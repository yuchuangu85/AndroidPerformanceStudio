package com.androidperformancestudio.ui

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Compose string resources using the application's explicit language selection.
 *
 * Profiler workspaces can receive an explicit locale from the unified application settings, which
 * can intentionally differ from the operating-system locale. Compose Resources' default
 * non-composable API follows the system locale, so resource lookup must use an explicit
 * environment. Loaded templates are cached by resource identity and locale; no global locale
 * is mutated and subsequent reads do not perform resource I/O.
 */
public fun localizedStringResource(
    resource: StringResource,
    language: UiLanguage = UiLanguage.ENGLISH,
    vararg formatArgs: Any?,
): String = localizedStringResource(resource, language.locale, *formatArgs)

/** Resolves a resource for an arbitrary requested locale, falling back to the base resource when unavailable. */
public fun localizedStringResource(
    resource: StringResource,
    locale: Locale,
    vararg formatArgs: Any?,
): String {
    val template =
        localizedStringTemplates.computeIfAbsent(ResourceCacheKey(resource, locale)) {
            runBlocking {
                getString(ResourceEnvironmentFactory.forLanguage(locale), resource)
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
    private val locale: Locale,
) {
    override fun equals(other: Any?): Boolean {
        return other is ResourceCacheKey && resource === other.resource && locale == other.locale
    }

    override fun hashCode(): Int = 31 * System.identityHashCode(resource) + locale.hashCode()
}

private val localizedStringTemplates = ConcurrentHashMap<ResourceCacheKey, String>()
