package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDevicesParser
import com.androidperformancestudio.platform.adb.AdbOutputParseException

class StudioAdbDevicesParser(
    private val delegate: AdbDevicesParser = AdbDevicesParser(),
) {
    fun parse(output: String): StudioResult<List<AdbDevice>> =
        try {
            StudioResult.Success(delegate.parse(output))
        } catch (error: AdbOutputParseException) {
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.DATA_VALIDATION,
                    code = "ADB_DEVICES_LINE_MALFORMED",
                    message = error.message ?: "Malformed adb devices output",
                    cause = error,
                ),
            )
        }
}
