package com.androidperformancestudio.compose.inspection.host

internal data class ComposeAgentAttachDiagnostics(
    val summary: String,
    val logcat: String,
    val terminalBootstrapFailure: Boolean,
)

internal fun composeAgentAttachDiagnostics(
    output: String,
    token: InspectorSessionToken,
): ComposeAgentAttachDiagnostics? {
    val entries = mutableListOf<AgentLogEntry>()
    var followsAgentError = false
    for (line in output.lineSequence()) {
        val entry = agentLogEntry(line, token, followsAgentError)
        if (entry != null) entries += entry
        followsAgentError = entry != null
    }
    if (entries.isEmpty()) return null
    val cause =
        entries.firstOrNull(AgentLogEntry::terminal)
            ?: entries.firstOrNull { !it.frameworkAttempt }
            ?: entries.first()
    val text = entries.joinToString("\n", transform = AgentLogEntry::line)
    return ComposeAgentAttachDiagnostics(
        cause.message.take(MAX_AGENT_SUMMARY_CHARS),
        boundedAgentLogcat(text),
        entries.any(AgentLogEntry::terminal),
    )
}

private fun agentLogEntry(
    line: String,
    token: InspectorSessionToken,
    followsAgentError: Boolean,
): AgentLogEntry? {
    val match = THREADTIME_LOG_LINE.matchEntire(line) ?: BRIEF_LOG_LINE.matchEntire(line)
    val severity = match?.groups?.get("severity")?.value
    val tag =
        match
            ?.groups
            ?.get("tag")
            ?.value
            ?.trim()
            .orEmpty()
    val message = match?.groups?.get("message")?.value ?: line
    val inspector = tag.startsWith("studio.ui-inspector")
    val frameworkAttempt = tag == "ActivityThread" && message.contains("Attaching agent", ignoreCase = true)
    val agentRuntime = AGENT_FAILURE_MESSAGES.any { message.contains(it, ignoreCase = true) }
    val agentLibrary = AGENT_LIBRARY_NAMES.any(message::contains)
    val continuation = followsAgentError && STACK_TRACE_LINE.containsMatchIn(message.trimStart())
    val related = inspector || frameworkAttempt || agentRuntime || agentLibrary || continuation
    return if (related) {
        AgentLogEntry(
            redactAgentToken(line, token),
            redactAgentToken(message, token),
            inspector && severity in listOf("E", "F"),
            frameworkAttempt,
        )
    } else {
        null
    }
}

private fun boundedAgentLogcat(text: String): String =
    if (text.length > MAX_AGENT_LOGCAT_CHARS) {
        // Keep a useful tail as well: the classloader description can precede the actual cause.
        text.take(MAX_AGENT_LOGCAT_CHARS / 2) + "\n[Device logcat truncated]\n" +
            text.takeLast(MAX_AGENT_LOGCAT_CHARS / 2)
    } else {
        text
    }

private data class AgentLogEntry(
    val line: String,
    val message: String,
    val terminal: Boolean,
    val frameworkAttempt: Boolean,
)

internal fun redactAgentToken(
    text: String,
    token: InspectorSessionToken,
): String =
    AGENT_OPTION_TOKEN.replace(
        text.replace(token.value, "[redacted]").replace(token.value.take(32), "[redacted]"),
        ";[redacted]",
    )

private val THREADTIME_LOG_LINE =
    Regex(
        """^\S+\s+\S+\s+\d+\s+\d+\s+(?<severity>[WEF])\s+(?<tag>[^:]+):\s?(?<message>.*)$""",
    )
private val BRIEF_LOG_LINE = Regex("""^(?<severity>[WEF])/(?<tag>[^(:]+)(?:\(\s*\d+\))?\s*:\s?(?<message>.*)$""")
private val STACK_TRACE_LINE = Regex("""^(?:at\s|Caused by:|Suppressed:|[\w.$]*(?:Exception|Error)(?::|$))""")
private val AGENT_OPTION_TOKEN = Regex(""";[0-9a-fA-F]{64}(?![0-9a-fA-F])""")
private val AGENT_FAILURE_MESSAGES = listOf("Agent attach failed", "Failed to attach agent", "Could not attach agent")
private val AGENT_LIBRARY_NAMES =
    listOf(
        "lib_ui_inspector_agent",
        "lib_ui_inspector_service",
        "lib_ui_inspector_payload",
        "libopenjdkjvmti",
    )
private const val MAX_AGENT_LOGCAT_CHARS = 16_384
private const val MAX_AGENT_SUMMARY_CHARS = 320
