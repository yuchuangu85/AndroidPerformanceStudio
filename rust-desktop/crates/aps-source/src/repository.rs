use crate::{
    ResolutionCandidate, ResolutionCandidateId, SourceFile, SourceSnapshot, SourceSnapshotId,
    SourceSymbol, SourceWorkspace, SourceWorkspaceId,
};
use std::collections::HashMap;

/// Compose SourceWorkspaceRepository/SourceIndexView port. Persistence is
/// replaced by the Rust-owned SQLite implementation in aps-storage.
pub trait SourceRepository {
    fn save_workspace(&mut self, workspace: &SourceWorkspace) -> Result<(), String>;

    fn workspace(&self, id: SourceWorkspaceId) -> Result<Option<SourceWorkspace>, String>;

    fn workspaces(&self) -> Result<Vec<SourceWorkspace>, String>;

    fn delete_workspace(&mut self, id: SourceWorkspaceId) -> Result<(), String>;

    fn save_snapshot(
        &mut self,
        snapshot: &SourceSnapshot,
        files: &[SourceFile],
        symbols: &[SourceSymbol],
    ) -> Result<(), String>;

    fn snapshot(&self, id: SourceSnapshotId) -> Result<Option<SourceSnapshot>, String>;

    fn files(&self, snapshot_id: SourceSnapshotId) -> Result<Vec<SourceFile>, String>;

    fn symbols(&self, snapshot_id: SourceSnapshotId) -> Result<Vec<SourceSymbol>, String>;

    fn save_candidates(&mut self, candidates: &[ResolutionCandidate]) -> Result<(), String>;

    fn candidate(&self, id: ResolutionCandidateId) -> Result<Option<ResolutionCandidate>, String>;
}

/// Compose InMemorySourceWorkspaceRepository. Workspace listing keeps insertion
/// order; snapshot-owned rows disappear with their workspace.
#[derive(Debug, Default)]
pub struct InMemorySourceRepository {
    workspaces: Vec<SourceWorkspace>,
    snapshots: Vec<SourceSnapshot>,
    files: HashMap<SourceSnapshotId, Vec<SourceFile>>,
    symbols: HashMap<SourceSnapshotId, Vec<SourceSymbol>>,
    candidates: HashMap<ResolutionCandidateId, ResolutionCandidate>,
}

impl SourceRepository for InMemorySourceRepository {
    fn save_workspace(&mut self, workspace: &SourceWorkspace) -> Result<(), String> {
        match self
            .workspaces
            .iter_mut()
            .find(|existing| existing.id == workspace.id)
        {
            Some(existing) => *existing = workspace.clone(),
            None => self.workspaces.push(workspace.clone()),
        }
        Ok(())
    }

    fn workspace(&self, id: SourceWorkspaceId) -> Result<Option<SourceWorkspace>, String> {
        Ok(self
            .workspaces
            .iter()
            .find(|workspace| workspace.id == id)
            .cloned())
    }

    fn workspaces(&self) -> Result<Vec<SourceWorkspace>, String> {
        Ok(self.workspaces.clone())
    }

    fn delete_workspace(&mut self, id: SourceWorkspaceId) -> Result<(), String> {
        let removed: Vec<SourceSnapshotId> = self
            .snapshots
            .iter()
            .filter(|snapshot| snapshot.workspace_id == id)
            .map(|snapshot| snapshot.id)
            .collect();
        self.snapshots
            .retain(|snapshot| snapshot.workspace_id != id);
        for snapshot_id in &removed {
            self.files.remove(snapshot_id);
            self.symbols.remove(snapshot_id);
        }
        self.candidates.retain(|_, candidate| {
            candidate.location.workspace_id != id
                && !removed.contains(&candidate.location.snapshot_id)
        });
        self.workspaces.retain(|workspace| workspace.id != id);
        Ok(())
    }

    fn save_snapshot(
        &mut self,
        snapshot: &SourceSnapshot,
        files: &[SourceFile],
        symbols: &[SourceSymbol],
    ) -> Result<(), String> {
        match self
            .snapshots
            .iter_mut()
            .find(|existing| existing.id == snapshot.id)
        {
            Some(existing) => *existing = snapshot.clone(),
            None => self.snapshots.push(snapshot.clone()),
        }
        self.files.insert(snapshot.id, files.to_vec());
        self.symbols.insert(snapshot.id, symbols.to_vec());
        Ok(())
    }

    fn snapshot(&self, id: SourceSnapshotId) -> Result<Option<SourceSnapshot>, String> {
        Ok(self
            .snapshots
            .iter()
            .find(|snapshot| snapshot.id == id)
            .cloned())
    }

    fn files(&self, snapshot_id: SourceSnapshotId) -> Result<Vec<SourceFile>, String> {
        Ok(self.files.get(&snapshot_id).cloned().unwrap_or_default())
    }

    fn symbols(&self, snapshot_id: SourceSnapshotId) -> Result<Vec<SourceSymbol>, String> {
        Ok(self.symbols.get(&snapshot_id).cloned().unwrap_or_default())
    }

    fn save_candidates(&mut self, candidates: &[ResolutionCandidate]) -> Result<(), String> {
        for candidate in candidates {
            self.candidates.insert(candidate.id, candidate.clone());
        }
        Ok(())
    }

    fn candidate(&self, id: ResolutionCandidateId) -> Result<Option<ResolutionCandidate>, String> {
        Ok(self.candidates.get(&id).cloned())
    }
}
