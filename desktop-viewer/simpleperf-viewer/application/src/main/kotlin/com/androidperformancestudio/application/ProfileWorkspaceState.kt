package com.androidperformancestudio.application

import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.storage.ProfileProjectionSnapshot
import com.androidperformancestudio.storage.ProfileQuery
import java.nio.file.Path

@JvmInline
value class ProfileGeneration(
    val value: Long,
)

sealed interface ProfileWorkspaceLoadState {
    data object Closed : ProfileWorkspaceLoadState

    data class Loading(
        val sessionDirectory: Path,
    ) : ProfileWorkspaceLoadState

    data class Refreshing(
        val sessionDirectory: Path,
    ) : ProfileWorkspaceLoadState

    data class Ready(
        val sessionDirectory: Path,
    ) : ProfileWorkspaceLoadState

    data class Failed(
        val sessionDirectory: Path,
        val error: StudioError,
    ) : ProfileWorkspaceLoadState
}

data class ProfileWorkspaceState(
    val generation: ProfileGeneration = ProfileGeneration(0),
    val sessionDirectory: Path? = null,
    val sessionMode: ProfileSessionMode? = null,
    val preparedSession: PreparedProfileSession? = null,
    val query: ProfileQuery = ProfileQuery(),
    val snapshot: ProfileProjectionSnapshot? = null,
    val loadState: ProfileWorkspaceLoadState = ProfileWorkspaceLoadState.Closed,
) {
    fun request(nextQuery: ProfileQuery): ProfileWorkspaceState =
        copy(
            generation = ProfileGeneration(generation.value + 1),
            query = nextQuery,
            loadState = ProfileWorkspaceLoadState.Refreshing(checkNotNull(sessionDirectory)),
        )
}
