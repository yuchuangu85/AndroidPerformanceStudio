package com.androidperformancestudio.source

public interface SourceWorkspaceRepository : SourceIndexView {
    public fun saveWorkspace(workspace: SourceWorkspace)

    public fun workspace(id: SourceWorkspaceId): SourceWorkspace?

    public fun workspaces(): List<SourceWorkspace>

    public fun deleteWorkspace(id: SourceWorkspaceId)

    public fun saveSnapshot(
        snapshot: SourceSnapshot,
        files: List<SourceFile>,
        symbols: List<SourceSymbol>,
    )

    public fun saveCandidates(candidates: List<ResolutionCandidate>)

    public fun candidate(id: ResolutionCandidateId): ResolutionCandidate?
}

public class InMemorySourceWorkspaceRepository : SourceWorkspaceRepository {
    private val workspaceValues: MutableMap<SourceWorkspaceId, SourceWorkspace> = linkedMapOf()
    private val snapshots: MutableMap<SourceSnapshotId, SourceSnapshot> = linkedMapOf()
    private val filesBySnapshot: MutableMap<SourceSnapshotId, List<SourceFile>> = linkedMapOf()
    private val symbolsBySnapshot: MutableMap<SourceSnapshotId, List<SourceSymbol>> = linkedMapOf()
    private val candidateValues: MutableMap<ResolutionCandidateId, ResolutionCandidate> = linkedMapOf()

    override fun saveWorkspace(workspace: SourceWorkspace) {
        workspaceValues[workspace.id] = workspace
    }

    override fun workspace(id: SourceWorkspaceId): SourceWorkspace? = workspaceValues[id]

    override fun workspaces(): List<SourceWorkspace> = workspaceValues.values.toList()

    override fun deleteWorkspace(id: SourceWorkspaceId) {
        val snapshotIds = snapshots.values.filter { it.workspaceId == id }.map(SourceSnapshot::id)
        snapshotIds.forEach { snapshotId ->
            snapshots.remove(snapshotId)
            filesBySnapshot.remove(snapshotId)
            symbolsBySnapshot.remove(snapshotId)
            candidateValues.entries.removeIf { it.value.location.snapshotId == snapshotId }
        }
        workspaceValues.remove(id)
    }

    override fun saveSnapshot(
        snapshot: SourceSnapshot,
        files: List<SourceFile>,
        symbols: List<SourceSymbol>,
    ) {
        snapshots[snapshot.id] = snapshot
        filesBySnapshot[snapshot.id] = files
        symbolsBySnapshot[snapshot.id] = symbols
    }

    override fun snapshot(snapshotId: SourceSnapshotId): SourceSnapshot? = snapshots[snapshotId]

    override fun files(snapshotId: SourceSnapshotId): List<SourceFile> = filesBySnapshot[snapshotId].orEmpty()

    override fun symbols(snapshotId: SourceSnapshotId): List<SourceSymbol> = symbolsBySnapshot[snapshotId].orEmpty()

    override fun saveCandidates(candidates: List<ResolutionCandidate>) {
        candidates.forEach { candidate -> candidateValues[candidate.id] = candidate }
    }

    override fun candidate(id: ResolutionCandidateId): ResolutionCandidate? = candidateValues[id]
}
