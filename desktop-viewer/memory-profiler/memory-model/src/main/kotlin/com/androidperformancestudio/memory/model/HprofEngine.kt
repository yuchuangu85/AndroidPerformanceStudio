package com.androidperformancestudio.memory.model

/** The selected engine applies to subsequent HPROF loads. */
enum class HprofEngine(
    val storageValue: String,
) {
    LEGACY("legacy"),
    RUST("rust"),
    ;

    companion object {
        val DEFAULT: HprofEngine = RUST

        fun parse(value: String?): HprofEngine =
            entries.firstOrNull { engine ->
                engine.storageValue.equals(value?.trim(), ignoreCase = true)
            } ?: DEFAULT
    }
}
