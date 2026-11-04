package com.androidperformancestudio.aiplatform.script

import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import com.androidperformancestudio.aiplatform.registry.ExtensionKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

public class ScriptException(
    public val code: String,
) : IllegalArgumentException(code)

@Serializable
public data class ScriptInput(
    val schema: String,
    val evidenceIds: Set<String>,
    val candidateIds: Set<String>,
    val payload: JsonObject,
) {
    init {
        require(schema.length in 1..128)
        require(evidenceIds.size <= 100 && candidateIds.size <= 100)
        require((evidenceIds + candidateIds).all { it.length in 1..256 })
    }
}

public data class ScriptRunRequest(
    val key: ExtensionKey,
    val scriptPath: String,
    val input: ScriptInput,
    val grantedPermissions: Set<Permission>,
    val budget: ResourceBudget = ResourceBudget(),
)

/** Only validated structured output is returned; stderr is metadata-only, never provider/UI text. */
public data class ScriptRunResult(
    val output: JsonObject,
    val audit: ScriptAudit,
)

@Serializable
public data class ScriptAudit(
    val extensionId: String,
    val extensionVersion: String,
    val inputHash: String,
    val outputHash: String,
    val stderrHash: String,
    val stdoutBytes: Int,
    val stderrBytes: Int,
    val runtime: ScriptRuntime,
    val runtimeVersion: String,
    val backend: String,
    val exitCode: Int,
)

public sealed interface SandboxAvailability {
    public data class Available(
        val runtimeVersion: String,
        val backend: String,
    ) : SandboxAvailability

    public data class Unavailable(
        val code: String,
    ) : SandboxAvailability
}

/** Installed tools only, not executable text supplied by a model/extension. */
public data class SandboxToolchain(
    val os: String = System.getProperty("os.name"),
    val python: Path? = null,
    val node: Path? = null,
    val bubblewrap: Path = Path.of("/usr/bin/bwrap"),
    val prlimit: Path = Path.of("/usr/bin/prlimit"),
    val sandboxExec: Path = Path.of("/usr/bin/sandbox-exec"),
    val linuxLauncher: Path = Path.of("/usr/libexec/aps/aps-script-sandbox"),
)

internal fun scriptRequire(
    condition: Boolean,
    code: String,
) {
    if (!condition) throw ScriptException(code)
}
