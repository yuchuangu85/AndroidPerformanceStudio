package com.androidperformancestudio.memory.presentation

import java.text.NumberFormat
import java.util.Locale

internal fun integer(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

internal fun integer(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)

internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_MB -> "%.1f MB".format(Locale.US, bytes.toDouble() / BYTES_PER_MB)
        bytes >= BYTES_PER_KB -> "%.1f KB".format(Locale.US, bytes.toDouble() / BYTES_PER_KB)
        else -> "$bytes B"
    }

private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = 1024.0 * 1024.0
