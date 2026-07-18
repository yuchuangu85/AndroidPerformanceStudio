package com.androidperformancestudio.storage

internal fun firefoxCompatibleSymbolName(
    symbolName: String,
    filePath: String,
    virtualAddress: Long,
): String =
    if (OPAQUE_VENDOR_SYMBOL.matches(symbolName)) {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        "$fileName+0x${java.lang.Long.toUnsignedString(virtualAddress, HEX_RADIX)}"
    } else {
        symbolName
    }

private const val HEX_RADIX = 16
private val OPAQUE_VENDOR_SYMBOL = Regex("^!{3,}[0-9A-Fa-f]+(?:![0-9A-Fa-f]+)+!$")
