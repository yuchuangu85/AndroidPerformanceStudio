package com.androidperformancestudio.source

import java.security.MessageDigest

internal fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun String.sha256(): String = encodeToByteArray().sha256()
