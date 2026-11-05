package com.androidperformancestudio.memory.model

/** HPROF snapshots are parsed by the Rust engine. */
enum class HprofEngine(
    val storageValue: String,
) {
    RUST("rust"),
    ;

    companion object {
        val DEFAULT: HprofEngine = RUST

        fun parse(value: String?): HprofEngine = DEFAULT
    }
}
