package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import kotlin.time.Duration

/** Reads the standard Android device property snapshot through the shared [AdbClient]. */
public class AndroidDevicePropertiesClient(
    private val adbClient: AdbClient,
    private val parser: AndroidGetpropParser = AndroidGetpropParser(),
) {
    public suspend fun read(
        serial: String,
        timeout: Duration = AdbClient.DEFAULT_TIMEOUT,
        isCancellationRequested: () -> Boolean = { false },
    ): StudioResult<AndroidDeviceProperties> =
        try {
            parser.parse(
                serial,
                adbClient
                    .shell(
                        serial = serial,
                        arguments = listOf("getprop"),
                        timeout = timeout,
                        isCancellationRequested = isCancellationRequested,
                    ).stdout,
            )
        } catch (error: AdbException) {
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "ADB_DEVICE_PROPERTIES_READ_FAILED",
                    message = error.message ?: "Unable to read Android device properties for $serial",
                    cause = error,
                ),
            )
        }
}
