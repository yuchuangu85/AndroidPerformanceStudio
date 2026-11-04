package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path

/** System property that pins the Compose inspection agent bundle. */
internal const val COMPOSE_AGENT_BUNDLE_PROPERTY = "agentperf.compose.agent.bundle"

/** Environment fallback for launches where passing a system property is awkward. */
internal const val COMPOSE_AGENT_BUNDLE_ENV = "APC_COMPOSE_AGENT_BUNDLE"

/** Resource root supplied by Compose Desktop's packaged launcher. */
private const val COMPOSE_APPLICATION_RESOURCES_PROPERTY = "compose.application.resources.dir"

/**
 * Development bundle locations searched under the working directory after packaged resources.
 *
 * The first entry supports a bundle next to a development launch; the second is the layout
 * produced by :layout-inspector:compose-agent-build when run from desktop-viewer/desktop-app.
 * Packaged launches use the launcher's resource root instead, independent of the working directory.
 */
internal val COMPOSE_AGENT_BUNDLE_CANDIDATES = listOf(
    "compose-agent-bundle",
    "../layout-inspector/compose-agent-build/build/compose-agent-bundle",
)

/**
 * Resolves the agent bundle root. An explicit configuration (system property, then environment)
 * always wins and is resolved against the working directory. Otherwise packaged resources take
 * priority over development candidates. If nothing exists, return the first expected location so
 * the failure message points at the missing packaged bundle rather than the launcher's working dir.
 */
internal fun resolveComposeAgentBundle(
    configured: String? =
        System.getProperty(COMPOSE_AGENT_BUNDLE_PROPERTY) ?: System.getenv(COMPOSE_AGENT_BUNDLE_ENV),
    workingDirectory: Path = Path.of("").toAbsolutePath(),
    applicationResourcesPath: Path? =
        System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of),
    isDirectory: (Path) -> Boolean = { Files.isDirectory(it) },
): Path {
    configured?.takeIf(String::isNotBlank)?.let { return workingDirectory.resolve(it).normalize() }
    val candidates =
        listOfNotNull(applicationResourcesPath?.resolve("compose-agent-bundle")?.normalize()) +
            COMPOSE_AGENT_BUNDLE_CANDIDATES.map { workingDirectory.resolve(it).normalize() }
    return candidates.firstOrNull(isDirectory) ?: candidates.first()
}
