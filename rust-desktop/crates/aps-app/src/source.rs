use aps_ai::{AnalysisRequest, SourceCandidate};
use aps_contracts::SourceDigest;
use aps_source::{
    LocalSourceWorkspace, ResolutionCandidate, ResolutionQuery, SnippetLimits, SourceFilesPage,
    SourceLimits, SourceLocation, SourceSnapshot, SourceSnippet,
};
use aps_storage::{Page, PageRequest, SourceSnapshotMetadata, SourceWorkspaceMetadata, Storage};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable,
        button::{Button, ButtonVariants},
        input::{Input, InputState},
    },
    prelude::*,
    *,
};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

const FILE_PAGE: usize = 100;
const RECENT_PAGE: usize = 25;
pub struct SourceWorkspace {
    language: Language,
    metadata_path: Option<PathBuf>,
    recent: Option<Page<SourceWorkspaceMetadata>>,
    recent_requested: bool,
    recent_generation: u64,
    recent_busy: bool,
    metadata_error: Option<String>,
    workspace: Option<Arc<LocalSourceWorkspace>>,
    snapshot: Option<Arc<SourceSnapshot>>,
    files: Option<SourceFilesPage>,
    snippet: Option<SourceSnippet>,
    candidates: Vec<ResolutionCandidate>,
    selected_candidate: Option<ResolutionCandidate>,
    query: Entity<InputState>,
    busy: bool,
    generation: u64,
    error: Option<String>,
}
impl SourceWorkspace {
    pub fn new(
        language: Language,
        metadata_path: Option<PathBuf>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        Self {
            language,
            metadata_path,
            recent: None,
            recent_requested: false,
            recent_generation: 0,
            recent_busy: false,
            metadata_error: None,
            workspace: None,
            snapshot: None,
            files: None,
            snippet: None,
            candidates: Vec::new(),
            selected_candidate: None,
            query: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::SourceSymbolDeclarationPlaceholder))
            }),
            busy: false,
            generation: 0,
            error: None,
        }
    }
    pub fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.language = language;
        self.query.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::SourceSymbolDeclarationPlaceholder),
                window,
                cx,
            )
        });
        cx.notify();
    }
    fn refresh_recent(&mut self, offset: usize, cx: &mut Context<Self>) {
        self.recent_requested = true;
        let Some(path) = self.metadata_path.clone() else {
            return;
        };
        self.recent_busy = true;
        self.recent_generation += 1;
        let generation = self.recent_generation;
        let task = cx.background_executor().spawn(async move {
            if !path.exists() {
                return Ok::<_, String>(Page {
                    total: 0,
                    offset,
                    rows: Vec::new(),
                });
            }
            Storage::open(path)?.list_source_workspaces(PageRequest {
                offset,
                limit: RECENT_PAGE,
            })
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.recent_generation != generation {
                    return;
                }
                this.recent_busy = false;
                match result {
                    Ok(page) => this.recent = Some(page),
                    Err(error) => this.metadata_error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    fn pick(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        self.busy = true;
        self.generation += 1;
        let generation = self.generation;
        cx.notify();
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: false,
            directories: true,
            multiple: false,
            prompt: Some(
                self.language
                    .text(TextKey::SourceRegisterLocalSourceWorkspace)
                    .into(),
            ),
        });
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(Ok(Some(paths))) if paths.len() == 1 => {
                        this.index(paths[0].clone(), None, cx)
                    }
                    Ok(Err(_)) => {
                        this.error = Some(
                            this.language
                                .text(TextKey::DetailsSourceFilePickerFailed)
                                .into(),
                        )
                    }
                    _ => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    fn index(
        &mut self,
        path: PathBuf,
        expected_workspace_id: Option<String>,
        cx: &mut Context<Self>,
    ) {
        if self.busy {
            return;
        }
        self.busy = true;
        self.error = None;
        self.metadata_error = None;
        self.generation += 1;
        let generation = self.generation;
        let metadata_path = self.metadata_path.clone();
        let language = self.language;
        let task = cx.background_executor().spawn(async move {
            let name = path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or(language.text(TextKey::DetailsSourceWorkspaceDefaultName))
                .to_owned();
            let workspace = Arc::new(LocalSourceWorkspace::register(path, name)?);
            if expected_workspace_id
                .as_deref()
                .is_some_and(|expected| expected != workspace.id().to_hex())
            {
                return Err(language
                    .text(TextKey::DetailsSourceWorkspaceIdentityMismatch)
                    .into());
            }
            let snapshot = Arc::new(workspace.snapshot(SourceLimits::default())?);
            let files = snapshot.files(0, FILE_PAGE)?;
            let metadata_error = metadata_path.and_then(|path| {
                persist_source_metadata(&path, &workspace, &snapshot, language).err()
            });
            Ok::<_, String>((workspace, snapshot, files, metadata_error))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok((workspace, snapshot, files, metadata_error)) => {
                        this.workspace = Some(workspace);
                        this.snapshot = Some(snapshot);
                        this.files = Some(files);
                        this.metadata_error = metadata_error;
                        this.snippet = None;
                        this.candidates.clear();
                        this.refresh_recent(0, cx);
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn close(&mut self, cx: &mut Context<Self>) {
        // A bounded background read/index may finish, but can no longer publish.
        self.generation += 1;
        self.busy = false;
        self.workspace = None;
        self.snapshot = None;
        self.files = None;
        self.snippet = None;
        self.candidates.clear();
        self.selected_candidate = None;
        self.error = None;
        cx.notify();
    }
    fn files(&mut self, offset: usize, cx: &mut Context<Self>) {
        if let Some(snapshot) = &self.snapshot {
            match snapshot.files(offset, FILE_PAGE) {
                Ok(page) => self.files = Some(page),
                Err(error) => self.error = Some(error),
            }
        }
        cx.notify();
    }
    fn resolve(&mut self, cx: &mut Context<Self>) {
        let Some(snapshot) = &self.snapshot else {
            return;
        };
        let text = self.query.read(cx).value().trim().to_owned();
        match snapshot.resolve(&ResolutionQuery {
            symbol: Some(text),
            ..Default::default()
        }) {
            Ok(candidates) => {
                self.candidates = candidates;
                self.error = None;
            }
            Err(error) => {
                self.error = Some(error);
                self.candidates.clear();
            }
        }
        cx.notify();
    }
    fn file(&mut self, path: String, start: usize, cx: &mut Context<Self>) {
        let Some(snapshot) = &self.snapshot else {
            return;
        };
        // Candidate navigation must work even outside the current file page.
        let lines = match snapshot.file(&path) {
            Ok(file) => file.line_count,
            Err(error) => {
                self.error = Some(error);
                cx.notify();
                return;
            }
        };
        if lines == 0 {
            self.error = Some(self.language.text(TextKey::DetailsSourceFileEmpty).into());
            cx.notify();
            return;
        }
        match snapshot.location(
            &path,
            start.clamp(1, lines),
            start.clamp(1, lines).saturating_add(199).min(lines),
        ) {
            Ok(location) => self.read(location, cx),
            Err(error) => {
                self.error = Some(error);
                cx.notify();
            }
        }
    }
    fn read(&mut self, location: SourceLocation, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let (Some(workspace), Some(snapshot)) = (self.workspace.clone(), self.snapshot.clone())
        else {
            return;
        };
        self.busy = true;
        self.error = None;
        self.snippet = None;
        self.generation += 1;
        let generation = self.generation;
        let task = cx.background_executor().spawn(async move {
            workspace.read_location(&snapshot, &location, SnippetLimits::default())
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(snippet) => {
                        this.snippet = Some(snippet);
                        this.error = None;
                    }
                    Err(error) => {
                        this.error = Some(error);
                        this.snippet = None;
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Navigates from a CPU flame/call-tree symbol to the first indexed source
    /// declaration. The page must already have a registered and indexed
    /// workspace; otherwise the local error explains how to enable navigation.
    pub fn navigate_to_symbol(&mut self, symbol: String, resource: String, cx: &mut Context<Self>) {
        let Some(snapshot) = self.snapshot.clone() else {
            self.error = Some(
                self.language
                    .text(TextKey::SourceOpenFromCpuRequiresIndex)
                    .into(),
            );
            cx.notify();
            return;
        };
        self.candidates.clear();
        self.selected_candidate = None;
        self.snippet = None;
        let mut query = ResolutionQuery {
            symbol: Some(symbol.clone()),
            ..Default::default()
        };
        let mut candidates = snapshot.resolve(&query).unwrap_or_default();
        if candidates.is_empty() {
            let basename = resource
                .rsplit(['/', '\\'])
                .next()
                .filter(|name| !name.is_empty())
                .map(str::to_owned);
            if let Some(file_name) = basename {
                query = ResolutionQuery {
                    symbol: None,
                    file_name: Some(file_name),
                    ..Default::default()
                };
                candidates = snapshot.resolve(&query).unwrap_or_default();
            }
        }
        let Some(candidate) = candidates
            .iter()
            .find(|candidate| candidate.confidence == aps_source::ResolutionConfidence::Probable)
            .or_else(|| candidates.first())
            .cloned()
        else {
            self.error = Some(
                self.language
                    .text(TextKey::SourceOpenFromCpuNoSymbolDeclaration)
                    .into(),
            );
            self.candidates = candidates;
            cx.notify();
            return;
        };
        self.candidates = candidates;
        self.selected_candidate = Some(candidate.clone());
        self.read(candidate.location, cx);
    }

    pub fn analysis_request(&self) -> Result<AnalysisRequest, String> {
        use crate::evidence::{clipped, object};
        let candidate = self.selected_candidate.as_ref().ok_or_else(|| {
            self.language
                .text(TextKey::DetailsSourceCandidateRequired)
                .to_owned()
        })?;
        let snapshot = self.snapshot.as_ref().ok_or_else(|| {
            self.language
                .text(TextKey::DetailsSourceSnapshotUnavailable)
                .to_owned()
        })?;
        snapshot.validate_candidate(candidate)?;
        let snippet = self
            .snippet
            .as_ref()
            .filter(|snippet| snippet.location == candidate.location)
            .map(|snippet| clipped(&snippet.text, 16 * 1024));
        let evidence = object([
            ("candidateId", candidate.id.to_hex().into()),
            (
                "relativePath",
                candidate.location.relative_path.clone().into(),
            ),
            ("startLine", (candidate.location.start_line as u64).into()),
            ("endLine", (candidate.location.end_line as u64).into()),
            ("indexVersion", (candidate.index_version as u64).into()),
            ("confidence", format!("{:?}", candidate.confidence).into()),
            ("snippetAvailable", snippet.is_some().into()),
        ]);
        Ok(AnalysisRequest {
            session_id: format!("source-{}", snapshot.id().to_hex()),
            run_id: format!("source-selection-{}", candidate.id.to_hex()),
            generation: 1,
            profiler: "source-workspace".into(),
            scope: "One explicitly selected, hash-bound source candidate; no workspace upload or path discovery.".into(),
            evidence: vec![aps_ai::Evidence {
                id: "source-selection".into(),
                kind: "source-candidate-selection".into(),
                summary: clipped(&candidate.reasons.join("; "), 2048),
                payload: evidence,
            }],
            candidates: vec![SourceCandidate {
                id: candidate.id.to_hex(),
                workspace_id: candidate.location.workspace_id.to_hex(),
                snapshot_id: candidate.location.snapshot_id.to_hex(),
                content_digest: candidate.location.content_hash,
                relative_path: candidate.location.relative_path.clone(),
                start_line: Some(candidate.location.start_line as u32),
                end_line: Some(candidate.location.end_line as u32),
                snippet,
            }],
            user_question: None,
        })
    }
}
fn persist_source_metadata(
    path: &Path,
    workspace: &LocalSourceWorkspace,
    snapshot: &SourceSnapshot,
    language: Language,
) -> Result<(), String> {
    let parent = path.parent().ok_or_else(|| {
        language
            .text(TextKey::DetailsSourceMetadataParentMissing)
            .to_owned()
    })?;
    if !parent.is_absolute() {
        return Err(language
            .text(TextKey::DetailsSourceMetadataPathAbsolute)
            .into());
    }
    let mut builder = std::fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder.create(parent).map_err(|_| {
        language
            .text(TextKey::DetailsSourceMetadataDirectoryCreate)
            .to_owned()
    })?;
    let parent_meta = std::fs::symlink_metadata(parent).map_err(|_| {
        language
            .text(TextKey::DetailsSourceMetadataDirectoryInspect)
            .to_owned()
    })?;
    if !parent_meta.is_dir() || parent_meta.file_type().is_symlink() {
        return Err(language
            .text(TextKey::DetailsSourceMetadataDirectoryInvalid)
            .into());
    }
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| {
            language
                .text(TextKey::DetailsSourceMetadataClockBeforeEpoch)
                .to_owned()
        })?
        .as_millis() as u64;
    let workspace_id = workspace.id().to_hex();
    let mut storage = Storage::open(path)?;
    storage.put_source_workspace(&SourceWorkspaceMetadata {
        workspace_id: workspace_id.clone(),
        display_name: workspace.display_name().to_owned(),
        local_root: workspace
            .root()
            .to_str()
            .ok_or_else(|| language.text(TextKey::DetailsSourceRootUtf8).to_owned())?
            .to_owned(),
        created_unix_ms: now,
    })?;
    storage.put_source_snapshot(&SourceSnapshotMetadata {
        snapshot_id: snapshot.id().to_hex(),
        workspace_id,
        manifest_digest: SourceDigest::from_hex(&snapshot.id().to_hex())?,
        file_count: snapshot.file_count() as u64,
        indexed_bytes: snapshot.coverage().indexed_bytes,
        index_version: aps_source::INDEX_VERSION,
        manifest_complete: snapshot.coverage().manifest_complete(),
        created_unix_ms: now,
    })
}
impl Render for SourceWorkspace {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.recent_requested {
            self.refresh_recent(0, cx);
        }
        let lang = self.language;
        let busy = self.busy;
        let mut body = div()
            .flex()
            .flex_col()
            .gap_3()
            .p_4()
            .child(
                div()
                    .flex()
                    .gap_2()
                    .child(
                        Button::new("source-register")
                            .primary()
                            .label(lang.text(TextKey::SourceRegisterDirectory))
                            .disabled(busy)
                            .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
                    )
                    .child(
                        Button::new("source-refresh")
                            .label(lang.text(TextKey::SourceRefreshSnapshot))
                            .disabled(busy || self.workspace.is_none())
                            .on_click(cx.listener(|this, _, _, cx| {
                                if let Some(workspace) = &this.workspace {
                                    this.index(
                                        workspace.root().to_path_buf(),
                                        Some(workspace.id().to_hex()),
                                        cx,
                                    );
                                }
                            })),
                    ),
            )
            .child(
                Button::new("source-close")
                    .label(lang.text(TextKey::SourceCloseWorkspace))
                    .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
            )
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .child(lang.text(TextKey::SourceReadOnlyLocalAndBoundedNothingIs)),
            )
            .children(
                self.error
                    .clone()
                    .map(|error| div().text_color(cx.theme().danger).child(error)),
            )
            .children(self.metadata_error.clone().map(|error| {
                div()
                    .text_color(cx.theme().danger)
                    .child(lang.format(TextKey::DetailsSourceMetadataError, &[("error", &error)]))
            }));
        if self.metadata_path.is_none() {
            body = body
                .child(lang.text(TextKey::SourceLocalMetadataIsUnavailableRegistrationsLastOnly));
        }
        if let Some(recent) = &self.recent {
            let offset = recent.offset;
            let total = recent.total;
            body = body.child(
                div()
                    .flex()
                    .gap_2()
                    .child(lang.text(TextKey::SourceRegisteredDirectories))
                    .child(
                        Button::new("recent-prev")
                            .label("←")
                            .disabled(self.recent_busy || offset == 0)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.refresh_recent(offset.saturating_sub(RECENT_PAGE), cx)
                            })),
                    )
                    .child(lang.format(
                        TextKey::DetailsSourcePageRange,
                        &[
                            ("start", &(offset + usize::from(!recent.rows.is_empty()))),
                            ("end", &(offset + recent.rows.len())),
                            ("total", &total),
                        ],
                    ))
                    .child(
                        Button::new("recent-next")
                            .label("→")
                            .disabled(self.recent_busy || offset + RECENT_PAGE >= total)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.refresh_recent(offset + RECENT_PAGE, cx)
                            })),
                    ),
            );
            body = body.children(recent.rows.iter().enumerate().map(|(index, item)| {
                let path = PathBuf::from(&item.local_root);
                let id = item.workspace_id.clone();
                Button::new(("recent-source", index))
                    .ghost()
                    .label(lang.format(
                        TextKey::DetailsSourceRecentWorkspace,
                        &[
                            ("display_name", &item.display_name),
                            ("local_root", &item.local_root),
                        ],
                    ))
                    .disabled(busy)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.index(path.clone(), Some(id.clone()), cx)
                    }))
            }));
        }
        if busy {
            body = body.child(lang.text(TextKey::SourceReadingIndexing));
        }
        if let Some(snapshot) = &self.snapshot {
            let coverage = snapshot.coverage();
            body = body
                .child(lang.format(
                    TextKey::DetailsSourceSnapshot,
                    &[
                        ("snapshot_id", &snapshot.id().to_hex()),
                        ("file_count", &snapshot.file_count()),
                        ("symbol_count", &coverage.indexed_symbols),
                        ("omitted_files", &coverage.omitted_files),
                        ("unreadable_entries", &coverage.unreadable_entries),
                    ],
                ))
                .child(
                    div().flex().gap_2().child(Input::new(&self.query)).child(
                        Button::new("source-resolve")
                            .label(lang.text(TextKey::SourceResolveSymbol))
                            .disabled(busy)
                            .on_click(cx.listener(|this, _, _, cx| this.resolve(cx))),
                    ),
                );
            body = body.children(
                self.candidates
                    .iter()
                    .enumerate()
                    .map(|(index, candidate)| {
                        let selected_candidate = candidate.clone();
                        let location = candidate.location.clone();
                        Button::new(("candidate", index))
                            .label(lang.format(
                                TextKey::DetailsSourceCandidate,
                                &[
                                    ("confidence", &format!("{:?}", candidate.confidence)),
                                    ("relative_path", &location.relative_path),
                                    ("line", &location.start_line),
                                    ("reasons", &candidate.reasons.join("; ")),
                                ],
                            ))
                            .disabled(busy)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.selected_candidate = Some(selected_candidate.clone());
                                this.read(location.clone(), cx)
                            }))
                    }),
            );
        }
        if let Some(files) = &self.files {
            let offset = files.offset;
            let total = files.total;
            body = body.child(
                div()
                    .flex()
                    .gap_2()
                    .child(lang.format(
                        TextKey::DetailsSourceFilesPage,
                        &[
                            ("start", &(offset + usize::from(!files.rows.is_empty()))),
                            ("end", &(offset + files.rows.len())),
                            ("total", &total),
                        ],
                    ))
                    .child(
                        Button::new("source-prev")
                            .label(lang.text(TextKey::CommonPrevious))
                            .disabled(busy || offset == 0)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.files(offset.saturating_sub(FILE_PAGE), cx)
                            })),
                    )
                    .child(
                        Button::new("source-next")
                            .label(lang.text(TextKey::CommonNext))
                            .disabled(busy || offset + FILE_PAGE >= total)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.files(offset + FILE_PAGE, cx)
                            })),
                    ),
            );
            let list = div()
                .w(px(300.))
                .flex_shrink_0()
                .flex()
                .flex_col()
                .gap_1()
                .children(files.rows.iter().enumerate().map(|(index, file)| {
                    let path = file.relative_path.clone();
                    Button::new(("source-file", index))
                        .ghost()
                        .label(path.clone())
                        .disabled(busy)
                        .on_click(cx.listener(move |this, _, _, cx| this.file(path.clone(), 1, cx)))
                }));
            let mut viewer = div().flex_1().min_w_0().flex().flex_col().gap_2();
            if let Some(snippet) = &self.snippet {
                let previous = snippet.start_line.saturating_sub(200).max(1);
                let next = snippet.end_line.saturating_add(1);
                let path = snippet.location.relative_path.clone();
                let next_path = path.clone();
                viewer = viewer
                    .child(lang.format(
                        TextKey::DetailsSourceSnippet,
                        &[
                            ("relative_path", &snippet.location.relative_path),
                            ("start_line", &snippet.start_line),
                            ("end_line", &snippet.end_line),
                            ("total_lines", &snippet.total_file_lines),
                            ("content_hash", &snippet.location.content_hash),
                        ],
                    ))
                    .child(
                        div()
                            .flex()
                            .gap_2()
                            .child(
                                Button::new("source-code-prev")
                                    .label(lang.text(TextKey::SourcePreviousLines))
                                    .disabled(busy || snippet.start_line <= 1)
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.file(path.clone(), previous, cx)
                                    })),
                            )
                            .child(
                                Button::new("source-code-next")
                                    .label(lang.text(TextKey::SourceNextLines))
                                    .disabled(busy || snippet.end_line >= snippet.total_file_lines)
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.file(next_path.clone(), next, cx)
                                    })),
                            ),
                    )
                    .child(
                        div()
                            .id("source-code")
                            .overflow_x_scroll()
                            .p_3()
                            .bg(cx.theme().muted)
                            .font_family("monospace")
                            .text_size(px(typography::BODY))
                            .flex()
                            .gap_3()
                            .child(
                                div()
                                    .flex_shrink_0()
                                    .text_color(cx.theme().muted_foreground)
                                    .child(
                                        (snippet.start_line..=snippet.end_line)
                                            .map(|line| line.to_string())
                                            .collect::<Vec<_>>()
                                            .join("\n"),
                                    ),
                            )
                            .child(div().flex_shrink_0().child(snippet.text.clone())),
                    );
                if snippet.truncated {
                    viewer =
                        viewer.child(lang.text(TextKey::SourceSnippetOutputWasTruncatedByItsByte));
                }
            } else {
                viewer = viewer.child(lang.text(TextKey::SourceSelectAFileOrASourceCandidate));
            }
            body = body.child(div().flex().gap_3().child(list).child(viewer));
        }
        body
    }
}
