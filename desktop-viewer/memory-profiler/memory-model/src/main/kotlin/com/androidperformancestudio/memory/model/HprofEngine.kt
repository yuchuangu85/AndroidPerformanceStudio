package com.androidperformancestudio.memory.model

/** HPROF snapshots are parsed by the Kotlin engine. */
enum class HprofEngine(
    val storageValue: String,
) {
    KOTLIN("kotlin"),
    ;

    companion object {
        val DEFAULT: HprofEngine = KOTLIN

        fun parse(value: String?): HprofEngine = DEFAULT
    }
}
