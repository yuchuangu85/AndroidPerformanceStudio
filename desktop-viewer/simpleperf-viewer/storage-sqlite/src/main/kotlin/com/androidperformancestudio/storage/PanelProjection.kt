package com.androidperformancestudio.storage

sealed interface PanelProjection<out T> {
    data class Ready<T>(
        val value: T,
    ) : PanelProjection<T>

    data class Failed(
        val code: String,
        val message: String,
    ) : PanelProjection<Nothing>
}
