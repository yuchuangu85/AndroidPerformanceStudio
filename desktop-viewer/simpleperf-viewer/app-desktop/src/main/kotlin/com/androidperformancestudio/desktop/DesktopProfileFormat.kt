package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path

internal enum class DesktopProfileFormat {
    SESSION_DIRECTORY,
    SESSION_PACKAGE,
    PERF_DATA,
    SIMPLEPERF_PROTOBUF,
    GECKO_PROFILE_JSON_GZIP,
    UNSUPPORTED,
}

internal fun detectDesktopProfileFormat(path: Path): DesktopProfileFormat {
    if (Files.isDirectory(path)) return DesktopProfileFormat.SESSION_DIRECTORY
    val name = path.fileName.toString().lowercase()
    return when {
        name.endsWith(".apsession.zip") || name.endsWith(".zip") -> DesktopProfileFormat.SESSION_PACKAGE
        name == "perf.data" || name.endsWith(".perf.data") || name.endsWith(".data") ->
            DesktopProfileFormat.PERF_DATA
        name.endsWith(".protobuf") || name.endsWith(".proto") || name.endsWith(".trace") ->
            DesktopProfileFormat.SIMPLEPERF_PROTOBUF
        name.endsWith(".json.gz") -> DesktopProfileFormat.GECKO_PROFILE_JSON_GZIP
        else -> DesktopProfileFormat.UNSUPPORTED
    }
}
