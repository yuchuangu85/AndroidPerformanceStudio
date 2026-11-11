//! Real Native/ART traces and Android bitmap image dumps. Kept independently
//! from Java snapshot tabs: a heapprofd trace is never published as an HPROF.
use super::super::*;
use super::{capture::transport, state::MemoryViewMode, widgets::toolbar_button};
use crate::import_timing::ImportTiming;
use aps_contracts::SourceDigest;
use aps_perfetto::{
    TraceSession,
    heap_profile::{HeapProfileKind, HeapProfilePage},
};
use aps_platform::TempWorkspace;
use std::{io::Write, sync::Arc};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum AdvancedMemoryKind {
    Native,
    Art,
    Bitmap,
}
impl AdvancedMemoryKind {
    fn view(self) -> MemoryViewMode {
        match self {
            Self::Native => MemoryViewMode::NativeHeap,
            Self::Art => MemoryViewMode::SampledArt,
            Self::Bitmap => MemoryViewMode::BitmapDump,
        }
    }
    fn profile(self) -> HeapProfileKind {
        if self == Self::Art {
            HeapProfileKind::Art
        } else {
            HeapProfileKind::Native
        }
    }
    fn title(self) -> TextKey {
        match self {
            Self::Native => TextKey::MemoryAppNativeHeapView,
            Self::Art => TextKey::MemoryAppSampledArtView,
            Self::Bitmap => TextKey::MemoryAppBitmapDumpView,
        }
    }
    fn filename(self) -> &'static str {
        match self {
            Self::Native => "native-heap.pftrace",
            Self::Art => "art-sampled.pftrace",
            Self::Bitmap => "bitmap-dump.zip",
        }
    }
}

pub(super) struct HeapDataset {
    pub path: PathBuf,
    pub session: Arc<TraceSession>,
    pub page: HeapProfilePage,
    pub pid: Option<u32>,
    import_timing: ImportTiming,
    _capture: Option<Arc<transport::CapturedTrace>>,
}
pub(super) struct BitmapDataset {
    pub dump: aps_memory::bitmap::BitmapDump,
    import_timing: ImportTiming,
    _images: TempWorkspace,
    _capture: Option<Arc<transport::CapturedHeap>>,
}
#[derive(Default)]
pub(in crate::import) struct AdvancedMemoryUi {
    native: Option<Arc<HeapDataset>>,
    art: Option<Arc<HeapDataset>>,
    bitmap: Option<Arc<BitmapDataset>>,
    selected_image: usize,
}
impl AdvancedMemoryUi {
    fn heap(&self, kind: AdvancedMemoryKind) -> Option<&Arc<HeapDataset>> {
        if kind == AdvancedMemoryKind::Art {
            self.art.as_ref()
        } else {
            self.native.as_ref()
        }
    }
}

fn root() -> Result<PathBuf, UiText> {
    crate::settings_path()
        .ok()
        .and_then(|p| p.parent().map(|p| p.join("memory-captures")))
        .ok_or_else(|| TextKey::MemoryPageCaptureStorageUnavailable.into())
}
fn load_trace(
    path: PathBuf,
    kind: AdvancedMemoryKind,
    pid: Option<u32>,
    capture: Option<Arc<transport::CapturedTrace>>,
    started: Instant,
    cancel: &CancellationToken,
) -> Result<HeapDataset, UiText> {
    let session = Arc::new(
        TraceSession::open(locate_trace_processor(cancel)?, &path, cancel)
            .map_err(|e| UiText::from(e.to_string()))?,
    );
    let page = session
        .heap_profile(kind.profile(), pid, 0, 100, cancel)
        .map_err(|e| UiText::from(e.to_string()))?;
    Ok(HeapDataset {
        path,
        session,
        page,
        pid,
        import_timing: ImportTiming::completed(started),
        _capture: capture,
    })
}
fn load_bitmap(
    path: &Path,
    capture: Option<Arc<transport::CapturedHeap>>,
    started: Instant,
    cancel: &CancellationToken,
) -> Result<BitmapDataset, UiText> {
    let images = TempWorkspace::create(&root()?)
        .map_err(|_| UiText::from("Cannot create private bitmap image storage"))?;
    let dump = aps_memory::bitmap::extract_in_worker(
        &memory_worker_executable()?,
        path,
        images.path(),
        cancel,
    )?;
    Ok(BitmapDataset {
        dump,
        import_timing: ImportTiming::completed(started),
        _images: images,
        _capture: capture,
    })
}
enum Loaded {
    Heap(HeapDataset),
    Bitmap(BitmapDataset),
}

