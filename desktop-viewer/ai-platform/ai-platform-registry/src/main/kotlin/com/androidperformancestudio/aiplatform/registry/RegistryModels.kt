package com.androidperformancestudio.aiplatform.registry

import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionManifest
import com.androidperformancestudio.aiplatform.model.ExtensionOrigin
import com.androidperformancestudio.aiplatform.model.PLATFORM_SCHEMA_VERSION
import com.androidperformancestudio.aiplatform.model.TrustStatus
import com.androidperformancestudio.aiplatform.model.requireStableId
import kotlinx.serialization.Serializable
import java.nio.file.Path

public data class ExtensionKey(
    val kind: ExtensionKind,
    val id: String,
    val version: String,
    val origin: ExtensionOrigin,
) {
    init {
        requireStableId(id)
        registryRequire(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && version.length <= 32, "E_VERSION")
    }
}

public data class RegistryEntry(
    val key: ExtensionKey,
    val manifest: ExtensionManifest,
    val directory: Path,
    val contentHash: String,
    val trustStatus: TrustStatus,
    val enabled: Boolean,
) {
    public val usable: Boolean get() = enabled && trustStatus == TrustStatus.TRUSTED
}

public data class RegistryIssue(
    val code: String,
)

public data class RegistryScan(
    val entries: List<RegistryEntry>,
    val issues: List<RegistryIssue>,
)

/** Written by the host only; never accepted from an imported bundle or its manifest. */
@Serializable
internal data class InstallationState(
    val schemaVersion: Int = PLATFORM_SCHEMA_VERSION,
    val id: String,
    val version: String,
    val kind: ExtensionKind,
    val origin: ExtensionOrigin,
    val contentHash: String,
    val trustStatus: TrustStatus,
    val enabled: Boolean,
) {
    init {
        registryRequire(schemaVersion == PLATFORM_SCHEMA_VERSION, "E_SCHEMA_VERSION")
        requireStableId(id)
        registryRequire(contentHash.matches(Regex("[a-f0-9]{64}")), "E_CONTENT_HASH")
        registryRequire(!enabled || trustStatus == TrustStatus.TRUSTED, "E_TRUST_STATE")
    }
}

/** Hard caps apply before parsing/allocating and are independent of package-declared run budgets. */
public data class RegistryLimits(
    val maxManifestBytes: Int = 65_536,
    val maxFileBytes: Int = 262_144,
    val maxScriptBytes: Int = 65_536,
    val maxPackageBytes: Int = 1_048_576,
    val maxTreeEntries: Int = 128,
    val maxInstalledVersions: Int = 1024,
) {
    init {
        require(maxManifestBytes in 1..65_536)
        require(maxFileBytes in 1..1_048_576 && maxScriptBytes in 1..1_048_576)
        require(maxPackageBytes in 1..4_194_304 && maxTreeEntries in 1..1024)
        require(maxInstalledVersions in 1..4096)
    }
}

@Serializable
internal data class SelectedVersion(
    val kind: ExtensionKind,
    val id: String,
    val version: String,
    val origin: ExtensionOrigin,
    val contentHash: String,
)
