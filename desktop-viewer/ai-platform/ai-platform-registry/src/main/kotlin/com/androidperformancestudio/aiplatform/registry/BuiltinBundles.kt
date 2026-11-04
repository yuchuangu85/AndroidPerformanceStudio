package com.androidperformancestudio.aiplatform.registry

import com.androidperformancestudio.aiplatform.model.PlatformJson

internal object BuiltinBundles {
    val reservedIds =
        setOf(
            "aps.main",
            "aps.chat",
            "aps.simpleperf",
            "aps.source-evidence",
            "aps.report",
            "aps.evidence-summary",
            "aps.source-candidates",
            "aps.report-artifact",
        )

    fun load(limits: RegistryLimits): List<VerifiedBundle> =
        reservedIds.map { id ->
            val base = "/builtin/$id/"
            val manifestBytes = resource(base + "manifest.json", limits.maxManifestBytes)
            val manifest = PlatformJson.decodeManifest(manifestBytes)
            registryRequire(manifest.id == id, "E_BUILTIN_CONTENT")
            var totalBytes = manifestBytes.size.toLong()
            val files =
                manifest.files.toSortedMap().mapValues { (path, hash) ->
                    val maxBytes = if (path in manifest.scripts) limits.maxScriptBytes else limits.maxFileBytes
                    resource(base + path, maxBytes).also { bytes ->
                        registryRequire(sha256(bytes) == hash, "E_BUILTIN_CONTENT")
                        totalBytes += bytes.size
                        registryRequire(totalBytes <= limits.maxPackageBytes, "E_PACKAGE_LIMIT")
                    }
                }
            VerifiedBundle(manifest, manifestBytes, files, packageHash(manifestBytes, files))
        }

    private fun resource(
        path: String,
        limit: Int,
    ): ByteArray {
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "E_BUILTIN_RESOURCE" }
        return stream.use { it.readNBytes(limit + 1) }.also { registryRequire(it.size <= limit, "E_FILE_LIMIT") }
    }
}
