package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Path

data class AndroidPackage(
    val packageName: String,
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
            is StudioResult.Success -> refreshProcesses(serial, packages.value, cancellationSignal)
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
    ): StudioResult<String> {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = listOf("-s", serial, "shell") + shellArguments,
            )
        return when (val result = processInvocation(request, cancellationSignal)) {
            is ProcessRunResult.Completed -> StudioResult.Success(result.output.stdout.text)
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
    }

    private suspend fun refreshProcesses(
        serial: String,
        packageOutput: String,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<AdbTargetSnapshot> =
        when (val processes = execute(serial, PROCESS_ARGUMENTS, cancellationSignal)) {
            is StudioResult.Failure -> processes
            is StudioResult.Success ->
                StudioResult.Success(
                    AdbTargetSnapshot(
                        packages = AdbTargetParser.parsePackages(packageOutput),
                        processes = AdbTargetParser.parseProcesses(processes.value),
                    ),
                )
        }

    companion object {
        private val PACKAGE_ARGUMENTS = listOf("cmd", "package", "list", "packages")
        private val PROCESS_ARGUMENTS = listOf("ps", "-A", "-o", "PID,PPID,USER,NAME")
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

    private const val PACKAGE_PREFIX = "package:"
    private const val PID_COLUMN = 0
    private const val PPID_COLUMN = 1
    private const val USER_COLUMN = 2
    private const val PROCESS_NAME_COLUMN = 3
    private const val TID_COLUMN = 1
    private const val THREAD_NAME_COLUMN = 2
    private val WHITESPACE = Regex("\\s+")
}
