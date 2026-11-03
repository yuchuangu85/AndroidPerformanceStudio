package com.androidperformancestudio.aiplatform.app

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.ReportLanguage
import com.androidperformancestudio.aiplatform.model.AiProviderDriver
import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.aiplatform.model.ProviderAvailability
import com.androidperformancestudio.aiplatform.model.ProviderCapability
import com.androidperformancestudio.aiplatform.model.ProviderDescriptor
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.ProviderUnavailableCode
import com.androidperformancestudio.aiplatform.provider.claude.ClaudeCliProvider
import com.androidperformancestudio.aiplatform.provider.codex.CodexResponsesProvider
import com.androidperformancestudio.aiplatform.providers.GatewayAnalysisProvider
import com.androidperformancestudio.aiplatform.providers.LocalProviderConfigResolver
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.providers.ProviderRegistry
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.registry.PlatformDirectories
import com.androidperformancestudio.aiplatform.runtime.ApplicationAiService
import com.androidperformancestudio.aiplatform.runtime.MainAgentOrchestrator
import com.androidperformancestudio.aiplatform.script.ArtifactPublisher
import com.androidperformancestudio.aiplatform.script.SandboxAvailability
import com.androidperformancestudio.aiplatform.script.SandboxedScriptRuntime
import com.androidperformancestudio.aiplatform.storage.SqliteConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences

public data class AiPlatformSettings(
    val providerId: String = "openai",
    val model: String = "",
    val allowArtifacts: Boolean = false,
    val agentId: String = "aps.main",
    val allowScripts: Boolean = false,
    val reportFormat: ArtifactFormat = ArtifactFormat.HTML,
    val reportLanguage: ReportLanguage = ReportLanguage.AUTO,
) {
    init {
        require(providerId in setOf("openai", "codex", "claude") && model.length <= 128)
        com.androidperformancestudio.aiplatform.model
            .requireStableId(agentId)
    }
}

/** Settings hold no credential values or raw provider config. */
public class AiPlatformSettingsStore(
    private val preferences: Preferences = Preferences.userRoot().node("com/androidperformancestudio/ai-platform"),
) {
    private val mutableSettings =
        states.computeIfAbsent(preferences.isUserNode to preferences.absolutePath()) {
            MutableStateFlow(readSettings())
        }

    public val settings: StateFlow<AiPlatformSettings> = mutableSettings.asStateFlow()

    public fun load(): AiPlatformSettings = mutableSettings.value

    private fun readSettings(): AiPlatformSettings =
        runCatching {
            AiPlatformSettings(
                preferences.get("provider", "openai"),
                preferences.get("model", ""),
                preferences.getBoolean("artifacts", false),
                preferences.get("agent", "aps.main"),
                preferences.getBoolean("scripts", false),
                ArtifactFormat.entries.firstOrNull { it.name == preferences.get("reportFormat", null) } ?: ArtifactFormat.HTML,
                ReportLanguage.entries.firstOrNull { it.name == preferences.get("reportLanguage", null) } ?: ReportLanguage.AUTO,
            )
        }.getOrElse { AiPlatformSettings() }

    public fun save(settings: AiPlatformSettings) {
        synchronized(mutableSettings) { persist { settings } }
    }

    public fun update(transform: (AiPlatformSettings) -> AiPlatformSettings) {
        synchronized(mutableSettings) { persist(transform) }
    }

    private fun persist(transform: (AiPlatformSettings) -> AiPlatformSettings) {
        val current = mutableSettings.value
        val previous = preferenceKeys.associateWith { preferences.get(it, null) }
        val settings = transform(current)
        try {
            preferences.put("provider", settings.providerId)
            preferences.put("model", settings.model)
            preferences.putBoolean("artifacts", settings.allowArtifacts)
            preferences.put("agent", settings.agentId)
            preferences.putBoolean("scripts", settings.allowScripts)
            preferences.put("reportFormat", settings.reportFormat.name)
            preferences.put("reportLanguage", settings.reportLanguage.name)
            preferences.flush()
        } catch (failure: Exception) {
            // Restore exact persisted values, including absent keys, before reporting failure.
            previous.forEach { (key, value) ->
                runCatching { if (value == null) preferences.remove(key) else preferences.put(key, value) }
            }
            runCatching { preferences.flush() }
            throw failure
        }
        mutableSettings.value = settings
    }

    private companion object {
        val preferenceKeys = listOf("provider", "model", "artifacts", "agent", "scripts", "reportFormat", "reportLanguage")
        val states = ConcurrentHashMap<Pair<Boolean, String>, MutableStateFlow<AiPlatformSettings>>()
    }
}

