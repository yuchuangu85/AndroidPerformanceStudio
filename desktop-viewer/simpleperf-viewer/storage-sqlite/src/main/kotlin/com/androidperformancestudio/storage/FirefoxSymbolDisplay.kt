package com.androidperformancestudio.storage

import com.androidperformancestudio.profileanalysis.FlameFunctionId

internal object FirefoxSymbolDisplay {
    class FunctionIdentityIndex(
        private val idForSymbol: (String, String) -> FlameFunctionId = FlameFunctionId::fromSymbol,
    ) {
        private val idsBySymbol = HashMap<Pair<String, String>, FlameFunctionId>()
        private val symbolsById = HashMap<FlameFunctionId, Pair<String, String>>()

        fun functionId(
            symbolName: String,
            resource: String,
        ): FlameFunctionId =
            idsBySymbol.getOrPut(symbolName to resource) {
                val key = symbolName to resource
                val id = idForSymbol(symbolName, resource)
                val previous = symbolsById.putIfAbsent(id, key)
                check(previous == null || previous == key) { "Stable function ID collision: $id" }
                id
            }
    }
}

fun firefoxCompatibleSymbolName(
    symbolName: String,
    filePath: String,
    virtualAddress: Long,
): String =
    if (OPAQUE_VENDOR_SYMBOL.matches(symbolName) || symbolName == "<unknown-symbol>" || symbolName.isBlank()) {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        "$fileName+0x${java.lang.Long.toUnsignedString(virtualAddress, HEX_RADIX)}"
    } else {
        symbolName
    }

private const val HEX_RADIX = 16
private val OPAQUE_VENDOR_SYMBOL = Regex("^!{3,}[0-9A-Fa-f]+(?:![0-9A-Fa-f]+)+!$")
