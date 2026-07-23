package com.androidperformancestudio.adb

import com.androidperformancestudio.perfetto.model.ErrorCategory
import com.androidperformancestudio.perfetto.model.StudioError
import com.androidperformancestudio.perfetto.model.StudioResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

class AdbLocator(
    private val configuredPath: String? = System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/adb" },
) {
    fun locate(): StudioResult<AdbBinary> {
        if (configuredPath != null) {
            val path = Paths.get(configuredPath)
            if (path.exists() && path.isExecutable()) {
                return StudioResult.Success(AdbBinary(path))
            }
        }

        // Check PATH
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(Path.of(":").toString())) {
            val candidate = Paths.get(dir, "adb")
            if (candidate.exists() && candidate.isExecutable()) {
                return StudioResult.Success(AdbBinary(candidate))
            }
        }

        return StudioResult.Failure(
            StudioError(
                category = ErrorCategory.CONFIGURATION,
                code = "ADB_NOT_FOUND",
                message = "adb not found. Set ANDROID_HOME or add platform-tools to PATH.",
            )
        )
    }
}

data class AdbBinary(val path: Path)
