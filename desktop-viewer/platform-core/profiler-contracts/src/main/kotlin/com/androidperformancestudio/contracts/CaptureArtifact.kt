package com.androidperformancestudio.contracts

import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION: Int = 1

@Serializable
@JvmInline
value class ArtifactId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "artifact id must not be blank" }
    }
}

@Serializable
@JvmInline
value class ArtifactKind(
    val value: String,
) {
    init {
        require(LOWERCASE_ID.matches(value)) { "artifact kind must be a lowercase stable id" }
    }
}

@Serializable
@JvmInline
value class ArtifactLocation(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "artifact location must not be blank" }
    }
}

@Serializable
@JvmInline
value class Sha256(
    val value: String,
) {
    init {
        require(SHA_256.matches(value)) { "sha256 must contain 64 lowercase hexadecimal characters" }
    }
}

@Serializable
data class ArtifactFormat(
    val name: String,
    val version: String? = null,
) {
    init {
        require(name.isNotBlank()) { "artifact format name must not be blank" }
        require(version == null || version.isNotBlank()) { "artifact format version must not be blank" }
    }
}

@Serializable
sealed interface ArtifactProducer {
    @Serializable
    @SerialName("known")
    data class Known(
        val name: String,
        val version: String? = null,
        val sha256: Sha256? = null,
    ) : ArtifactProducer {
        init {
            require(name.isNotBlank()) { "producer name must not be blank" }
            require(version == null || version.isNotBlank()) { "producer version must not be blank" }
        }
    }

    @Serializable
    @SerialName("unknown")
    data object Unknown : ArtifactProducer
}

@Serializable
enum class ArtifactAcquisitionKind {
    CAPTURE,
    IMPORT,
}

@Serializable
data class ArtifactAcquisition(
    val kind: ArtifactAcquisitionKind,
    val application: String,
    val applicationVersion: String? = null,
    val performedAtEpochMillis: Long,
) {
    init {
        require(application.isNotBlank()) { "acquisition application must not be blank" }
        require(applicationVersion == null || applicationVersion.isNotBlank()) {
            "acquisition application version must not be blank"
        }
        require(performedAtEpochMillis >= 0) { "acquisition time must not be negative" }
    }
}

@Serializable
data class ArtifactProvenance(
    val producer: ArtifactProducer = ArtifactProducer.Unknown,
    val acquisition: ArtifactAcquisition,
)

@Serializable
@JvmInline
value class ClockDomain(
    val value: String,
) {
    init {
        require(CLOCK_DOMAIN.matches(value)) { "clock domain must be a stable identifier" }
    }
}

@Serializable
data class ArtifactTimePoint(
    val clockDomain: ClockDomain,
    val timestampNanos: Long,
) {
    init {
        require(timestampNanos >= 0) { "timestamp must not be negative" }
    }
}

@Serializable
data class ClockMapping(
    val source: ClockDomain,
    val target: ClockDomain,
    val sourceReferenceNanos: Long,
    val targetReferenceNanos: Long,
    val errorBoundNanos: Long,
    val validFromSourceNanos: Long? = null,
    val validToSourceNanos: Long? = null,
) {
    init {
        require(source != target) { "clock mapping must connect different domains" }
        require(sourceReferenceNanos >= 0 && targetReferenceNanos >= 0) {
            "clock mapping reference timestamps must not be negative"
        }
        require(errorBoundNanos >= 0) { "clock mapping error bound must not be negative" }
        require(validFromSourceNanos == null || validFromSourceNanos >= 0) {
            "clock mapping validity start must not be negative"
        }
        require(validToSourceNanos == null || validToSourceNanos >= 0) {
            "clock mapping validity end must not be negative"
        }
        require(validFromSourceNanos == null || validToSourceNanos == null || validFromSourceNanos <= validToSourceNanos) {
            "clock mapping validity start must not follow its end"
        }
        require(validFromSourceNanos == null || sourceReferenceNanos >= validFromSourceNanos) {
            "clock mapping reference must not precede its validity interval"
        }
        require(validToSourceNanos == null || sourceReferenceNanos <= validToSourceNanos) {
            "clock mapping reference must not follow its validity interval"
        }
    }
}

@Serializable
@JvmInline
value class DeviceLocalId private constructor(
    val value: String,
) {
    init {
        require(SHA_256.matches(value)) { "device local id must be a salted SHA-256 value" }
    }

    companion object {
        fun fromRawSerial(
            rawSerial: String,
            applicationSalt: ByteArray,
        ): DeviceLocalId {
            require(rawSerial.isNotBlank()) { "device raw serial must not be blank" }
            require(applicationSalt.size >= 16) { "device id application salt must contain at least 16 bytes" }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(applicationSalt)
            return DeviceLocalId(HexFormat.of().formatHex(digest.digest(rawSerial.toByteArray())))
        }
    }
}

@Serializable
data class DeviceTargetIdentity(
    val localId: DeviceLocalId,
    val manufacturer: String? = null,
    val model: String? = null,
    val buildFingerprint: String? = null,
    val rawSerial: String? = null,
) {
    init {
        require(manufacturer == null || manufacturer.isNotBlank()) { "device manufacturer must not be blank" }
        require(model == null || model.isNotBlank()) { "device model must not be blank" }
        require(buildFingerprint == null || buildFingerprint.isNotBlank()) { "device build fingerprint must not be blank" }
        require(rawSerial == null || rawSerial.isNotBlank()) { "device raw serial must not be blank" }
    }
}

enum class ProcessIdentityStrength {
    STRONG,
    WEAK,
}

