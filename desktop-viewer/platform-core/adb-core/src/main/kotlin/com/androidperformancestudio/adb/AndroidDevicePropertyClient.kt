package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import kotlin.time.Duration

/** Shared typed access to individual Android `getprop` values through [AdbClient]. */
public class AndroidDevicePropertyClient(
    private val adbClient: AdbClient,
) {
    public suspend fun read(
        serial: String,
        property: String,
        timeout: Duration = AdbClient.DEFAULT_TIMEOUT,
        isCancellationRequested: () -> Boolean = { false },
    ): StudioResult<String> {
        if (!PROPERTY_PATTERN.matches(property)) {
            return failure(serial, property, "ADB_DEVICE_PROPERTY_INVALID", "Invalid Android property name: $property")
        }
        return try {
            StudioResult.Success(
                adbClient
                    .shell(
                        serial = serial,
                        arguments = listOf("getprop", property),
                        timeout = timeout,
                        isCancellationRequested = isCancellationRequested,
                    ).stdout
                    .trim(),
            )
        } catch (error: AdbException) {
            failure(
                serial,
                property,
                "ADB_DEVICE_PROPERTY_READ_FAILED",
                error.message ?: "Unable to read Android property $property for $serial",
                error,
            )
        }
    }

    public suspend fun sdkInt(
        serial: String,
        timeout: Duration = AdbClient.DEFAULT_TIMEOUT,
        isCancellationRequested: () -> Boolean = { false },
    ): StudioResult<Int> =
        when (val result = read(serial, SDK_PROPERTY, timeout, isCancellationRequested)) {
            is StudioResult.Failure -> result
            is StudioResult.Success ->
                result.value.toIntOrNull()?.takeIf { it > 0 }?.let { StudioResult.Success(it) }
                    ?: failure(
                        serial,
                        SDK_PROPERTY,
                        "ADB_DEVICE_SDK_INVALID",
                        "Invalid Android API level: ${result.value}",
                    )
        }

    private fun <T> failure(
        serial: String,
        property: String,
        code: String,
        message: String,
        cause: Throwable? = null,
    ): StudioResult<T> =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.DATA_VALIDATION,
                code = code,
                message = "$message (serial=$serial, property=$property)",
                cause = cause,
            ),
        )

    private companion object {
        const val SDK_PROPERTY = "ro.build.version.sdk"
        val PROPERTY_PATTERN = Regex("[A-Za-z0-9_.-]+")
    }
}
