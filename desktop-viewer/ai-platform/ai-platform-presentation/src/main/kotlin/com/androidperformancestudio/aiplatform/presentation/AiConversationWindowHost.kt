@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.androidperformancestudio.ai.ReportLanguage
import com.androidperformancestudio.ai.isDirectChat
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettings
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.app.BusinessContext
import com.androidperformancestudio.aiplatform.app.contextDigest
import com.androidperformancestudio.aiplatform.app.createChatContext
import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.runtime.AgentPolicyException
import com.androidperformancestudio.aiplatform.runtime.AiApplicationState
import com.androidperformancestudio.aiplatform.runtime.ChatSessionSummary
import com.androidperformancestudio.aiplatform.runtime.ChatTurn
import com.androidperformancestudio.aiplatform.runtime.PreparedAgentRun
import com.androidperformancestudio.aiplatform.storage.ConversationStorageException
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.WindowSurfaceEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Composable
public fun AiConversationWindowHost(
    visible: Boolean,
    runtime: AiPlatformRuntime?,
    startupError: String?,
    context: BusinessContext?,
    language: UiLanguage,
    darkTheme: Boolean,
    defaultModel: String,
    onClose: () -> Unit,
    onOpenSourceCandidate: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    windowChrome: @Composable FrameWindowScope.(title: String) -> Unit = {},
    openRequestId: Long = 0,
    loadModels: suspend (providerId: String, refresh: Boolean) -> List<String> = { _, _ -> emptyList() },
    selectModel: ((providerId: String, model: String) -> Unit)? = null,
) {
    // Unsent drafts remain local across closing/reopening this window, never in provider history.
    val memory = remember(runtime) { ChatWindowMemory(context) }
    if (!visible) return
    val title = chatLabel(language, "AI Chat", "AI 对话")
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
    Window(
        onCloseRequest = onClose,
        title = title,
        state = windowState,
    ) {
        window.minimumSize = java.awt.Dimension(900, 720)
        AiConversationWindowActivationEffect(openRequestId, windowState)
        val background = (if (darkTheme) DshChatColors.Dark else DshChatColors.Light).background
        WindowSurfaceEffect(background)
        Column(Modifier.fillMaxSize().background(background)) {
            // Native decoration belongs to the desktop host and stays outside the scaled viewer theme.
            windowChrome(title)
            Box(Modifier.weight(1f)) {
                ViewerTheme(darkTheme = darkTheme) {
                    if (runtime == null) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CopyableText(startupError ?: "E_AI_STARTING", language = language)
                        }
                    } else {
                        ConversationWorkspace(
                            runtime,
                            context,
                            language,
                            defaultModel,
                            onOpenSourceCandidate,
                            onOpenSettings,
                            null,
                            memory,
                            loadModels = loadModels,
                            selectModel = selectModel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
public fun AiConversationWorkspace(
    runtime: AiPlatformRuntime,
    context: BusinessContext?,
    language: UiLanguage,
    defaultModel: String,
    onOpenSourceCandidate: (String) -> Unit = {},
    settingsStoreOverride: AiPlatformSettingsStore? = null,
    onOpenSettings: () -> Unit = {},
    loadModels: suspend (providerId: String, refresh: Boolean) -> List<String> = { _, _ -> emptyList() },
    selectModel: ((providerId: String, model: String) -> Unit)? = null,
) {
    val memory = remember(runtime) { ChatWindowMemory(context) }
    ConversationWorkspace(
        runtime,
        context,
        language,
        defaultModel,
        onOpenSourceCandidate,
        onOpenSettings,
        settingsStoreOverride,
        memory,
        loadModels = loadModels,
        selectModel = selectModel,
    )
}

@Composable
internal fun ConversationWorkspace(
    runtime: AiPlatformRuntime,
    context: BusinessContext?,
    language: UiLanguage,
    defaultModel: String,
    onSource: (String) -> Unit,
    onOpenSettings: () -> Unit,
    settingsStoreOverride: AiPlatformSettingsStore?,
    memory: ChatWindowMemory,
    interactionMutex: Mutex = remember { Mutex() },
    loadModels: suspend (providerId: String, refresh: Boolean) -> List<String> = { _, _ -> emptyList() },
    selectModel: ((providerId: String, model: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val state by runtime.service.state.collectAsState()
    val store = remember(settingsStoreOverride) { settingsStoreOverride ?: AiPlatformSettingsStore() }
    val latestSettings by store.settings.collectAsState()
    var settings by remember(store) { mutableStateOf(store.settings.value) }
    var catalogProvider by remember(store) { mutableStateOf(store.settings.value.providerId) }
    var catalogModels by remember(store) { mutableStateOf<List<String>>(emptyList()) }
    var catalogLoading by remember(store) { mutableStateOf(false) }
    var catalogError by remember(store) { mutableStateOf<String?>(null) }
    var catalogGeneration by remember(store) { mutableStateOf(0L) }
    var catalogOpen by remember(store) { mutableStateOf(false) }
    var bound by memory.bound
    var boundRunId by memory.boundRunId
    var boundHistoryId by remember { mutableStateOf<String?>(null) }
    var navigationRevision by memory.navigationRevision
    var includeSource by remember { mutableStateOf(false) }
    var sourceRequired by memory.sourceRequired
    var sessions by remember { mutableStateOf<List<ChatSessionSummary>>(emptyList()) }
    var turns by remember { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var loadedConversationId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var evidenceSelection by remember { mutableStateOf<EvidenceSelection?>(null) }
    var parentConversationId by remember { mutableStateOf<String?>(null) }
    var parentTurnId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0L) }
    var preparing by remember { mutableStateOf(false) }
    var transitioning by remember { mutableStateOf(false) }
    var preparationJob by remember { mutableStateOf<Job?>(null) }
    var preparationGeneration by remember { mutableStateOf(0L) }
    val busy = preparing || transitioning || state.status?.terminal == false

    fun refreshModels(refresh: Boolean = false) {
        if (busy) return
        val provider = store.settings.value.providerId
        if (catalogLoading && catalogProvider == provider) return
        if (catalogProvider != provider) catalogModels = emptyList()
        catalogProvider = provider
        val generation = ++catalogGeneration
        catalogLoading = true
        catalogError = null
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { loadModels(provider, refresh).toList() }
                if (catalogGeneration == generation && store.settings.value.providerId == provider) {
                    catalogModels = loaded
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (catalogGeneration == generation && store.settings.value.providerId == provider) {
                    catalogModels = emptyList()
                    catalogError = "E_AI_MODEL_CATALOG"
                }
            } finally {
                if (catalogGeneration == generation) catalogLoading = false
            }
        }
    }

    LaunchedEffect(latestSettings.providerId, busy) {
        if (catalogProvider != latestSettings.providerId) {
            catalogProvider = latestSettings.providerId
            catalogGeneration++
            catalogModels = emptyList()
            catalogLoading = false
            catalogError = null
            if (catalogOpen && !busy) refreshModels()
        }
    }
    val displayContext = preparedDisplayContext(state.prepared.takeIf { busy }, bound)
    val sourceReauthorizationRequired =
        !includeSource &&
            (
                sourceRequired ||
                    busy &&
                    state.prepared
                        ?.analysisPreview
                        ?.sourceCandidates
                        ?.isNotEmpty() == true
            )
    val executableContext =
        bound.takeIf {
            (!state.restored || boundHistoryId == state.conversationId) &&
                (!busy || state.prepared?.approval?.runId == boundRunId)
        }

    fun action(
        onFinally: () -> Unit = {},
        block: suspend () -> Unit,
    ): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                message = null
                interactionMutex.withLock { block() }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: AgentPolicyException) {
                message = failure.code.takeIf { it.matches(Regex("E_[A-Z0-9_]{1,80}")) } ?: "E_AI_ACTION"
            } catch (failure: ConversationStorageException) {
                message = failure.code.takeIf { it.matches(Regex("E_[A-Z0-9_]{1,80}")) } ?: "E_AI_ACTION"
            } catch (_: Exception) {
                message = "E_AI_ACTION"
            } finally {
                onFinally()
            }
        }

    fun navigate(block: suspend () -> Unit) {
        if (preparing || transitioning) return
        transitioning = true
        action(onFinally = { transitioning = false }) { block() }
    }

    suspend fun beginConversation(
        keepDraft: Boolean = false,
        bindCurrentContext: Boolean = true,
    ) {
        val draft = memory.draft
        memory.drafts[memory.conversationId] = memory.draft
        memory.queued = memory.queued.filterNot { it.conversationId == memory.conversationId }
        memory.retryDraft = null
        withContext(Dispatchers.IO) { runtime.service.newConversation() }
        memory.conversationId = UUID.randomUUID().toString()
        memory.draft = if (keepDraft) draft else TextFieldValue("")
        bound = if (bindCurrentContext) context ?: createChatContext() else createChatContext()
        boundRunId = null
        boundHistoryId = null
        navigationRevision = context?.revision
        includeSource = false
        sourceRequired = false
        parentConversationId = null
        parentTurnId = null
        evidenceSelection = null
        turns = emptyList()
        refresh++
    }

    suspend fun prepare(
        question: String,
        frozenContext: BusinessContext,
        selectedSettings: AiPlatformSettings,
        source: Boolean,
        submittedDraft: TextFieldValue? = null,
    ) {
        var acquired: PreparedAgentRun? = null
        try {
            val id = memory.conversationId
            val request =
                frozenContext.request.copy(
                    sourceCandidates = if (source) frozenContext.request.sourceCandidates else emptyList(),
                    outputLanguage = selectedSettings.reportLanguage,
                )
            val parent =
                parentTurnId ?: state.lastTurnRunId?.takeIf {
                    state.conversationId == id && state.status?.terminal == true
                } ?: turns.lastOrNull()?.takeIf { it.status.terminal }?.runId
            val runSettings =
                if (request.isDirectChat) {
                    selectedSettings.copy(
                        agentId = "aps.chat",
                        allowArtifacts = false,
                        allowScripts = false,
                    )
                } else {
                    selectedSettings
                }
            val permissions = withContext(Dispatchers.IO) { requestedPermissions(runtime, runSettings, source) }
            withContext(Dispatchers.IO) {
                acquired =
                    runtime.service.prepare(
                        id,
                        request,
                        runSettings.agentId,
                        selectedSettings.providerId,
                        selectedSettings.model.ifBlank { defaultModel },
                        permissions,
                        question.takeIf(String::isNotBlank),
                        parent,
                        parentConversationId,
                        reportFormat = selectedSettings.reportFormat,
                    )
            }
            parentConversationId = null
            parentTurnId = null
            boundRunId = acquired?.approval?.runId
            sourceRequired = request.sourceCandidates.isNotEmpty()
            if (submittedDraft != null && memory.draft == submittedDraft) memory.draft = TextFieldValue("")
            memory.drafts.remove(id)
            refresh++
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { acquired?.let { runtime.service.cancel(it.approval.runId) } }
            throw cancelled
        }
    }

    fun launchPreparation(
        question: String,
        canonical: BusinessContext,
        selectedSettings: AiPlatformSettings,
        source: Boolean,
        submittedDraft: TextFieldValue? = null,
    ) {
        val generation = preparationGeneration + 1
        preparationGeneration = generation
        settings = selectedSettings
        preparing = true
        preparationJob =
            action(
                onFinally = {
                    if (preparationGeneration == generation) preparing = false
                },
            ) {
                try {
                    prepare(question, canonical, selectedSettings, source, submittedDraft)
                    memory.retryDraft = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    memory.retryDraft = QueuedChatDraft(memory.conversationId, question, canonical, source)
                    throw failure
                }
            }
    }

    fun send() {
        if (preparing || transitioning) return
        val question = memory.draft.text.trim()
        val canonical = executableContext
        if (question.isEmpty() || question.length > 4096) return
        if (canonical == null) {
            message = "E_CONTEXT_UNAVAILABLE"
            return
        }
        if (sourceReauthorizationRequired) {
            message = "E_SOURCE_REAUTH_REQUIRED"
            return
        }
        if (busy) {
            if (memory.queued.count { it.conversationId == memory.conversationId } >= MAX_QUEUED_MESSAGES) {
                message = "E_QUEUE_LIMIT"
                return
            }
            memory.queued = memory.queued + QueuedChatDraft(memory.conversationId, question, canonical, includeSource)
            memory.draft = TextFieldValue("")
        } else {
            val selectedSettings = store.settings.value
            val source = includeSource
            val submittedDraft = memory.draft
            launchPreparation(question, canonical, selectedSettings, source, submittedDraft)
        }
    }

    fun retry(runId: String? = null) {
        if (busy) return
        val canonical = executableContext
        if (canonical == null || sourceReauthorizationRequired) {
            message = if (sourceReauthorizationRequired) "E_SOURCE_REAUTH_REQUIRED" else "E_CONTEXT_UNAVAILABLE"
            return
        }
        val currentTurns = turns.takeIf { loadedConversationId == memory.conversationId }.orEmpty()
        val failedTurn =
            if (runId != null) {
                currentTurns.singleOrNull { it.runId == runId && it.status.allowsChatRetry() && it.result == null }
            } else {
                currentTurns.lastOrNull()?.takeIf { it.status.allowsChatRetry() && it.result == null }
            }
        val failedDraft = memory.retryDraft?.takeIf { runId == null && it.conversationId == memory.conversationId }
        val question = failedDraft?.question ?: failedTurn?.question.orEmpty()
        if (failedDraft == null && failedTurn == null || question.isBlank() && canonical.request.isDirectChat) {
            message = "E_RETRY_UNAVAILABLE"
            return
        }
        launchPreparation(question, canonical, store.settings.value, includeSource)
    }

    LaunchedEffect(latestSettings, busy) { if (!busy) settings = latestSettings }
    LaunchedEffect(context?.revision, state.conversationId, state.prepared?.approval?.runId, state.restored, state.status) {
        evidenceSelection = null
        when {
            state.restored -> {
                bound = null
                boundRunId = null
                boundHistoryId = null
                sourceRequired = false
                state.conversationId?.let { id ->
                    memory.conversationId = id
                    val record = withContext(Dispatchers.IO) { runtime.service.history().find { it.id == id } }
                    bound = record?.let { matchingChatContext(it, context) ?: matchingChatContext(it, createChatContext()) }
                    boundHistoryId = id.takeIf { bound != null }
                    sourceRequired = bound?.request?.sourceCandidates?.isNotEmpty() == true
                }
                includeSource = false
            }
            state.prepared != null && busy -> {
                val prepared = checkNotNull(state.prepared)
                val previousBound = bound
                bound = null
                boundRunId = null
                boundHistoryId = null
                memory.conversationId = prepared.conversationId
                val record =
                    withContext(Dispatchers.IO) {
                        runtime.service.history().find { it.id == prepared.conversationId }
                    }
                val currentBinding = record?.let { matchingChatContext(it, context) }
                bound = currentBinding
                    ?: record?.let {
                        matchingChatContext(it, previousBound)
                            ?: matchingChatContext(it, createChatContext())
                    }
                if (currentBinding != null) navigationRevision = context?.revision
                boundRunId = prepared.approval.runId.takeIf { bound != null }
                sourceRequired = bound?.request?.sourceCandidates?.isNotEmpty() == true
            }
            !busy && navigationRevision != context?.revision -> {
                val keepDraft = navigationRevision == null && memory.draft.text.isNotBlank()
                navigate { beginConversation(keepDraft = keepDraft) }
            }
        }
    }
    LaunchedEffect(runtime, state.conversationId, state.status, memory.conversationId, memory.showingArchived, refresh) {
        val id = memory.conversationId
        val loaded =
            withContext(Dispatchers.IO) {
                val history = runtime.service.chatHistory(includeArchived = memory.showingArchived)
                val selected = runtime.service.history().any { it.id == id }
                history to if (selected) runtime.service.transcript(id) else emptyList()
            }
        sessions = if (memory.showingArchived) loaded.first.filter { it.archived } else loaded.first
        if (memory.conversationId == id) {
            turns = loaded.second
            loadedConversationId = id
        }
    }
    LaunchedEffect(state.status, state.lastTurnRunId) {
        val next = memory.queued.firstOrNull { it.conversationId == memory.conversationId }
        if (!preparing &&
            !transitioning &&
            navigationRevision == context?.revision &&
            state.status == RunStatus.SUCCEEDED &&
            !state.restored &&
            next != null &&
            state.conversationId == next.conversationId
        ) {
            // Claim once before scheduling outside this effect: prepare changes this effect's keys.
            memory.queued = memory.queued.filterNot { it === next }
            // Freeze saved settings for the new request now, not when its draft was queued.
            launchPreparation(next.question, next.context, store.settings.value, next.includeSource)
        }
    }

    val visibleQueue = memory.queued.filter { it.conversationId == memory.conversationId }
    val shownTurns =
        turns.takeIf { loadedConversationId == memory.conversationId }.orEmpty().map { turn ->
            if (turn.runId == state.prepared?.approval?.runId && state.conversationId == memory.conversationId) {
                turn.copy(status = state.status ?: turn.status, result = state.result ?: turn.result)
            } else {
                turn
            }
        }
    DshChatSurface(
        state =
            ChatSurfaceState(
                sessions,
                memory.conversationId,
                shownTurns,
                memory.draft,
                displayContext?.takeUnless { it.request.isDirectChat }?.title,
                executableContext != null && !sourceReauthorizationRequired && !preparing && !transitioning,
                settings.model.ifBlank { defaultModel },
                busy,
                state.status,
                message ?: state.errorCode,
                state.prepared.takeIf { state.status == RunStatus.AWAITING_APPROVAL },
                state.timeline,
                includeSource,
                !busy && bound?.request?.sourceCandidates?.isNotEmpty() == true,
                visibleQueue.map { it.question },
                memory.showingArchived,
                models = catalogModels.takeIf { catalogProvider == latestSettings.providerId }.orEmpty(),
                modelsLoading = catalogLoading,
                modelsError = catalogError,
            ),
        actions =
            ChatSurfaceActions(
                onNew = { navigate { beginConversation() } },
                onSelect = { id ->
                    navigate {
                        if (state.restored && state.conversationId == id && memory.conversationId == id) return@navigate
                        memory.drafts[memory.conversationId] = memory.draft
                        bound = null
                        boundRunId = null
                        boundHistoryId = null
                        sourceRequired = false
                        includeSource = false
                        withContext(Dispatchers.IO) { runtime.service.restore(id) }
                        memory.conversationId = id
                        memory.draft = memory.drafts[id] ?: TextFieldValue("")
                        refresh++
                    }
                },
                onSend = ::send,
                onStop = {
                    preparationJob?.cancel()
                    val runId = state.prepared?.approval?.runId
                    action { runId?.let { runtime.service.cancel(it) } }
                },
                onApprove = { state.prepared?.let { prepared -> action { runtime.service.approveAndRun(prepared.approval) } } },
                onDraft = { memory.draft = it },
                onRename = { id, title ->
                    action {
                        withContext(Dispatchers.IO) { runtime.service.renameConversation(id, title) }
                        refresh++
                    }
                },
                onPin = { id, pinned ->
                    action {
                        withContext(Dispatchers.IO) { runtime.service.pinConversation(id, pinned) }
                        refresh++
                    }
                },
                onArchive = { id, archived ->
                    navigate {
                        if (id == state.conversationId && busy && archived) runtime.service.cancel()
                        withContext(Dispatchers.IO) { runtime.service.archiveConversation(id, archived) }
                        if (id == memory.conversationId && archived) beginConversation()
                        refresh++
                    }
                },
                onDelete = { id ->
                    navigate {
                        withContext(Dispatchers.IO) { runtime.service.deleteChatConversation(id) }
                        memory.queued = memory.queued.filterNot { it.conversationId == id }
                        memory.retryDraft = memory.retryDraft?.takeUnless { it.conversationId == id }
                        if (id == memory.conversationId) {
                            beginConversation()
                            loadedConversationId = null
                        }
                        memory.drafts.remove(id)
                        refresh++
                    }
                },
                onFork = { id, requestedTurn ->
                    if (!busy) {
                        navigate {
                            val record = withContext(Dispatchers.IO) { runtime.service.history().single { it.id == id } }
                            val canonical =
                                matchingChatContext(record, createChatContext()) ?: context
                                    ?: throw AgentPolicyException("E_CONTEXT_UNAVAILABLE")
                            if (record.analysisSessionId !=
                                canonical.request.sessionId.value
                            ) {
                                throw AgentPolicyException("E_CONTEXT_CHANGED")
                            }
                            val turn =
                                requestedTurn ?: record.turns.lastOrNull { it.status.terminal }?.runId
                                    ?: throw AgentPolicyException("E_PARENT_TURN")
                            beginConversation()
                            bound = canonical
                            parentConversationId = id
                            parentTurnId = turn
                        }
                    }
                },
                // Clipboard ownership and visible failure feedback belong to the surface.
                onCopySession = {},
                onEditQuestion = { question -> memory.draft = TextFieldValue(question, TextRange(question.length)) },
                onRetry = {
                    retry()
                },
                onRetryTurn = { retry(it) },
                onOpenSettings = onOpenSettings,
                onOpenModels = {
                    catalogOpen = true
                    refreshModels()
                },
                onRefreshModels = { refreshModels(refresh = true) },
                onCloseModels = { catalogOpen = false },
                onSelectModel = { model ->
                    if (!busy && !catalogLoading && model in catalogModels && catalogProvider == store.settings.value.providerId) {
                        val provider = catalogProvider
                        action {
                            if (runtime.service.state.value.status
                                    ?.terminal == false ||
                                preparing ||
                                transitioning
                            ) {
                                return@action
                            }
                            withContext(Dispatchers.IO) {
                                if (selectModel == null) {
                                    store.update {
                                        check(it.providerId == provider) { "E_PROVIDER_CONNECTION_CHANGED" }
                                        it.copy(model = model)
                                    }
                                } else {
                                    selectModel(provider, model)
                                }
                            }
                        }
                    }
                },
                onBindContext = {
                    if (!busy && context != null) {
                        navigate {
                            val record = withContext(Dispatchers.IO) { runtime.service.history().find { it.id == memory.conversationId } }
                            val matching = record?.let { matchingChatContext(it, context) }
                            if (matching != null) {
                                bound = matching
                                boundHistoryId = memory.conversationId
                                includeSource = false
                                sourceRequired = matching.request.sourceCandidates.isNotEmpty()
                                if (sourceRequired) message = "E_SOURCE_REAUTH_REQUIRED"
                            } else {
                                beginConversation(keepDraft = true)
                            }
                        }
                    } else if (context == null) {
                        message = "E_CONTEXT_UNAVAILABLE"
                    }
                },
                onStartOrdinaryChat = {
                    if (!busy) {
                        navigate { beginConversation(keepDraft = true, bindCurrentContext = false) }
                    }
                },
                onIncludeSource = {
                    if (!busy) {
                        includeSource = it
                        if (it) {
                            message = null
                        }
                    }
                },
                onEvidence = { id ->
                    val canonical = bound?.takeIf { selected -> selected.request.evidence.count { it.id == id } == 1 }
                    evidenceSelection = EvidenceSelection(id, canonical)
                },
                onSource = { id ->
                    if (bound?.request?.sourceCandidates?.count { it.id == id } == 1) {
                        onSource(id)
                    } else {
                        message = "E_SOURCE_CONTEXT_UNAVAILABLE"
                    }
                },
                onArtifact = { hash -> action { withContext(Dispatchers.IO) { previewArtifact(runtime, hash) } } },
                onRemoveQueued = { index ->
                    visibleQueue.getOrNull(index)?.let { selected -> memory.queued = memory.queued.filterNot { it === selected } }
                },
                onEditQueued = { index ->
                    visibleQueue.getOrNull(index)?.let { selected ->
                        memory.draft = TextFieldValue(selected.question, TextRange(selected.question.length))
                        memory.queued = memory.queued.filterNot { it === selected }
                    }
                },
                onShowArchived = { memory.showingArchived = it },
            ),
        language = language,
    )
    evidenceSelection?.let { selection ->
        EvidenceDialog(selection, language) { evidenceSelection = null }
    }
}

private fun requestedPermissions(
    runtime: AiPlatformRuntime,
    settings: AiPlatformSettings,
    includeSource: Boolean,
): Set<Permission> {
    val declared =
        runtime.registry
            .resolve(ExtensionKind.AGENT, settings.agentId)
            .manifest.permissions
    return buildSet {
        add(Permission.READ_EVIDENCE)
        add(Permission.PROVIDER_NETWORK)
        if (includeSource) {
            add(Permission.READ_SOURCE_CANDIDATES)
            add(Permission.SEND_SOURCE_SNIPPETS)
        }
        if (settings.allowArtifacts && Permission.WRITE_ARTIFACT in declared) add(Permission.WRITE_ARTIFACT)
        if (settings.allowScripts && Permission.EXECUTE_SCRIPT in declared) add(Permission.EXECUTE_SCRIPT)
    }
}

/** A pending approval displays only its frozen payload's context, never a new report's data. */
internal fun preparedDisplayContext(
    prepared: PreparedAgentRun?,
    fallback: BusinessContext?,
): BusinessContext? {
    if (prepared == null) return fallback
    val request = prepared.analysisPreview ?: return null
    val key =
        request.originProfiler.name
            .lowercase()
            .replace('_', '-')
    return BusinessContext(key, contextDigest(prepared.approval.payloadHash), request.scope.description, request)
}

internal fun resultBelongsToContext(
    state: AiApplicationState,
    bound: BusinessContext?,
): Boolean {
    val boundSession = bound?.request?.sessionId ?: return true
    val resultSession = state.result?.sessionId ?: state.prepared?.analysisPreview?.sessionId ?: return true
    return boundSession == resultSession
}

/** Reused session/reference IDs are not proof that a history item belongs to current evidence. */
internal fun matchingChatContext(
    record: ConversationRecord,
    context: BusinessContext?,
): BusinessContext? {
    if (context == null || record.analysisSessionId != context.request.sessionId.value) return null
    val candidates = listOf(context.request.copy(sourceCandidates = emptyList()), context.request)
    return candidates
        .firstOrNull { request ->
            val material =
                request
                    .copy(
                        userQuestion = null,
                        conversationContext = emptyList(),
                        outputLanguage = ReportLanguage.AUTO,
                    ).payloadText() +
                    "\n" + request.promptVersion + "\n" + request.payloadPolicyVersion
            contextDigest(material) == record.contextHash
        }?.let { context.copy(request = it) }
}

@Composable
private fun EvidenceDialog(
    selection: EvidenceSelection,
    language: UiLanguage,
    onClose: () -> Unit,
) {
    val evidence =
        selection.context
            ?.request
            ?.evidence
            ?.singleOrNull { it.id == selection.id }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(chatLabel(language, "Evidence & sources", "证据与源码")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (evidence == null) {
                    CopyableText("E_EVIDENCE_CONTEXT_UNAVAILABLE", Modifier.testTag("ai-evidence-context-unavailable"), language = language)
                    CopyableText(selection.id, language = language)
                } else {
                    Column(Modifier.testTag("ai-evidence-detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CopyableText(evidence.id, Modifier.testTag("ai-evidence-detail-id"), language = language)
                        CopyableText(evidence.kind, Modifier.testTag("ai-evidence-detail-kind"), language = language)
                        CopyableText(evidence.summary, Modifier.testTag("ai-evidence-detail-summary"), language = language)
                        CopyableText(evidence.structuredPayload, Modifier.testTag("ai-evidence-detail-payload"), language = language)
                        CopyableText(
                            "${selection.context.request.sessionId.value} · " +
                                "${selection.context.request.originProfiler} · ${selection.context.request.scope.kind}",
                            Modifier.testTag("ai-evidence-detail-identity"),
                            language = language,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(chatLabel(language, "Close", "关闭")) } },
    )
}

internal class ChatWindowMemory(
    initialContext: BusinessContext?,
) {
    val bound = mutableStateOf<BusinessContext?>(initialContext ?: createChatContext())
    val boundRunId = mutableStateOf<String?>(null)
    val navigationRevision = mutableStateOf(initialContext?.revision)
    val sourceRequired = mutableStateOf(false)
    var conversationId by mutableStateOf(UUID.randomUUID().toString())
    var draft by mutableStateOf(TextFieldValue(""))
    var showingArchived by mutableStateOf(false)
    var queued by mutableStateOf<List<QueuedChatDraft>>(emptyList())
    var retryDraft by mutableStateOf<QueuedChatDraft?>(null)
    val drafts = mutableMapOf<String, TextFieldValue>()
}

internal class QueuedChatDraft(
    val conversationId: String,
    val question: String,
    val context: BusinessContext,
    val includeSource: Boolean,
)

private data class EvidenceSelection(
    val id: String,
    val context: BusinessContext?,
)

private fun chatLabel(
    language: UiLanguage,
    en: String,
    zh: String,
): String = if (language == UiLanguage.SIMPLIFIED_CHINESE) zh else en

internal fun previewArtifact(
    runtime: AiPlatformRuntime,
    hash: String,
    open: (Path, ArtifactFormat) -> Unit = { target, format ->
        val desktop = java.awt.Desktop.getDesktop()
        when (format) {
            ArtifactFormat.HTML -> desktop.browse(target.toUri())
            ArtifactFormat.MARKDOWN -> desktop.open(target.toFile())
        }
    },
) {
    val metadata = runtime.artifacts.metadata(hash)
    val bytes = runtime.artifacts.read(hash)
    val extension =
        when (metadata.format) {
            ArtifactFormat.HTML -> "html"
            ArtifactFormat.MARKDOWN -> "md"
        }
    val directory = runtime.directories.artifacts.resolve("previews")
    Files.createDirectories(directory)
    require(!Files.isSymbolicLink(directory))
    val target = directory.resolve("$hash.$extension")
    require(!Files.isSymbolicLink(target))
    Files.write(target, bytes)
    open(target, metadata.format)
}

private const val MAX_QUEUED_MESSAGES = 8
