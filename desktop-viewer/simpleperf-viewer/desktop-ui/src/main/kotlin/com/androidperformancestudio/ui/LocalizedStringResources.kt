package com.androidperformancestudio.ui

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Compose string resources using the application's explicit language selection.
 *
 * Profiler workspaces receive a `chinese` flag from the unified application settings, which
 * can intentionally differ from the operating-system locale. Compose Resources' default
 * non-composable API follows the system locale, so resource lookup must use an explicit
 * environment. Loaded templates are cached by resource identity and language; no global locale
 * is mutated and subsequent reads do not perform resource I/O.
 */
public fun localizedStringResource(
    resource: StringResource,
    chinese: Boolean,
    vararg formatArgs: Any?,
): String {
    val template = localizedStringTemplates.computeIfAbsent(ResourceCacheKey(resource, chinese)) {
        runBlocking {
            getString(ResourceEnvironmentFactory.forLanguage(chinese), resource)
        }
    }
    return if (formatArgs.isEmpty()) {
        template
    } else {
        String.format(Locale.ROOT, template, *formatArgs)
    }
}

private class ResourceCacheKey(
    private val resource: StringResource,
    private val chinese: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is ResourceCacheKey && resource === other.resource && chinese == other.chinese

    override fun hashCode(): Int = 31 * System.identityHashCode(resource) + chinese.hashCode()
}

private val localizedStringTemplates = ConcurrentHashMap<ResourceCacheKey, String>()
