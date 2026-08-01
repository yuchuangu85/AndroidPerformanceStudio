package com.androidperformancestudio.desktop

private const val VERSION_SYSTEM_PROPERTY = "agentperf.version"
private const val DEVELOPMENT_VERSION = "development"

internal fun resolveApplicationVersion(
    systemProperty: String? = System.getProperty(VERSION_SYSTEM_PROPERTY),
    packageVersion: String? = ApplicationVersion::class.java.`package`?.implementationVersion,
): String =
    systemProperty?.trim()?.takeIf(String::isNotEmpty)
        ?: packageVersion?.trim()?.takeIf(String::isNotEmpty)
        ?: DEVELOPMENT_VERSION

internal object ApplicationVersion {
    fun current(): String = resolveApplicationVersion()
}
