package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Path

enum class CapabilityReadiness {
    READY,
    LIMITED,
    BLOCKED,
}

enum class ProfilingScope {
    ANY_PROCESS,
    PROFILEABLE_OR_DEBUGGABLE_APPS,
    DEBUGGABLE_APPS,
}

enum class RootAccess {
    ACTIVE,
    AVAILABLE_AFTER_ADB_ROOT,
    UNAVAILABLE,
}

enum class DeviceCapabilityLimitation {
    SIMPLEPERF_UNAVAILABLE,
    ROOT_UNAVAILABLE,
    ADB_ROOT_NOT_ACTIVE,
    APP_MUST_BE_PROFILEABLE_OR_DEBUGGABLE,
    DEBUGGABLE_APP_REQUIRED,
}

data class DeviceCapabilities(
    val serial: String,
    val readiness: CapabilityReadiness,
    val rootAccess: RootAccess,
    val profilingScope: ProfilingScope,
    val simpleperfVersion: String?,
    val eventNames: List<String>,
    val limitations: Set<DeviceCapabilityLimitation>,
) {
    val isRoot: Boolean
        get() = rootAccess == RootAccess.ACTIVE
}

class AdbDeviceCapabilityDetector(
    private val adbExecutable: Path,
    private val processInvocation: ProcessInvocation = { request, signal ->
        JvmProcessRunner().run(request, signal)
    },
) {
    suspend fun detect(
        properties: AndroidDeviceProperties,
        cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal(),
    ): StudioResult<DeviceCapabilities> =
        when (val rootResult = detectRoot(properties.serial, cancellationSignal)) {
            is StudioResult.Failure -> rootResult
            is StudioResult.Success -> detectWithRoot(properties, rootResult.value, cancellationSignal)
        }

    private suspend fun detectWithRoot(
        properties: AndroidDeviceProperties,
        isRoot: Boolean,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<DeviceCapabilities> =
        if (isRoot) {
            detectWithRootAccess(properties, RootAccess.ACTIVE, cancellationSignal)
        } else {
            when (val buildType = detectBuildType(properties.serial, cancellationSignal)) {
                is StudioResult.Failure -> buildType
                is StudioResult.Success ->
                    detectWithRootAccess(
                        properties,
                        buildType.value.toRootAccess(),
                        cancellationSignal,
                    )
            }
        }

    private suspend fun detectWithRootAccess(
        properties: AndroidDeviceProperties,
        rootAccess: RootAccess,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<DeviceCapabilities> =
        when (val simpleperfResult = detectSimpleperf(properties.serial, cancellationSignal)) {
            is StudioResult.Failure -> simpleperfResult
            is StudioResult.Success -> {
                val probe = simpleperfResult.value
                if (!probe.available) {
                    StudioResult.Success(
                        DeviceCapabilityEvaluator.evaluate(properties, rootAccess, false, null, emptyList()),
                    )
                } else {
                    detectEvents(properties, rootAccess, probe, cancellationSignal)
                }
            }
        }

    private suspend fun detectEvents(
        properties: AndroidDeviceProperties,
        rootAccess: RootAccess,
        probe: SimpleperfProbe,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<DeviceCapabilities> {
        val request = adbShellRequest(properties.serial, "simpleperf", "list")
        return when (val result = processInvocation(request, cancellationSignal)) {
            is ProcessRunResult.Completed ->
                StudioResult.Success(
                    DeviceCapabilityEvaluator.evaluate(
                        properties,
                        rootAccess,
                        true,
                        probe.version,
                        parseEventNames(result.output.stdout.text),
                    ),
                )
            is ProcessRunResult.Failed ->
                if (result.error.category == ErrorCategory.PROCESS_EXIT) {
                    StudioResult.Success(
                        DeviceCapabilityEvaluator.evaluate(properties, rootAccess, true, probe.version, emptyList()),
                    )
                } else {
                    StudioResult.Failure(result.error)
                }
        }
    }

    private suspend fun detectBuildType(
        serial: String,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<String> {
        val request = adbShellRequest(serial, "getprop", "ro.build.type")
        return when (val result = processInvocation(request, cancellationSignal)) {
            is ProcessRunResult.Completed ->
                StudioResult.Success(
                    result.output.stdout.text
                        .trim(),
                )
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
    }

    private suspend fun detectRoot(
        serial: String,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<Boolean> {
        val request = adbShellRequest(serial, "id", "-u")
        return when (val result = processInvocation(request, cancellationSignal)) {
            is ProcessRunResult.Completed -> parseRoot(result.output)
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
    }

    private fun parseRoot(output: ProcessOutput): StudioResult<Boolean> {
        val uid =
            output.stdout.text
                .trim()
                .toIntOrNull()
                ?.takeIf { it >= 0 }
        return if (uid == null) {
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.DATA_VALIDATION,
                    code = "ADB_SHELL_UID_INVALID",
                    message = "Invalid adb shell uid: ${output.stdout.text.trim()}",
                ),
            )
        } else {
            StudioResult.Success(uid == ROOT_UID)
        }
    }

    private suspend fun detectSimpleperf(
        serial: String,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<SimpleperfProbe> {
        val request = adbShellRequest(serial, "simpleperf", "--version")
        return when (val result = processInvocation(request, cancellationSignal)) {
            is ProcessRunResult.Completed ->
                StudioResult.Success(
                    SimpleperfProbe(
                        available = true,
                        version = firstOutputLine(result.output),
                    ),
                )
            is ProcessRunResult.Failed ->
                if (result.error.category == ErrorCategory.PROCESS_EXIT) {
                    StudioResult.Success(SimpleperfProbe(available = false, version = null))
                } else {
                    StudioResult.Failure(result.error)
                }
        }
    }

    private fun firstOutputLine(output: ProcessOutput): String? =
        sequenceOf(output.stdout.text, output.stderr.text)
            .flatMap(CharSequence::lineSequence)
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)

    private fun adbShellRequest(
        serial: String,
        vararg shellArguments: String,
    ): ProcessRequest =
        ProcessRequest(
            executable = adbExecutable,
            arguments = listOf("-s", serial, "shell") + shellArguments,
        )

    private data class SimpleperfProbe(
        val available: Boolean,
        val version: String?,
    )

    companion object {
        private const val ROOT_UID = 0

        internal fun parseEventNames(output: String): List<String> =
            output
                .lineSequence()
                .map(String::trim)
                .map { it.substringBefore(' ').substringBefore('(') }
                .filter { it.matches(EVENT_NAME_PATTERN) }
                .filterNot { it in NON_EVENT_LABELS }
                .distinct()
                .toList()

        private val EVENT_NAME_PATTERN = Regex("[a-z][a-z0-9_-]*(?::[uk])?")
        private val NON_EVENT_LABELS = setOf("list", "event", "events", "hardware", "software", "tracepoint")
    }
}

private object DeviceCapabilityEvaluator {
    fun evaluate(
        properties: AndroidDeviceProperties,
        rootAccess: RootAccess,
        simpleperfAvailable: Boolean,
        simpleperfVersion: String?,
        eventNames: List<String>,
    ): DeviceCapabilities {
        val profilingScope = profilingScope(properties.sdkInt, rootAccess)
        val limitations = limitations(rootAccess, simpleperfAvailable, profilingScope)
        val readiness =
            when {
                !simpleperfAvailable -> CapabilityReadiness.BLOCKED
                rootAccess == RootAccess.ACTIVE -> CapabilityReadiness.READY
                else -> CapabilityReadiness.LIMITED
            }
        return DeviceCapabilities(
            serial = properties.serial,
            readiness = readiness,
            rootAccess = rootAccess,
            profilingScope = profilingScope,
            simpleperfVersion = simpleperfVersion,
            eventNames = eventNames,
            limitations = limitations,
        )
    }

    private fun profilingScope(
        sdkInt: Int,
        rootAccess: RootAccess,
    ): ProfilingScope =
        when {
            rootAccess == RootAccess.ACTIVE -> ProfilingScope.ANY_PROCESS
            sdkInt >= PROFILEABLE_MIN_SDK -> ProfilingScope.PROFILEABLE_OR_DEBUGGABLE_APPS
            else -> ProfilingScope.DEBUGGABLE_APPS
        }

    private fun limitations(
        rootAccess: RootAccess,
        simpleperfAvailable: Boolean,
        profilingScope: ProfilingScope,
    ): Set<DeviceCapabilityLimitation> =
        buildSet {
            if (!simpleperfAvailable) add(DeviceCapabilityLimitation.SIMPLEPERF_UNAVAILABLE)
            addRootLimitation(rootAccess)
            addProfilingLimitation(profilingScope)
        }

    private fun MutableSet<DeviceCapabilityLimitation>.addRootLimitation(rootAccess: RootAccess) {
        when (rootAccess) {
            RootAccess.ACTIVE -> Unit
            RootAccess.AVAILABLE_AFTER_ADB_ROOT -> add(DeviceCapabilityLimitation.ADB_ROOT_NOT_ACTIVE)
            RootAccess.UNAVAILABLE -> add(DeviceCapabilityLimitation.ROOT_UNAVAILABLE)
        }
    }

    private fun MutableSet<DeviceCapabilityLimitation>.addProfilingLimitation(scope: ProfilingScope) {
        when (scope) {
            ProfilingScope.ANY_PROCESS -> Unit
            ProfilingScope.PROFILEABLE_OR_DEBUGGABLE_APPS ->
                add(DeviceCapabilityLimitation.APP_MUST_BE_PROFILEABLE_OR_DEBUGGABLE)
            ProfilingScope.DEBUGGABLE_APPS -> add(DeviceCapabilityLimitation.DEBUGGABLE_APP_REQUIRED)
        }
    }

    private const val PROFILEABLE_MIN_SDK = 29
}

private fun String.toRootAccess(): RootAccess =
    if (this == "userdebug") {
        RootAccess.AVAILABLE_AFTER_ADB_ROOT
    } else {
        RootAccess.UNAVAILABLE
    }
