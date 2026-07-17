package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Path

data class AndroidPackage(
    val packageName: String,
    val debuggable: Boolean = false,
    val profileableByShell: Boolean = false,
)

data class AndroidProcess(
    val pid: Int,
    val parentPid: Int,
    val user: String,
    val name: String,
)

data class AndroidThread(
    val pid: Int,
    val tid: Int,
    val name: String,
)

data class AdbTargetSnapshot(
    val packages: List<AndroidPackage>,
    val processes: List<AndroidProcess>,
) {
    fun search(query: String): AdbTargetSnapshot {
        val normalized = query.trim()
        if (normalized.isEmpty()) return this
        return AdbTargetSnapshot(
            packages = packages.filter { it.packageName.contains(normalized, ignoreCase = true) },
            processes =
                processes.filter {
                    it.name.contains(normalized, ignoreCase = true) ||
                        it.user.contains(normalized, ignoreCase = true) ||
                        it.pid.toString().contains(normalized)
                },
        )
    }
}

class AdbTargetCatalog(
    private val adbExecutable: Path,
    private val processInvocation: ProcessInvocation = { request, signal ->
        JvmProcessRunner().run(request, signal)
    },
) {
    suspend fun refresh(
        serial: String,
        cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal(),
    ): StudioResult<AdbTargetSnapshot> =
        when (val packages = execute(serial, PACKAGE_ARGUMENTS, cancellationSignal)) {
            is StudioResult.Failure -> packages
            is StudioResult.Success -> refreshPackageCapabilities(serial, packages.value, cancellationSignal)
        }

    suspend fun listThreads(
        serial: String,
        pid: Int,
        cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal(),
    ): StudioResult<List<AndroidThread>> {
        require(pid > 0) { "pid must be positive" }
        return when (
            val output =
                execute(
                    serial,
                    listOf("ps", "-T", "-p", pid.toString(), "-o", "PID,TID,NAME"),
                    cancellationSignal,
                )
        ) {
            is StudioResult.Failure -> output
            is StudioResult.Success -> StudioResult.Success(AdbTargetParser.parseThreads(output.value))
        }
    }

    private suspend fun execute(
        serial: String,
        shellArguments: List<String>,
        cancellationSignal: ProcessCancellationSignal,
        captureLimit: Int = ProcessRequest.DEFAULT_CAPTURE_LIMIT,
    ): StudioResult<String> {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = listOf("-s", serial, "shell") + shellArguments,
                maxCapturedCharactersPerStream = captureLimit,
            )
        return when (val result = processInvocation(request, cancellationSignal)) {
            is ProcessRunResult.Completed -> StudioResult.Success(result.output.stdout.text)
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
    }

    private suspend fun refreshProcesses(
        serial: String,
        packages: List<AndroidPackage>,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<AdbTargetSnapshot> =
        when (val processes = execute(serial, PROCESS_ARGUMENTS, cancellationSignal)) {
            is StudioResult.Failure -> processes
            is StudioResult.Success ->
                StudioResult.Success(
                    AdbTargetSnapshot(
                        packages = packages,
                        processes = AdbTargetParser.parseProcesses(processes.value),
                    ),
                )
        }

    private suspend fun refreshPackageCapabilities(
        serial: String,
        packageOutput: String,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<AdbTargetSnapshot> =
        when (val packageDetails = loadPackageDetails(serial, cancellationSignal)) {
            is StudioResult.Failure -> packageDetails
            is StudioResult.Success ->
                refreshProcesses(
                    serial = serial,
                    packages =
                        AdbTargetParser.applyPackageCapabilities(
                            packages = AdbTargetParser.parsePackages(packageOutput),
                            packageDetails = packageDetails.value,
                        ),
                    cancellationSignal = cancellationSignal,
                )
        }

    private suspend fun loadPackageDetails(
        serial: String,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<String> {
        val appTypes =
            execute(
                serial,
                PACKAGE_DETAILS_ARGUMENTS,
                cancellationSignal,
                captureLimit = PACKAGE_DETAILS_CAPTURE_LIMIT,
            )
        return if (appTypes is StudioResult.Failure && appTypes.error.category == ErrorCategory.PROCESS_EXIT) {
            loadFallbackPackageDetails(serial, cancellationSignal)
        } else {
            appTypes
        }
    }

    private suspend fun loadFallbackPackageDetails(
        serial: String,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<String> {
        val packageList =
            execute(
                serial,
                PACKAGE_LIST_ARGUMENTS,
                cancellationSignal,
                captureLimit = PACKAGE_DETAILS_CAPTURE_LIMIT,
            )
        return if (packageList is StudioResult.Failure && packageList.error.category == ErrorCategory.PROCESS_EXIT) {
            execute(
                serial,
                PACKAGE_DETAILS_FALLBACK_ARGUMENTS,
                cancellationSignal,
                captureLimit = PACKAGE_DETAILS_CAPTURE_LIMIT,
            )
        } else {
            packageList
        }
    }

    companion object {
        private val PACKAGE_ARGUMENTS = listOf("cmd", "package", "list", "packages")
        private val PACKAGE_DETAILS_ARGUMENTS = listOf("sh", "-c", SIMPLEPERF_APP_TYPES_SCRIPT)
        private val PACKAGE_LIST_ARGUMENTS = listOf("cat", "/data/system/packages.list")
        private val PACKAGE_DETAILS_FALLBACK_ARGUMENTS = listOf("dumpsys", "package", "packages")
        private val PROCESS_ARGUMENTS = listOf("ps", "-A", "-o", "PID,PPID,USER,NAME")
        private const val PACKAGE_DETAILS_CAPTURE_LIMIT = 16 * 1024 * 1024
        private const val SIMPLEPERF_APP_TYPES_SCRIPT =
            "command -v simpleperf_app_runner >/dev/null 2>&1 || exit 127; " +
                "command -v xargs >/dev/null 2>&1 || exit 127; " +
                "command -v cut >/dev/null 2>&1 || exit 127; " +
                "cmd package list packages | cut -d: -f2 | " +
                "xargs -r -P 8 -n 1 sh -c '" +
                "app_type=\$(simpleperf_app_runner \"\$1\" --show-app-type 2>/dev/null); " +
                "case \"\$app_type\" in debuggable|profileable) echo \"\$1 \$app_type\";; esac' sh"
    }
}

private object AdbTargetParser {
    fun parsePackages(output: String): List<AndroidPackage> =
        output
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith(PACKAGE_PREFIX) }
            .map { it.removePrefix(PACKAGE_PREFIX).trim() }
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .map(::AndroidPackage)
            .toList()

    fun applyPackageCapabilities(
        packages: List<AndroidPackage>,
        packageDetails: String,
    ): List<AndroidPackage> {
        val capabilities = parsePackageCapabilities(packageDetails)
        return packages.map { packageInfo ->
            capabilities[packageInfo.packageName]?.let { capability ->
                packageInfo.copy(
                    debuggable = capability.debuggable,
                    profileableByShell = capability.profileableByShell,
                )
            } ?: packageInfo
        }
    }

    fun parseProcesses(output: String): List<AndroidProcess> =
        rows(output)
            .mapNotNull { columns ->
                val pid = columns.getOrNull(PID_COLUMN)?.toIntOrNull() ?: return@mapNotNull null
                val parentPid = columns.getOrNull(PPID_COLUMN)?.toIntOrNull() ?: return@mapNotNull null
                val user = columns.getOrNull(USER_COLUMN).orEmpty()
                val name = columns.getOrNull(PROCESS_NAME_COLUMN).orEmpty()
                if (user.isEmpty() || name.isEmpty()) return@mapNotNull null
                AndroidProcess(pid = pid, parentPid = parentPid, user = user, name = name)
            }.sortedBy(AndroidProcess::pid)
            .toList()

    fun parseThreads(output: String): List<AndroidThread> =
        rows(output)
            .mapNotNull { columns ->
                val pid = columns.getOrNull(PID_COLUMN)?.toIntOrNull() ?: return@mapNotNull null
                val tid = columns.getOrNull(TID_COLUMN)?.toIntOrNull() ?: return@mapNotNull null
                val name = columns.getOrNull(THREAD_NAME_COLUMN).orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                AndroidThread(pid = pid, tid = tid, name = name)
            }.sortedBy(AndroidThread::tid)
            .toList()

    private fun rows(output: String): Sequence<List<String>> =
        output
            .lineSequence()
            .drop(1)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.split(WHITESPACE) }

    private fun parsePackageCapabilities(output: String): Map<String, PackageCapability> {
        val result = parseSimpleperfAppTypes(output).toMutableMap()
        result.putAll(parsePackagesListCapabilities(output))
        PACKAGE_BLOCK.findAll(output).forEach { match ->
            val packageName = match.groupValues[PACKAGE_NAME_GROUP]
            val body = match.groupValues[PACKAGE_BODY_GROUP]
            result[packageName] =
                PackageCapability(
                    debuggable = DEBUGGABLE_FLAG.containsMatchIn(body),
                    profileableByShell = PROFILEABLE_BY_SHELL_FLAG.containsMatchIn(body),
                )
        }
        return result
    }

    private fun parseSimpleperfAppTypes(output: String): Map<String, PackageCapability> =
        output
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { line ->
                val columns = line.split(WHITESPACE)
                val packageName = columns.getOrNull(SIMPLEPERF_APP_TYPE_NAME_COLUMN) ?: return@mapNotNull null
                when (columns.getOrNull(SIMPLEPERF_APP_TYPE_COLUMN)) {
                    "debuggable" -> packageName to PackageCapability(debuggable = true, profileableByShell = false)
                    "profileable" -> packageName to PackageCapability(debuggable = false, profileableByShell = true)
                    else -> null
                }
            }.toMap()

    private fun parsePackagesListCapabilities(output: String): Map<String, PackageCapability> =
        output
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { line ->
                val columns = line.split(WHITESPACE)
                val packageName = columns.getOrNull(PACKAGES_LIST_NAME_COLUMN) ?: return@mapNotNull null
                columns.getOrNull(PACKAGES_LIST_UID_COLUMN)?.toIntOrNull() ?: return@mapNotNull null
                val debuggable =
                    columns
                        .getOrNull(PACKAGES_LIST_DEBUGGABLE_COLUMN)
                        .binaryFlag()
                        ?: return@mapNotNull null
                val profileable = columns.getOrNull(PACKAGES_LIST_PROFILEABLE_COLUMN).binaryFlag() ?: false
                packageName to PackageCapability(debuggable, profileable)
            }.toMap()

    private fun String?.binaryFlag(): Boolean? =
        when (this) {
            "0" -> false
            "1" -> true
            else -> null
        }

    private data class PackageCapability(
        val debuggable: Boolean,
        val profileableByShell: Boolean,
    )

    private const val PACKAGE_PREFIX = "package:"
    private const val PID_COLUMN = 0
    private const val PPID_COLUMN = 1
    private const val USER_COLUMN = 2
    private const val PROCESS_NAME_COLUMN = 3
    private const val TID_COLUMN = 1
    private const val THREAD_NAME_COLUMN = 2
    private const val PACKAGE_NAME_GROUP = 1
    private const val PACKAGE_BODY_GROUP = 2
    private const val PACKAGES_LIST_NAME_COLUMN = 0
    private const val PACKAGES_LIST_UID_COLUMN = 1
    private const val PACKAGES_LIST_DEBUGGABLE_COLUMN = 2
    private const val PACKAGES_LIST_PROFILEABLE_COLUMN = 6
    private const val SIMPLEPERF_APP_TYPE_NAME_COLUMN = 0
    private const val SIMPLEPERF_APP_TYPE_COLUMN = 1
    private val WHITESPACE = Regex("\\s+")
    private val PACKAGE_BLOCK =
        Regex("(?ms)^[ \\t]*Package \\[([^]]+)] \\([^\\n]*\\):(.*?)(?=^[ \\t]*Package \\[|\\z)")
    private val DEBUGGABLE_FLAG = Regex("\\bDEBUGGABLE\\b")
    private val PROFILEABLE_BY_SHELL_FLAG = Regex("\\b(?:PRIVATE_FLAG_)?PROFILEABLE_BY_SHELL\\b")
}