/** Exclusive desktop ownership makes startup interruption recovery safe; no automatic execution. */
public class AiPlatformRuntime private constructor(
    public val directories: PlatformDirectories,
    public val registry: ExtensionRegistry,
    public val providers: ProviderRegistry,
    public val history: SqliteConversationRepository,
    public val artifacts: ArtifactPublisher,
    public val service: ApplicationAiService,
    public val scripts: SandboxedScriptRuntime?,
    private val scope: CoroutineScope,
    private val profileChannel: FileChannel,
    private val profileLock: java.nio.channels.FileLock,
) : AutoCloseable {
    private val shutdownMutex = Mutex()
    private var closed = false

    /**
     * Cancel and join this runtime's IO children before closing history or releasing profile ownership.
     * A slow/non-cooperative provider keeps the profile locked rather than authorizing overlapping runs.
     */
    public suspend fun shutdown(): Unit =
        withContext(NonCancellable) {
            shutdownMutex.withLock {
                if (closed) return@withLock
                var failed = false
                try {
                    try {
                        service.cancel()
                    } catch (_: Exception) {
                        failed = true
                    }
                    scope.coroutineContext[Job]?.cancelAndJoin()
                    try {
                        history.close()
                    } catch (_: Exception) {
                        failed = true
                    }
                } finally {
                    try {
                        if (profileLock.isValid) profileLock.release()
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        try {
                            profileChannel.close()
                        } catch (_: Exception) {
                            failed = true
                        }
                        closed = true
                    }
                }
                if (failed) error("E_AI_SHUTDOWN")
            }
        }

    /** Desktop disposal is outside the private IO scope; provider cleanup never needs the UI thread. */
    override fun close(): Unit = runBlocking { shutdown() }

    public companion object {
        public fun open(
            root: Path = Path.of(System.getProperty("user.home"), ".android-performance-studio"),
            openAiGateway: (String) -> AiAnalysisGateway,
            openAiAvailable: () -> Boolean,
            sourceAuthorization: (AnalysisRequest) -> Boolean = { false },
            claudeEnvironment: () -> Map<String, String> = { emptyMap() },
            codexGateway: (String) -> AiAnalysisGateway = openAiGateway,
            codexAvailable: () -> Boolean = openAiAvailable,
            connectionRevision: (String) -> String = { "" },
            gatewayForRun: ((providerId: String, model: String, expectedRevision: String?) -> AiAnalysisGateway)? = null,
            claudeEnvironmentForRun: ((expectedRevision: String?) -> Map<String, String>)? = null,
        ): AiPlatformRuntime {
            val directories = PlatformDirectories(root)
            directories.initialize()
            val path = directories.providerState.resolve("desktop-ai.lock")
            require(!Files.isSymbolicLink(path)) { "E_PROFILE_LOCK" }
            val channel = FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS)
            val lock =
                try {
                    channel.tryLock() ?: error("E_AI_PROFILE_BUSY")
                } catch (_: Exception) {
                    channel.close()
                    error("E_AI_PROFILE_BUSY")
                }
            var store: SqliteConversationRepository? = null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val registry = ExtensionRegistry(directories)
                registry.installBuiltins()
                val openAi =
                    object : AiProviderDriver {
                        override val descriptor = ProviderDescriptor("openai", setOf(ProviderCapability.STRUCTURED_ANALYSIS))

                        override suspend fun discover(): ProviderAvailability =
                            if (openAiAvailable()) {
                                ProviderAvailability.Available("openai-responses-v1")
                            } else {
                                ProviderAvailability.Unavailable(ProviderUnavailableCode.MISSING_CREDENTIAL)
                            }

                        override fun run(request: ProviderRunRequest): Flow<ProviderEvent> =
                            GatewayAnalysisProvider(
                                "openai",
                                request.model,
                                if (gatewayForRun !=
                                    null
                                ) {
                                    gatewayForRun("openai", request.model, request.connectionRevision)
                                } else {
                                    openAiGateway(request.model)
                                },
                                { discover() },
                            ).run(request)
                    }
                val resolver = LocalProviderConfigResolver()
                val drivers =
                    mutableListOf<AiProviderDriver>(
                        openAi,
                        CodexResponsesProvider(
                            codexGateway,
                            codexAvailable,
                            gatewayForRun =
                                gatewayForRun?.let { factory ->
                                    { request -> factory("codex", request.model, request.connectionRevision) }
                                },
                        ),
                    )
                executable("claude")?.let {
                    drivers +=
                        ClaudeCliProvider(
                            it,
                            resolver,
                            connectionEnvironment = claudeEnvironment,
                            connectionEnvironmentForRun =
                                claudeEnvironmentForRun?.let { factory ->
                                    { request -> factory(request.connectionRevision) }
                                },
                        )
                }
                val providers = ProviderRegistry(drivers)
                val history = SqliteConversationRepository(directories.conversations.resolve("platform.sqlite")).also { store = it }
                history.recoverInterrupted()
                val artifacts = ArtifactPublisher(directories.artifacts)
                val scripts =
                    scriptSandboxToolchain()?.let { toolchain ->
                        SandboxedScriptRuntime(registry, directories.providerState.resolve("script-work"), toolchain)
                    }
                val orchestrator =
                    MainAgentOrchestrator(
                        registry,
                        ProviderBroker(providers),
                        history,
                        sourceAuthorization,
                        artifactPublisher = artifacts,
                        scriptRunner = scripts?.let { sandbox -> sandbox::run },
                        scriptSupport = { scripts?.discover(it) is SandboxAvailability.Available },
                        connectionRevision = connectionRevision,
                    )
                val service = ApplicationAiService(orchestrator, history, scope)
                return AiPlatformRuntime(directories, registry, providers, history, artifacts, service, scripts, scope, channel, lock)
            } catch (_: Exception) {
                scope.cancel()
                store?.close()
                lock.release()
                channel.close()
                error("E_AI_STARTUP")
            }
        }
    }
}

/** Verify actual executable candidates; Finder's restricted PATH is not assumed to include runtimes. */
public fun executable(name: String): Path? {
    require(name in setOf("codex", "claude", "python3", "node"))
    val directories =
        System
            .getenv("PATH")
            .orEmpty()
            .split(java.io.File.pathSeparator)
            .filter(String::isNotBlank) +
            listOf("/opt/homebrew/bin", "/usr/local/bin", "${System.getProperty("user.home")}/.local/bin")
    return directories
        .asSequence()
        .map {
            Path
                .of(
                    it,
                ).resolve(if (System.getProperty("os.name").startsWith("Windows")) "$name.exe" else name)
        }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?.toAbsolutePath()
        ?.normalize()
}
