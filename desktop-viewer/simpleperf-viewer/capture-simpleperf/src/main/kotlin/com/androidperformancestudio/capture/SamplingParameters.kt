package com.androidperformancestudio.capture

enum class CallGraphMode {
    DWARF,
    FRAME_POINTER,
    NONE,
}

enum class EventScope {
    USER,
    KERNEL,
    BOTH,
}

sealed interface SamplingRate {
    data class Frequency(
        val hertz: Int,
    ) : SamplingRate {
        init {
            require(hertz > 0) { "frequency must be positive" }
        }
    }

    data class Period(
        val events: Long,
    ) : SamplingRate {
        init {
            require(events > 0) { "period must be positive" }
        }
    }
}

sealed interface SimpleperfTarget {
    data class App(
        val packageName: String,
    ) : SimpleperfTarget {
        init {
            requireCommandToken(packageName, "packageName")
        }
    }

    data class Process(
        val pid: Int,
        val appPackage: String? = null,
    ) : SimpleperfTarget {
        init {
            require(pid > 0) { "pid must be positive" }
            appPackage?.let { requireCommandToken(it, "appPackage") }
        }
    }

    data class ProcessName(
        val name: String,
    ) : SimpleperfTarget {
        init {
            requireCommandToken(name, "processName")
        }
    }

    data class Thread(
        val tid: Int,
        val appPackage: String? = null,
    ) : SimpleperfTarget {
        init {
            require(tid > 0) { "tid must be positive" }
            appPackage?.let { requireCommandToken(it, "appPackage") }
        }
    }

    data object SystemWide : SimpleperfTarget
}

data class SamplingParameters(
    val target: SimpleperfTarget,
    val event: String = "cpu-clock",
    val rate: SamplingRate = SamplingRate.Frequency(DEFAULT_FREQUENCY_HERTZ),
    val durationSeconds: Double? = DEFAULT_DURATION_SECONDS,
    val callGraph: CallGraphMode = CallGraphMode.DWARF,
    val scope: EventScope = EventScope.BOTH,
    val outputPath: String = DEFAULT_OUTPUT_PATH,
) {
    init {
        requireCommandToken(event, "event")
        require(durationSeconds == null || durationSeconds > 0.0) { "duration must be positive" }
        requireCommandToken(outputPath, "outputPath")
    }

    companion object {
        const val DEFAULT_FREQUENCY_HERTZ = 1000
        const val DEFAULT_DURATION_SECONDS = 10.0
        const val DEFAULT_OUTPUT_PATH = "/data/local/tmp/aps/perf.data"
    }
}

data class SimpleperfRecordCommand(
    val serial: String,
    val simpleperfPath: String,
    val parameters: SamplingParameters,
) {
    init {
        requireCommandToken(serial, "serial")
        requireCommandToken(simpleperfPath, "simpleperfPath")
    }

    val shellArguments: List<String> =
        buildList {
            addAll(listOf(simpleperfPath, "record"))
            addAll(parameters.eventArguments())
            addAll(parameters.rate.arguments())
            parameters.durationSeconds?.let { addAll(listOf("--duration", it.toCommandNumber())) }
            addAll(parameters.callGraph.arguments())
            addAll(parameters.target.arguments())
            addAll(listOf("-o", parameters.outputPath))
        }

    val adbArguments: List<String> = listOf("-s", serial, "shell") + shellArguments

    fun preview(adbExecutable: String = "adb"): String = commandOf(adbExecutable, adbArguments)
}

fun commandOf(
    executable: String,
    arguments: List<String>,
): String =
    (listOf(executable) + arguments)
        .joinToString(" ") { token -> token.shellQuote() }

private fun SamplingParameters.eventArguments(): List<String> {
    val modifier =
        when (scope) {
            EventScope.USER -> ":u"
            EventScope.KERNEL -> ":k"
            EventScope.BOTH -> ""
        }
    return listOf("-e", event + modifier)
}

private fun SamplingRate.arguments(): List<String> =
    when (this) {
        is SamplingRate.Frequency -> listOf("-f", hertz.toString())
        is SamplingRate.Period -> listOf("-c", events.toString())
    }

private fun CallGraphMode.arguments(): List<String> =
    when (this) {
        CallGraphMode.DWARF -> listOf("-g")
        CallGraphMode.FRAME_POINTER -> listOf("--call-graph", "fp")
        CallGraphMode.NONE -> emptyList()
    }

private fun SimpleperfTarget.arguments(): List<String> =
    when (this) {
        is SimpleperfTarget.App -> listOf("--app", packageName)
        is SimpleperfTarget.Process -> appArguments(appPackage) + listOf("-p", pid.toString())
        is SimpleperfTarget.ProcessName -> listOf("-p", name)
        is SimpleperfTarget.Thread -> appArguments(appPackage) + listOf("-t", tid.toString())
        SimpleperfTarget.SystemWide -> listOf("-a")
    }

internal fun SimpleperfTarget.isAppScoped(): Boolean =
    when (this) {
        is SimpleperfTarget.App -> true
        is SimpleperfTarget.Process -> appPackage != null
        is SimpleperfTarget.Thread -> appPackage != null
        is SimpleperfTarget.ProcessName,
        SimpleperfTarget.SystemWide,
        -> false
    }

private fun appArguments(appPackage: String?): List<String> = appPackage?.let { listOf("--app", it) }.orEmpty()

private fun Double.toCommandNumber(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun String.shellQuote(): String =
    if (matches(SAFE_SHELL_TOKEN)) {
        this
    } else {
        "'${replace("'", "'\\''")}'"
    }

private fun requireCommandToken(
    value: String,
    name: String,
) {
    require(value.isNotBlank() && value.none(Char::isWhitespace)) { "$name must be a non-blank command token" }
}

private val SAFE_SHELL_TOKEN = Regex("[A-Za-z0-9_@%+=:,./-]+")