impl ImportWorkspace {
    pub(in crate::import) fn advanced_memory_import_duration_text(&self) -> Option<String> {
        let timing = match self.memory_state.view_mode {
            MemoryViewMode::NativeHeap => self
                .memory_advanced
                .native
                .as_ref()
                .map(|data| data.import_timing),
            MemoryViewMode::SampledArt => self
                .memory_advanced
                .art
                .as_ref()
                .map(|data| data.import_timing),
            MemoryViewMode::BitmapDump => self
                .memory_advanced
                .bitmap
                .as_ref()
                .map(|data| data.import_timing),
            _ => None,
        };
        timing.and_then(|timing| timing.text(self.language))
    }
    pub(super) fn advanced_memory_ready(&self) -> bool {
        self.kind == Destination::Memory
            && !self.busy
            && !self.export_busy
            && !self.memory_state.loading
            && !self.memory_state.instances_loading
            && !self.memory_state.detail_loading
            && !self.memory_state.comparison_loading
            && self.memory_state.dominator_loading.is_empty()
            && self.memory_capture.target().is_some()
    }
    pub(in crate::import) fn has_bitmap_dump(&self) -> bool {
        self.memory_advanced.bitmap.is_some()
    }
    pub(crate) fn capture_memory_active(&mut self, cx: &mut Context<Self>) {
        match self.memory_state.view_mode {
            MemoryViewMode::NativeHeap => {
                self.capture_advanced_memory(AdvancedMemoryKind::Native, cx)
            }
            MemoryViewMode::SampledArt => self.capture_advanced_memory(AdvancedMemoryKind::Art, cx),
            MemoryViewMode::BitmapDump => {
                self.capture_advanced_memory(AdvancedMemoryKind::Bitmap, cx)
            }
            _ => self.capture_memory_heap(cx),
        }
    }
    pub(in crate::import) fn close_advanced_memory(&mut self, cx: &mut Context<Self>) -> bool {
        let selected = matches!(
            self.memory_state.view_mode,
            MemoryViewMode::NativeHeap | MemoryViewMode::SampledArt | MemoryViewMode::BitmapDump
        );
        if !selected {
            return false;
        }
        self.cancel_work(cx);
        match self.memory_state.view_mode {
            MemoryViewMode::NativeHeap => self.memory_advanced.native = None,
            MemoryViewMode::SampledArt => self.memory_advanced.art = None,
            MemoryViewMode::BitmapDump => self.memory_advanced.bitmap = None,
            _ => {}
        }
        self.memory_state.view_mode = MemoryViewMode::Classes;
        cx.notify();
        true
    }
    fn advanced_target_text(&self) -> String {
        self.memory_capture.target().map_or_else(
            || {
                self.language
                    .text(TextKey::MemoryPageCaptureSelectTarget)
                    .into()
            },
            |target| {
                self.language.format(
                    TextKey::MemoryLiveTarget,
                    &[
                        ("process", &target.process_name),
                        ("pid", &target.pid),
                        ("serial", &target.serial),
                    ],
                )
            },
        )
    }
    pub(crate) fn capture_advanced_memory(
        &mut self,
        kind: AdvancedMemoryKind,
        cx: &mut Context<Self>,
    ) {
        if !self.advanced_memory_ready() {
            self.notice = Some(TextKey::MemoryPageCaptureSelectTarget.into());
            cx.notify();
            return;
        }
        let target = self.memory_capture.target().unwrap().clone();
        let root = match root() {
            Ok(root) => root,
            Err(e) => {
                self.error = Some(e);
                cx.notify();
                return;
            }
        };
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        let generation = self.generation;
        let cancel = self.cancel.clone();
        self.busy = true;
        self.memory_capture.in_progress = true;
        self.error = None;
        self.notice = None;
        self.memory_state.error = None;
        self.memory_state.notice = None;
        self.memory_state.view_mode = kind.view();
        self.memory_state.loading_message = Some(
            self.language
                .text(if kind == AdvancedMemoryKind::Bitmap {
                    TextKey::MemoryLiveBitmapRecording
                } else {
                    TextKey::MemoryLiveRecording
                })
                .into(),
        );
        let task_target = target.clone();
        let work = cx.background_executor().spawn(async move {
            match kind {
                AdvancedMemoryKind::Bitmap => {
                    let owner = Arc::new(transport::capture_bitmap_heap(
                        &task_target,
                        &root,
                        &cancel,
                    )?);
                    // Only host-side loading/extraction is import work; device
                    // recording time is not part of this status metric.
                    Ok(Loaded::Bitmap(load_bitmap(
                        &owner.path,
                        Some(owner.clone()),
                        Instant::now(),
                        &cancel,
                    )?))
                }
                _ => {
                    let owner = Arc::new(transport::capture_trace(
                        &task_target,
                        &root,
                        kind.profile(),
                        &cancel,
                    )?);
                    Ok(Loaded::Heap(load_trace(
                        owner.path.clone(),
                        kind,
                        Some(task_target.pid),
                        Some(owner),
                        Instant::now(),
                        &cancel,
                    )?))
                }
            }
        });
        cx.spawn(async move |this, cx| {
            let result: Result<Loaded, UiText> = work.await;
            this.update(cx, |this, cx| {
                if this.generation != generation || this.memory_capture.target() != Some(&target) {
                    return;
                }
                this.memory_capture.in_progress = false;
                this.finish_advanced_memory(kind, result, cx);
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn finish_advanced_memory(
        &mut self,
        kind: AdvancedMemoryKind,
        result: Result<Loaded, UiText>,
        cx: &mut Context<Self>,
    ) {
        self.busy = false;
        self.memory_state.loading_message = None;
        match result {
            Ok(Loaded::Heap(data)) => {
                if kind == AdvancedMemoryKind::Art {
                    self.memory_advanced.art = Some(Arc::new(data));
                } else {
                    self.memory_advanced.native = Some(Arc::new(data));
                }
                self.memory_state.view_mode = kind.view();
                self.notice = Some(TextKey::MemoryLivePublished.into());
            }
            Ok(Loaded::Bitmap(data)) => {
                self.memory_advanced.bitmap = Some(Arc::new(data));
                self.memory_advanced.selected_image = 0;
                self.memory_state.view_mode = MemoryViewMode::BitmapDump;
                self.notice = Some(TextKey::MemoryLivePublished.into());
            }
            Err(error) => self.error = Some(error),
        }
        cx.notify();
    }
    pub(crate) fn import_advanced_memory(
        &mut self,
        kind: AdvancedMemoryKind,
        cx: &mut Context<Self>,
    ) {
        if self.busy || self.export_busy {
            return;
        }
        let generation = self.generation;
        let view_mode = self.memory_state.view_mode;
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(self.language.text(TextKey::MemoryLiveImport).into()),
        });
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            this.update(cx, |this, cx| {
                if this.generation != generation || this.memory_state.view_mode != view_mode {
                    return;
                }
                if let Ok(Ok(Some(paths))) = chosen {
                    if let Some(path) = paths.into_iter().next() {
                        this.open_advanced_memory(path, kind, cx);
                    }
                }
            })
            .ok();
        })
        .detach();
    }
    fn open_advanced_memory(
        &mut self,
        path: PathBuf,
        kind: AdvancedMemoryKind,
        cx: &mut Context<Self>,
    ) {
        if self.busy || self.export_busy {
            return;
        }
        let started = Instant::now();
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        let generation = self.generation;
        let cancel = self.cancel.clone();
        self.busy = true;
        self.error = None;
        self.notice = None;
        self.memory_state.error = None;
        self.memory_state.notice = None;
        let work = cx.background_executor().spawn(async move {
            if kind == AdvancedMemoryKind::Bitmap {
                load_bitmap(&path, None, started, &cancel).map(Loaded::Bitmap)
            } else {
                load_trace(path, kind, None, None, started, &cancel).map(Loaded::Heap)
            }
        });
        cx.spawn(async move |this, cx| {
            let result = work.await;
            this.update(cx, |this, cx| {
                if this.generation == generation {
                    this.finish_advanced_memory(kind, result, cx);
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn page_advanced_memory(
        &mut self,
        kind: AdvancedMemoryKind,
        offset: usize,
        cx: &mut Context<Self>,
    ) {
        if self.busy || self.export_busy {
            return;
        }
        let Some(data) = self.memory_advanced.heap(kind).cloned() else {
            return;
        };
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        let generation = self.generation;
        let cancel = self.cancel.clone();
        self.busy = true;
        self.error = None;
        let task_data = data.clone();
        let work = cx.background_executor().spawn(async move {
            task_data
                .session
                .heap_profile(kind.profile(), task_data.pid, offset, 100, &cancel)
        });
        cx.spawn(async move |this, cx| {
            let result = work.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(page) => {
                        let next = Arc::new(HeapDataset {
                            path: data.path.clone(),
                            session: data.session.clone(),
                            page,
                            pid: data.pid,
                            import_timing: data.import_timing,
                            _capture: data._capture.clone(),
                        });
                        if kind == AdvancedMemoryKind::Art {
                            this.memory_advanced.art = Some(next);
                        } else {
                            this.memory_advanced.native = Some(next);
                        }
                    }
                    Err(e) => this.error = Some(e.to_string().into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    pub(crate) fn advanced_memory_export_enabled(&self, kind: AdvancedMemoryKind) -> bool {
        !self.busy
            && !self.export_busy
            && if kind == AdvancedMemoryKind::Bitmap {
                self.memory_advanced.bitmap.is_some()
            } else {
                self.memory_advanced.heap(kind).is_some()
            }
    }
    pub(crate) fn export_advanced_memory(
        &mut self,
        kind: AdvancedMemoryKind,
        cx: &mut Context<Self>,
    ) {
        if !self.advanced_memory_export_enabled(kind) {
            return;
        }
        let heap = self.memory_advanced.heap(kind).cloned();
        let bitmap = self.memory_advanced.bitmap.clone();
        let directory = aps_platform::home_directory().unwrap_or_else(|_| std::env::temp_dir());
        let picker = cx.prompt_for_new_path(&directory, Some(kind.filename()));
        self.export_busy = true;
        self.export_generation += 1;
        let generation = self.export_generation;
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let target = this
                .update(cx, |this, cx| {
                    if this.export_generation != generation {
                        return None;
                    }
                    match chosen {
                        Ok(Ok(Some(path))) => Some(path),
                        _ => {
                            this.export_busy = false;
                            cx.notify();
                            None
                        }
                    }
                })
                .ok()
                .flatten();
            let Some(target) = target else {
                return;
            };
            let work = cx.background_executor().spawn(async move {
                if kind == AdvancedMemoryKind::Bitmap {
                    export_bitmap_bundle(
                        bitmap.ok_or("Bitmap session unavailable")?.as_ref(),
                        &target,
                    )
                } else {
                    let data = heap.ok_or("Heap trace unavailable")?;
                    let scratch = root().map_err(|_| "Temporary capture root unavailable")?;
                    reject_scratch_destination(Some(&scratch), &target)?;
                    reject_scratch_destination(
                        data._capture.as_ref().and_then(|c| c.path.parent()),
                        &target,
                    )?;
                    super::exports::write_verified_copy(
                        &data.path,
                        &target,
                        &data.page.source_digest.to_hex(),
                    )
                }
            });
            let result: Result<(), String> = work.await;
            this.update(cx, |this, cx| {
                if this.export_generation != generation {
                    return;
                }
                this.export_busy = false;
                match result {
                    Ok(()) => this.notice = Some(TextKey::MemoryLiveExported.into()),
                    Err(e) => this.error = Some(e.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    pub(super) fn render_advanced_heap(
        &mut self,
        kind: AdvancedMemoryKind,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let ready = self.advanced_memory_ready();
        let data = self.memory_advanced.heap(kind).cloned();
        let mut page = studio::page(colors)
            .id("memory-advanced-heap")
            .h_auto()
            .flex_1()
            .min_h_0()
            .child(studio::section_title(language.text(kind.title()), colors))
            .child(self.advanced_actions(kind, ready, colors, cx))
            .child(studio::supporting(
                language.text(if kind == AdvancedMemoryKind::Art {
                    TextKey::MemoryLiveArtBoundary
                } else {
                    TextKey::MemoryLiveNativeBoundary
                }),
                colors,
            ));
        if let Some(data) = data {
            page = page.child(studio::supporting(
                format!("SHA-256: {}", data.page.source_digest.to_hex()),
                colors,
            ));
            if let Some(capture) = &data._capture {
                page = page
                    .child(studio::supporting(
                        self.language.format(
                            TextKey::MemoryLiveTarget,
                            &[
                                ("process", &capture.target.process_name),
                                ("pid", &capture.target.pid),
                                ("serial", &capture.target.serial),
                            ],
                        ),
                        colors,
                    ))
                    .child(studio::supporting(
                        format!(
                            "boot {} · start {} · API {}",
                            capture.boot_id, capture.start_ticks, capture.sdk
                        ),
                        colors,
                    ));
            }
            let mut table = div()
                .id("heap-profile-rows")
                .flex_1()
                .min_h_0()
                .overflow_y_scrollbar()
                .flex()
                .flex_col()
                .gap_1();
            table = table.child(studio::supporting(
                language.text(TextKey::MemoryLiveHeapColumns),
                colors,
            ));
            if data.page.rows.is_empty() {
                table = table.child(studio::supporting(
                    language.text(TextKey::MemoryLiveNoSamples),
                    colors,
                ));
            }
            for row in &data.page.rows {
                table=table.child(studio::card(colors).p(px(6.)).child(crate::studio::selectable(format!(
                    "PID {} · UPID {} · #{} · rows {} · alloc {} / {} B · net {} / {} B\n{}",
                    row.pid,row.upid,row.callsite_id,row.sample_rows,row.allocated_count,row.allocated_bytes,
                    row.net_count,row.net_bytes,row.leaf_frame.as_deref().unwrap_or("[unsymbolized]")))));
            }
            let offset = data.page.offset;
            page = page.child(table).child(
                div()
                    .flex()
                    .gap_1()
                    .child(
                        toolbar_button(
                            "heap-profile-prev",
                            language.text(TextKey::CommonPrevious),
                            !self.busy && offset > 0,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.page_advanced_memory(kind, offset.saturating_sub(100), cx)
                        })),
                    )
                    .child(
                        toolbar_button(
                            "heap-profile-next",
                            language.text(TextKey::CommonNext),
                            !self.busy && data.page.has_more,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.page_advanced_memory(kind, offset + 100, cx)
                        })),
                    ),
            );
        } else {
            page = page.child(studio::supporting(
                language.text(TextKey::MemoryLiveNoSamples),
                colors,
            ));
        }
        page.into_any_element()
    }
    fn advanced_actions(
        &self,
        kind: AdvancedMemoryKind,
        ready: bool,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> Div {
        div()
            .flex()
            .flex_wrap()
            .gap_1()
            .child(
                toolbar_button(
                    "advanced-memory-capture",
                    self.language.text(TextKey::MemoryLiveCapture),
                    ready,
                    false,
                    colors,
                )
                .tooltip(self.advanced_target_text())
                .on_click(
                    cx.listener(move |this, _, _, cx| this.capture_advanced_memory(kind, cx)),
                ),
            )
            .child(
                toolbar_button(
                    "advanced-memory-import",
                    self.language.text(TextKey::MemoryLiveImport),
                    !self.busy && !self.export_busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.import_advanced_memory(kind, cx))),
            )
            .child(
                toolbar_button(
                    "advanced-memory-export",
                    self.language.text(TextKey::MemoryLiveExport),
                    self.advanced_memory_export_enabled(kind),
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.export_advanced_memory(kind, cx))),
            )
            .when(self.busy, |row| {
                row.child(
                    toolbar_button(
                        "advanced-memory-cancel",
                        self.language.text(TextKey::ImportCancelOperation),
                        true,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
                )
            })
    }
    pub(super) fn render_bitmap_dump(
        &mut self,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let mut page = studio::page(colors)
            .id("memory-bitmap-image-dump")
            .h_auto()
            .flex_1()
            .min_h_0()
            .child(studio::section_title(
                self.language.text(TextKey::MemoryAppBitmapDumpView),
                colors,
            ))
            .child(self.advanced_actions(
                AdvancedMemoryKind::Bitmap,
                self.advanced_memory_ready(),
                colors,
                cx,
            ))
            .child(studio::supporting(
                self.language.text(TextKey::MemoryLiveBitmapBoundary),
                colors,
            ));
        let Some(data) = self.memory_advanced.bitmap.clone() else {
            return page.into_any_element();
        };
        let dump = &data.dump;
        if let Some(capture) = &data._capture {
            page = page.child(studio::supporting(
                self.language.format(
                    TextKey::MemoryLiveTarget,
                    &[
                        ("process", &capture.target.process_name),
                        ("pid", &capture.target.pid),
                        ("serial", &capture.target.serial),
                    ],
                ),
                colors,
            ));
        }
        let bytes: u64 = dump.images.iter().map(|i| i.png_bytes).sum();
        page = page.child(studio::supporting(
            self.language.format(
                TextKey::MemoryLiveBitmapSummary,
                &[
                    ("recorded", &dump.recorded),
                    ("discovered", &dump.discovered),
                    ("images", &dump.images.len()),
                    ("omitted", &dump.omitted),
                    ("bytes", &bytes),
                ],
            ),
            colors,
        ));
        let mut grid = div()
            .id("bitmap-images-grid")
            .w_full()
            .flex()
            .flex_wrap()
            .content_start()
            .items_start()
            .flex_shrink_0()
            .gap(px(8.))
            .pr(px(12.));
        for (index, image) in dump.images.iter().enumerate() {
            let selected = self.memory_advanced.selected_image == index;
            grid = grid.child(
                Button::new(("bitmap-image", index))
                    .ghost()
                    .selected(selected)
                    .w(px(164.))
                    .h(px(166.))
                    .flex_shrink_0()
                    .p(px(6.))
                    .rounded(px(4.))
                    .border_1()
                    .border_color(if selected {
                        colors.accent
                    } else {
                        colors.border
                    })
                    .bg(if selected {
                        colors.selected_row
                    } else {
                        colors.panel
                    })
                    .text_color(colors.text)
                    .accessibility_label(format!(
                        "#{}  {}×{}  {} B",
                        image.index, image.width, image.height, image.png_bytes
                    ))
                    .child(
                        div()
                            .size_full()
                            .flex()
                            .flex_col()
                            .gap(px(4.))
                            .child(
                                div()
                                    .relative()
                                    .w_full()
                                    .h(px(112.))
                                    .flex_shrink_0()
                                    .overflow_hidden()
                                    .bg(colors.field)
                                    .child(
                                        img(image.path.clone())
                                            .absolute()
                                            .inset_0()
                                            .object_fit(ObjectFit::Contain)
                                            .size_full(),
                                    ),
                            )
                            .child(
                                div()
                                    .text_size(px(studio::FONT_SUPPORTING))
                                    .text_ellipsis()
                                    .child(format!(
                                        "#{}  {}×{}",
                                        image.index, image.width, image.height
                                    )),
                            )
                            .child(
                                div()
                                    .text_size(px(studio::FONT_SUPPORTING))
                                    .text_color(colors.secondary_text)
                                    .text_ellipsis()
                                    .child(format!("{} B", image.png_bytes)),
                            ),
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.memory_advanced.selected_image = index;
                        cx.notify();
                    })),
            );
        }
        let selected_image = dump.images.get(self.memory_advanced.selected_image);
        let mut image_panel = div()
            .id("bitmap-images-panel")
            .flex()
            .flex_col()
            .flex_1()
            .min_w_0()
            .min_h_0()
            .gap_1();
        if let Some(image) = selected_image {
            image_panel = image_panel.child(
                div()
                    .id("bitmap-image-metadata")
                    .w_full()
                    .flex_shrink_0()
                    .overflow_x_scrollbar()
                    .child(studio::supporting(
                        format!("{} · SHA-256 {}", image.array_id, image.sha256),
                        colors,
                    )),
            );
        }
        image_panel = image_panel.child(
            div()
                .id("bitmap-images-list")
                .flex_1()
                .h_full()
                .min_w_0()
                .min_h_0()
                .overflow_y_scrollbar()
                .child(grid),
        );
        let mut preview = div()
            .id("bitmap-image-preview")
            .w(px(320.))
            .max_w(relative(0.4))
            .flex_shrink_0()
            .min_w_0()
            .min_h_0()
            .flex()
            .flex_col()
            .gap_1();
        if let Some(image) = selected_image {
            preview = preview.child(
                div()
                    .relative()
                    .flex_1()
                    .w_full()
                    .min_h_0()
                    .overflow_hidden()
                    .child(
                        img(image.path.clone())
                            .absolute()
                            .inset_0()
                            .object_fit(ObjectFit::Contain)
                            .size_full(),
                    ),
            );
        }
        page.child(
            div()
                .flex()
                .flex_1()
                .min_w_0()
                .min_h_0()
                .overflow_hidden()
                .gap_2()
                .child(image_panel)
                .child(preview),
        )
        .into_any_element()
    }
}

fn reject_scratch_destination(scratch: Option<&Path>, target: &Path) -> Result<(), String> {
    if let Some(scratch) = scratch {
        let source = scratch
            .canonicalize()
            .map_err(|_| "Scratch storage unavailable")?;
        let destination = target
            .parent()
            .ok_or("Export directory unavailable")?
            .canonicalize()
            .map_err(|_| "Export directory unavailable")?;
        if destination.starts_with(source) {
            return Err("Choose an export location outside temporary capture storage".into());
        }
    }
    Ok(())
}
fn export_bitmap_bundle(data: &BitmapDataset, target: &Path) -> Result<(), String> {
    let scratch = root().map_err(|_| "Temporary capture root unavailable")?;
    reject_scratch_destination(Some(&scratch), target)?;
    reject_scratch_destination(Some(data._images.path()), target)?;
    reject_scratch_destination(
        data._capture
            .as_ref()
            .and_then(|capture| capture.path.parent()),
        target,
    )?;
    if !target.is_absolute() {
        return Err("Select an absolute export path".into());
    }
    let mut options = std::fs::OpenOptions::new();
    options.create_new(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let file = options
        .open(target)
        .map_err(|_| "Export exists or cannot be created; choose a new name")?;
    let result = (|| -> Result<(), String> {
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored);
        let images=data.dump.images.iter().map(|i|serde_json::json!({"file":format!("images/bitmap-{}.png",i.index),"width":i.width,"height":i.height,"pngBytes":i.png_bytes,"sha256":i.sha256,"arrayId":i.array_id})).collect::<Vec<_>>();
        let target_identity=data._capture.as_ref().map(|capture|serde_json::json!({"serial":capture.target.serial,"pid":capture.target.pid,"package":capture.target.package_name,"process":capture.target.process_name,"bootId":capture.boot_id,"startTicks":capture.start_ticks}));
        let manifest=serde_json::to_vec_pretty(&serde_json::json!({"captureTarget":target_identity,"format":"aps-android-bitmap-dump-v1","sourceDigest":data.dump.source_digest,"recorded":data.dump.recorded,"discovered":data.dump.discovered,"omitted":data.dump.omitted,"images":images})).map_err(|_|"Cannot encode bitmap manifest")?;
        zip.start_file("manifest.json", options)
            .map_err(|_| "Cannot start bitmap manifest")?;
        zip.write_all(&manifest)
            .map_err(|_| "Cannot write bitmap manifest")?;
        for image in &data.dump.images {
            let meta =
                std::fs::symlink_metadata(&image.path).map_err(|_| "Bitmap image unavailable")?;
            if meta.is_symlink()
                || !meta.is_file()
                || meta.len() != image.png_bytes
                || SourceDigest::hash_file(&image.path, 32 * 1024 * 1024)
                    .map_err(|_| "Cannot verify bitmap image")?
                    .to_hex()
                    != image.sha256
            {
                return Err("Bitmap image changed; export aborted".into());
            }
            zip.start_file(format!("images/bitmap-{}.png", image.index), options)
                .map_err(|_| "Cannot start bitmap image export")?;
            let mut source =
                std::fs::File::open(&image.path).map_err(|_| "Cannot open bitmap image")?;
            if std::io::copy(&mut source, &mut zip).map_err(|_| "Cannot copy bitmap image")?
                != image.png_bytes
            {
                return Err("Bitmap image changed during export".into());
            }
            if SourceDigest::hash_file(&image.path, 32 * 1024 * 1024)
                .map_err(|_| "Cannot verify bitmap image")?
                .to_hex()
                != image.sha256
            {
                return Err("Bitmap image changed during export".into());
            }
        }
        zip.finish()
            .map_err(|_| "Cannot finalize bitmap bundle")?
            .sync_all()
            .map_err(|_| "Cannot persist bitmap bundle")?;
        Ok(())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(target);
    }
    result
}

#[cfg(test)]
mod bitmap_grid_tests {
    fn bitmap_view_source() -> &'static str {
        include_str!("advanced.rs")
            .split("pub(super) fn render_bitmap_dump(")
            .nth(1)
            .unwrap()
            .split("fn reject_scratch_destination(")
            .next()
            .unwrap()
    }

    #[::core::prelude::v1::test]
    fn bitmap_grid_wraps_inside_a_bounded_vertical_scroll_viewport() {
        let source = bitmap_view_source();
        let grid = source
            .split("let mut grid =")
            .nth(1)
            .unwrap()
            .split("for (index, image)")
            .next()
            .unwrap();
        assert!(grid.contains(".flex_wrap()"));
        assert!(grid.contains(".content_start()"));
        assert!(grid.contains(".flex_shrink_0()"));
        let viewport = source
            .split(".id(\"bitmap-images-list\")")
            .nth(1)
            .unwrap()
            .split(".child(preview)")
            .next()
            .unwrap();
        for constraint in [
            ".flex_1()",
            ".h_full()",
            ".min_w_0()",
            ".min_h_0()",
            ".overflow_y_scrollbar()",
            ".child(grid)",
        ] {
            assert!(
                viewport.contains(constraint),
                "Missing grid constraint: {constraint}"
            );
        }
    }

    #[::core::prelude::v1::test]
    fn bitmap_tiles_keep_fixed_thumbnails_and_selected_image_preview() {
        let source = bitmap_view_source();
        let tile = source
            .split("Button::new((\"bitmap-image\", index))")
            .nth(1)
            .unwrap()
            .split("let mut preview =")
            .next()
            .unwrap();
        for constraint in [
            ".w(px(164.))",
            ".h(px(166.))",
            ".h(px(112.))",
            ".flex_shrink_0()",
            ".relative()",
            ".absolute()",
            ".inset_0()",
            ".object_fit(ObjectFit::Contain)",
            ".selected(selected)",
            ".accessibility_label(",
            "this.memory_advanced.selected_image = index;",
        ] {
            assert!(
                tile.contains(constraint),
                "Missing tile behavior: {constraint}"
            );
        }
        let panel = source.split("let mut image_panel =").nth(1).unwrap();
        assert!(
            panel.find("bitmap-image-metadata").unwrap()
                < panel.find("bitmap-images-list").unwrap()
        );
        let preview = source.split("let mut preview =").nth(1).unwrap();
        assert!(!preview.contains("bitmap-image-metadata"));
        assert!(source.contains("dump.images.get(self.memory_advanced.selected_image)"));
        assert!(source.contains("image.array_id, image.sha256"));
        for constraint in [
            ".relative()",
            ".flex_1()",
            ".absolute()",
            ".inset_0()",
            ".object_fit(ObjectFit::Contain)",
            ".size_full()",
        ] {
            assert!(
                preview.contains(constraint),
                "Missing preview constraint: {constraint}"
            );
        }
    }
}

#[cfg(test)]
mod import_timing_tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn advanced_views_do_not_export_a_retained_java_heap_as_ai_evidence() {
        for language in [Language::English, Language::SimplifiedChinese] {
            let mut workspace = ImportWorkspace::new(Destination::Memory, language);
            for view in [
                MemoryViewMode::NativeHeap,
                MemoryViewMode::SampledArt,
                MemoryViewMode::BitmapDump,
            ] {
                workspace.memory_state.view_mode = view;
                assert_eq!(
                    workspace.memory_analysis_request().err().as_deref(),
                    Some(language.text(TextKey::MemoryLiveAiUnavailable))
                );
            }
        }
    }

    #[::core::prelude::v1::test]
    fn advanced_views_never_reuse_a_java_snapshot_import_duration() {
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        workspace.import_duration = Some(Duration::from_secs(9));
        for view in [
            MemoryViewMode::NativeHeap,
            MemoryViewMode::SampledArt,
            MemoryViewMode::BitmapDump,
        ] {
            workspace.memory_state.view_mode = view;
            assert_eq!(workspace.advanced_memory_import_duration_text(), None);
        }
    }

    #[::core::prelude::v1::test]
    fn advanced_import_clocks_are_post_selection_and_preserved_on_paging() {
        let source = include_str!("advanced.rs");
        let open = source
            .split("fn open_advanced_memory(")
            .nth(1)
            .unwrap()
            .split("fn page_advanced_memory(")
            .next()
            .unwrap();
        let open: String = open.chars().filter(|c| !c.is_whitespace()).collect();
        assert!(
            open.find("letstarted=Instant::now();").unwrap()
                < open.find("background_executor()").unwrap()
        );
        assert!(open.contains("load_bitmap(&path,None,started,&cancel)"));
        assert!(open.contains("load_trace(path,kind,None,None,started,&cancel)"));
        let page = source
            .split("fn page_advanced_memory(")
            .nth(1)
            .unwrap()
            .split("fn advanced_memory_export_enabled(")
            .next()
            .unwrap();
        let page: String = page.chars().filter(|c| !c.is_whitespace()).collect();
        assert!(page.contains("import_timing:data.import_timing"));
        assert!(!page.contains("ImportTiming::completed"));
        let capture = source
            .split("fn capture_advanced_memory(")
            .nth(1)
            .unwrap()
            .split("fn finish_advanced_memory(")
            .next()
            .unwrap();
        let capture: String = capture.chars().filter(|c| !c.is_whitespace()).collect();
        assert!(
            capture.find("capture_bitmap_heap(").unwrap() < capture.find("load_bitmap(").unwrap()
        );
        assert!(capture.find("capture_trace(").unwrap() < capture.find("load_trace(").unwrap());
        assert!(capture.contains("Some(owner),Instant::now(),&cancel"));
    }
}