@Serializable
data class ProcessIdentity(
    val pid: Int,
    val deviceLocalId: DeviceLocalId? = null,
    val processName: String? = null,
    val packageName: String? = null,
    val startedAt: ArtifactTimePoint? = null,
    val strength: ProcessIdentityStrength = processIdentityStrength(deviceLocalId, startedAt),
) {
    init {
        require(pid > 0) { "process id must be positive" }
        require(processName == null || processName.isNotBlank()) { "process name must not be blank" }
        require(packageName == null || packageName.isNotBlank()) { "package name must not be blank" }
        require(strength == processIdentityStrength(deviceLocalId, startedAt)) {
            "process identity strength must match its device and start marker"
        }
    }
}

@Serializable
@JvmInline
value class CapabilityId(
    val value: String,
) {
    init {
        require(CAPABILITY_ID.matches(value)) {
            "capability id must be a lowercase namespaced id"
        }
    }
}

@Serializable
enum class ArtifactCompleteness {
    COMPLETE,
    PARTIAL,
    UNKNOWN,
}

@Serializable
data class ArtifactLimitation(
    val capability: CapabilityId? = null,
    val code: String,
    val message: String,
) {
    init {
        require(code.isNotBlank()) { "limitation code must not be blank" }
        require(message.isNotBlank()) { "limitation message must not be blank" }
    }
}

@Serializable
enum class PrivacyRedaction {
    DEVICE_SERIAL,
}

@Serializable
data class ArtifactPrivacy(
    val containsSensitiveIdentity: Boolean = false,
    val redactions: Set<PrivacyRedaction> = setOf(PrivacyRedaction.DEVICE_SERIAL),
) {
    init {
        require(!containsSensitiveIdentity || PrivacyRedaction.DEVICE_SERIAL !in redactions) {
            "device serial cannot be both included and redacted"
        }
    }
}

@Serializable
data class CaptureArtifact(
    val contractVersion: Int = CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION,
    val id: ArtifactId,
    val kind: ArtifactKind,
    val location: ArtifactLocation,
    val sha256: Sha256,
    val format: ArtifactFormat? = null,
    val provenance: ArtifactProvenance,
    val capturedAt: ArtifactTimePoint? = null,
    val device: DeviceTargetIdentity? = null,
    val process: ProcessIdentity? = null,
    val clockDomains: Set<ClockDomain> = emptySet(),
    val clockMappings: Set<ClockMapping> = emptySet(),
    val requestedCapabilities: Set<CapabilityId>? = null,
    val availableCapabilities: Set<CapabilityId> = emptySet(),
    val completeness: ArtifactCompleteness = ArtifactCompleteness.UNKNOWN,
    val limitations: List<ArtifactLimitation> = emptyList(),
    val warnings: List<String> = emptyList(),
    val privacy: ArtifactPrivacy = ArtifactPrivacy(),
) {
    init {
        require(contractVersion == CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION) {
            "unsupported capture artifact contract version: $contractVersion"
        }
        require(warnings.none(String::isBlank)) { "artifact warnings must not be blank" }
        require(capturedAt == null || capturedAt.clockDomain in clockDomains) {
            "capture timestamp clock domain must be declared by the artifact"
        }
        require(process?.startedAt == null || process.startedAt.clockDomain in clockDomains) {
            "process start clock domain must be declared by the artifact"
        }
        require(process?.deviceLocalId == null || process.deviceLocalId == device?.localId) {
            "process identity must reference the artifact device"
        }
        require(device?.rawSerial == null || privacy.containsSensitiveIdentity) {
            "raw device serial requires explicit sensitive identity preservation"
        }
        require(device?.rawSerial == null || PrivacyRedaction.DEVICE_SERIAL !in privacy.redactions) {
            "raw device serial cannot be present when device serial is redacted"
        }
        require(clockMappings.all { it.source in clockDomains && it.target in clockDomains }) {
            "clock mappings must only reference declared clock domains"
        }
        validateCompleteness()
    }

    private fun validateCompleteness() {
        when (completeness) {
            ArtifactCompleteness.COMPLETE ->
                require(requestedCapabilities != null && availableCapabilities.containsAll(requestedCapabilities)) {
                    "complete artifacts must provide every requested capability"
                }
            ArtifactCompleteness.PARTIAL -> {
                require(requestedCapabilities != null) { "partial artifacts must declare requested capabilities" }
                val missing = requestedCapabilities - availableCapabilities
                require(missing.isNotEmpty()) { "partial artifacts must be missing a requested capability" }
                require(missing.all { capability -> limitations.any { it.capability == capability } }) {
                    "partial artifacts must explain every missing capability"
                }
            }
            ArtifactCompleteness.UNKNOWN ->
                require(requestedCapabilities == null) {
                    "unknown completeness is only valid when requested capabilities are unknown"
                }
        }
    }
}

object CaptureArtifactJson {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            classDiscriminator = "producerType"
        }

    fun encode(artifact: CaptureArtifact): String = json.encodeToString(artifact)

    fun decode(value: String): CaptureArtifact = json.decodeFromString(value)
}

private val SHA_256 = Regex("[0-9a-f]{64}")
private val LOWERCASE_ID = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
private val CAPABILITY_ID = Regex("[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)+")
private val CLOCK_DOMAIN = Regex("[A-Za-z][A-Za-z0-9._-]*")

private fun processIdentityStrength(
    deviceLocalId: DeviceLocalId?,
    startedAt: ArtifactTimePoint?,
): ProcessIdentityStrength =
    if (deviceLocalId == null || startedAt == null) ProcessIdentityStrength.WEAK else ProcessIdentityStrength.STRONG
