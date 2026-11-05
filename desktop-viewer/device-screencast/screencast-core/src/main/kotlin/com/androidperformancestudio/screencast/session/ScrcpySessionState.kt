package com.androidperformancestudio.screencast.session

/** Connection state machine exposed to the UI. */
sealed interface ScrcpySessionState {
    /** No session; nothing is running. */
    data object Idle : ScrcpySessionState

    /** Push/tunnel/handshake in progress; cannot be interrupted. */
    data object Connecting : ScrcpySessionState

    /** Video is streaming and control is available. */
    data class Running(
        val deviceName: String,
        val width: Int,
        val height: Int,
    ) : ScrcpySessionState

    /** The session ended (device disconnected or server exited). */
    data object Disconnected : ScrcpySessionState

    /** The session failed before or during startup. */
    data class Failed(
        val message: String,
    ) : ScrcpySessionState
}
