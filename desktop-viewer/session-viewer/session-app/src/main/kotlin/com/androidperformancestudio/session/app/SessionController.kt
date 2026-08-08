package com.androidperformancestudio.session.app

import com.androidperformancestudio.session.model.ProfilerSession
import com.androidperformancestudio.session.model.SessionSegment
import com.androidperformancestudio.session.storage.SessionStore
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionState(
    val sessions: List<ProfilerSession> = emptyList(),
    val selectedSessionId: String? = null,
    val error: String? = null,
) {
    val selectedSession: ProfilerSession?
        get() = sessions.firstOrNull { it.id == selectedSessionId }
}

/** Drives the unified Session workspace: list persisted sessions, create/add segments, select. */
class SessionController(
    private val store: SessionStore = SessionStore(SessionStore.defaultFile()),
) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.value = mutableState.value.copy(sessions = store.listRecent(), error = null)
    }

    fun selectSession(id: String) {
        mutableState.value = mutableState.value.copy(selectedSessionId = id)
    }

    fun createSession(name: String) {
        val session =
            ProfilerSession(
                id = "session-${System.currentTimeMillis()}",
                name = name.ifBlank { "Session ${mutableState.value.sessions.size + 1}" },
                createdAtEpochMillis = Instant.now().toEpochMilli(),
            )
        store.save(session)
        refresh()
        selectSession(session.id)
    }

    fun addSegment(
        sessionId: String,
        segment: SessionSegment,
    ) {
        val session = store.find(sessionId) ?: return
        store.save(session.withSegment(segment))
        refresh()
    }

    fun deleteSession(id: String) {
        store.delete(id)
        refresh()
    }
}
