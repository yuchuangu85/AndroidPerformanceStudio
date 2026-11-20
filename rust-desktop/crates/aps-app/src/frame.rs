//! Frame Analyzer workspace: Compose frame-profiler parity for the Frame route.
//!
//! Offline gfxinfo FrameStats imports, bounded online captures, Perfetto
//! FrameTimeline traces, filtering, export, AI preflight and the external
//! Perfetto viewer live here. No platform-jank signal is ever inferred from a
//! deadline miss, and no trace bytes leave the machine without the existing AI
//! approval flow.

mod ai;
mod analysis_json;
mod artifact;
mod capture;
mod detail_data;
mod export;
mod mcp_receipt;
mod model;
mod optimize;
mod page;
mod perfetto;
mod probe_compare;
mod probe_receipt;
mod probe_review;
#[cfg(test)]
mod probe_tests;
pub(crate) use optimize::{Optimization, ReviewDiff};
mod sessions;
mod trace_patch;
pub(crate) use probe_review::{ProbeDraft, show_optimization, show_probe_draft};

use crate::import_timing::ImportTiming;
use crate::notifications::{NoticeSnapshot, NoticeSource};
use crate::perfetto_viewer::ViewerServer;
use aps_adb::{AdbDevice, AndroidApplicationProcess};
use aps_ai::AnalysisRequest;
use aps_contracts::SourceDigest;
use aps_frame::analysis::AnalyzedFrame;
use aps_frame::{
    FrameAnalysisResult, FrameCaptureSession, FrameJankAnalyzer, FrameSample, FrameSource,
    FrameSourceCapabilities, FrameViewFilter,
};
use aps_platform::CancellationToken;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{prelude::*, *};
use std::{
    io::Read,
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const POLL_INTERVAL: Duration = Duration::from_millis(250);
const DEFAULT_FRAME_TIMELINE_CAPTURE_MILLIS: u64 = 10_000;
const MAX_IMPORT_BYTES: u64 = 512 * 1024 * 1024;

struct FrameTimelineOperation {
    generation: u64,
    serial: String,
    process: capture::FrameProcessOption,
    cancel: CancellationToken,
}

impl FrameTimelineOperation {
    fn matches_target(
        &self,
        serial: Option<&str>,
        process: Option<&capture::FrameProcessOption>,
        online: bool,
    ) -> bool {
        online && serial == Some(self.serial.as_str()) && process == Some(&self.process)
    }

    fn can_publish(&self, generation: u64) -> bool {
        self.generation == generation && !self.cancel.is_cancelled()
    }
}

/// Request from the Frame Analyzer to correlate a frame with the Layout Inspector.
pub(crate) struct FrameLayoutInspectionRequest {
    pub device_serial: Option<String>,
}

impl EventEmitter<FrameLayoutInspectionRequest> for FrameWorkspace {}
impl EventEmitter<crate::evidence::AnalysisRequested> for FrameWorkspace {}

#[derive(Clone)]
pub(crate) enum FrameProbeAction {
    Locate(String),
    Optimize(String),
    Plan,
    Patch,
}
impl EventEmitter<FrameProbeAction> for FrameWorkspace {}
fn can_probe(frame: &AnalyzedFrame) -> bool {
    frame.sample.resolved_duration_ns().is_some_and(|ns| ns > 0)
        && (frame.deadline_verdict == aps_frame::FrameDeadlineVerdict::Missed
            || frame.sample.platform_jank == Some(true))
}

pub struct FrameWorkspace {
    pub(in crate::frame) language: Language,
    pub(in crate::frame) data_dir: Option<PathBuf>,
    pub(in crate::frame) session_store: sessions::FrameSessionStore,
    pub(in crate::frame) devices: Vec<capture::FrameDeviceOption>,
    pub(in crate::frame) selected_device_serial: Option<String>,
    pub(in crate::frame) is_refreshing_devices: bool,
    pub(in crate::frame) processes: Vec<capture::FrameProcessOption>,
    pub(in crate::frame) selected_process_pid: Option<i32>,
    pub(in crate::frame) pending_process_pid: Option<i32>,
    pub(in crate::frame) analysis: Option<Arc<FrameAnalysisResult>>,
    // Set only when File > Open parsed and verified these exact Frame report bytes.
    // A live capture, associated trace, or history label cannot supply this identity.
    review_file_digest: Option<SourceDigest>,
    pub(in crate::frame) filtered_analysis: Option<Arc<FrameAnalysisResult>>,
    pub(in crate::frame) imported_file_name: Option<String>,
    pub(in crate::frame) import_timing: ImportTiming,
    pub(in crate::frame) observed_refresh_rates_hz: Vec<f64>,
    pub(in crate::frame) selected_frame_id: Option<u64>,
    pub(in crate::frame) perfetto_trace_file: Option<PathBuf>,
    // Viewer links are navigation hints; only an analyzed source may supply a
    // frame timestamp. The linked path never changes session provenance.
    viewer_only_trace: bool,
    pub(in crate::frame) artifact: Option<artifact::FrameArtifact>,
    pub(in crate::frame) is_loading: bool,
    pub(in crate::frame) is_capturing: bool,
    pub(in crate::frame) operation_status: Option<model::FrameOperationStatus>,
    pub(in crate::frame) warnings: Vec<String>,
    pub(in crate::frame) error: Option<String>,
    pub(in crate::frame) view_filter: FrameViewFilter,
    pub(in crate::frame) source_filter: Option<FrameSource>,
    pub(in crate::frame) online_capture: Option<capture::OnlineFrameCapture>,
    pub(in crate::frame) online_frames: Vec<FrameSample>,
    pub(in crate::frame) current_session: Option<FrameCaptureSession>,
    pub(in crate::frame) viewer: ViewerServer,
    pub(in crate::frame) ui: page::PageUi,
    pub(in crate::frame) language_generation: u64,
    pub(in crate::frame) import_generation: u64,
    pub(in crate::frame) capture_generation: u64,
    timeline_operation: Option<FrameTimelineOperation>,
    mcp_publication: Option<mcp_receipt::Publication>,
    comparison: probe_compare::Comparison,
    probe_labels: std::collections::BTreeMap<String, String>,
}

impl FrameWorkspace {
    pub fn new(
        language: Language,
        settings_path: Option<&Path>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let data_dir = settings_path
            .and_then(Path::parent)
            .map(|root| root.join("frame-profiler"));
        let mut this = Self {
            language,
            session_store: sessions::FrameSessionStore::new(
                data_dir.clone().unwrap_or_default().join("sessions"),
            ),
            data_dir,
            devices: Vec::new(),
            selected_device_serial: None,
            is_refreshing_devices: false,
            processes: Vec::new(),
            selected_process_pid: None,
            pending_process_pid: None,
            analysis: None,
            filtered_analysis: None,
            review_file_digest: None,
            imported_file_name: None,
            import_timing: ImportTiming::default(),
            observed_refresh_rates_hz: Vec::new(),
            selected_frame_id: None,
            perfetto_trace_file: None,
            viewer_only_trace: false,
            artifact: None,
            is_loading: false,
            is_capturing: false,
            operation_status: None,
            warnings: Vec::new(),
            error: None,
            view_filter: FrameViewFilter::All,
            source_filter: None,
            online_capture: None,
            online_frames: Vec::new(),
            current_session: None,
            viewer: ViewerServer::new(9418),
            ui: page::PageUi::new(window, cx),
            language_generation: 0,
            import_generation: 0,
            capture_generation: 0,
            timeline_operation: None,
            mcp_publication: None,
            comparison: Default::default(),
            probe_labels: Default::default(),
        };
        this
    }

    pub fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.language = language;
        self.language_generation += 1;
        self.ui.set_language(language, window, cx);
        cx.notify();
    }

    pub(crate) fn page_language(&self) -> Language {
        self.language
    }

    pub(crate) fn selected_shared_target(&self) -> (Option<&str>, Option<u32>) {
        (
            self.selected_device_serial.as_deref(),
            self.selected_process_pid.map(|pid| pid as u32),
        )
    }

    pub(crate) fn apply_shared_target_lists(
        &mut self,
        devices: &[AdbDevice],
        processes: &[AndroidApplicationProcess],
        selected_serial: Option<&str>,
        selected_pid: Option<u32>,
        cx: &mut Context<Self>,
    ) {
        self.devices = devices
            .iter()
            .map(|device| capture::FrameDeviceOption {
                serial: device.serial.clone(),
                name: device.display_name(),
                online: device.is_online(),
            })
            .collect();
        self.processes = processes
            .iter()
            .map(|process| capture::FrameProcessOption {
                pid: process.pid as i32,
                name: process.name.clone(),
                package_name: process.package_name.clone(),
            })
            .collect();
        self.selected_device_serial = selected_serial.map(str::to_owned);
        self.selected_process_pid = selected_pid.map(|pid| pid as i32);
        self.pending_process_pid = None;
        self.is_refreshing_devices = false;
        // A target list update is not a successful retry of a failed capture.
        self.reconcile_frame_timeline_target(cx);
        cx.notify();
    }

    pub(crate) fn set_shared_target(
        &mut self,
        serial: Option<String>,
        pid: Option<u32>,
        cx: &mut Context<Self>,
    ) {
        self.selected_device_serial = serial;
        self.selected_process_pid = pid.map(|value| value as i32);
        self.pending_process_pid = None;
        self.reconcile_frame_timeline_target(cx);
        cx.notify();
    }

    // ---------------- device / process discovery ----------------

    pub(crate) fn refresh_devices(&mut self, cx: &mut Context<Self>) {
        if self.is_capturing || self.is_loading || self.is_refreshing_devices {
            return;
        }
        self.is_refreshing_devices = true;
        self.error = None;
        let task = cx.background_executor().spawn(capture::list_devices());
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.is_refreshing_devices = false;
                match result {
                    Ok(devices) => {
                        let retained = this.selected_device_serial.clone().filter(|serial| {
                            devices
                                .iter()
                                .any(|device| &device.serial == serial && device.online)
                        });
                        let automatic = retained.clone().or_else(|| {
                            let online: Vec<&capture::FrameDeviceOption> =
                                devices.iter().filter(|device| device.online).collect();
                            (online.len() == 1).then(|| online[0].serial.clone())
                        });
                        this.devices = devices;
                        if retained.is_none() {
                            this.selected_device_serial = None;
                            this.processes.clear();
                            this.selected_process_pid = None;
                        }
                        if let Some(serial) = automatic {
                            this.select_device(serial, cx);
                        }
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn select_device(&mut self, serial: String, cx: &mut Context<Self>) {
        if self.is_capturing {
            return;
        }
        self.selected_device_serial = Some(serial.clone());
        self.processes.clear();
        self.selected_process_pid = None;
        self.reconcile_frame_timeline_target(cx);
        self.is_refreshing_devices = true;
        self.error = None;
        let generation = self.capture_generation;
        let task_serial = serial.clone();
        let task = cx
            .background_executor()
            .spawn(async move { capture::list_processes(&task_serial).await });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.capture_generation != generation
                    || this.selected_device_serial.as_deref() != Some(serial.as_str())
                {
                    return;
                }
                this.is_refreshing_devices = false;
                match result {
                    Ok(processes) => {
                        this.processes = processes;
                        if let Some(pid) = this.pending_process_pid {
                            if this.processes.iter().any(|process| process.pid == pid) {
                                this.selected_process_pid = Some(pid);
                                this.pending_process_pid = None;
                            }
                        } else if this.selected_process_pid.is_none() && this.processes.len() == 1 {
                            this.selected_process_pid = this.processes[0].pid.into();
                        }
                        this.error = None;
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn select_process(&mut self, pid: i32, cx: &mut Context<Self>) {
        if self.is_capturing {
            return;
        }
        self.pending_process_pid = Some(pid);
        if self.processes.iter().any(|process| process.pid == pid) {
            self.selected_process_pid = Some(pid);
            self.pending_process_pid = None;
            self.reconcile_frame_timeline_target(cx);
            self.error = None;
            cx.notify();
        }
    }

    fn selected_process(&self) -> Option<&capture::FrameProcessOption> {
        self.selected_process_pid
            .and_then(|pid| self.processes.iter().find(|process| process.pid == pid))
    }

    // ---------------- imports ----------------

    pub(crate) fn pick_import_frame_stats(&mut self, cx: &mut Context<Self>) {
        self.pick_file(
            TextKey::FrameImportGfxinfoFramestats,
            cx,
            |this, path, cx| this.import_frame_stats(path, cx),
        );
    }

    /// Debug-only verification seam: opens an exported frame-analysis.json on
    /// launch without driving the native file picker.
    #[cfg(debug_assertions)]
    pub(crate) fn import_debug_analysis_json(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        self.import_frame_stats(path, cx);
    }

    pub(crate) fn pick_import_perfetto(&mut self, cx: &mut Context<Self>) {
        self.pick_file(TextKey::FrameImportedFrames, cx, |this, path, cx| {
            this.import_perfetto_trace(path, cx)
        });
    }

    pub(crate) fn pick_associate_perfetto(&mut self, cx: &mut Context<Self>) {
        self.pick_file(
            TextKey::FrameAssociatePerfettoTrace,
            cx,
            |this, path, cx| this.associate_perfetto_trace(path, cx),
        );
    }

    fn pick_file(
        &mut self,
        prompt: TextKey,
        cx: &mut Context<Self>,
        on_selected: impl FnOnce(&mut Self, PathBuf, &mut Context<Self>) + 'static,
    ) {
        if self.is_capturing || self.is_loading {
            return;
        }
        let dialog = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(self.language.text(prompt).into()),
        });
        let mut on_selected = Some(on_selected);
        cx.spawn(async move |this, cx| {
            let result = dialog.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(Some(paths))) if paths.len() == 1 => {
                    if let Some(callback) = on_selected.take() {
                        callback(this, paths[0].clone(), cx);
                    }
                }
                Ok(Err(error)) => this.error = Some(format!("{error}")),
                _ => {}
            })
            .ok();
        })
        .detach();
    }

    fn import_frame_stats(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        if self.is_capturing || self.is_loading {
            return;
        }
        let started = Instant::now();
        self.is_loading = true;
        self.import_generation += 1;
        let generation = self.import_generation;
        self.error = None;
        self.operation_status = None;
        cx.notify();
        let session_id = new_id("frame-import");
        let task = cx
            .background_executor()
            .spawn(async move { import_frame_stats_blocking(path, session_id) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.import_generation != generation {
                    return;
                }
                this.is_loading = false;
                match result {
                    Ok(loaded) => {
                        this.imported_file_name = Some(loaded.file_name);
                        this.perfetto_trace_file = None;
                        this.viewer_only_trace = false;
                        this.artifact = None;
                        this.current_session = Some(loaded.session.clone());
                        this.apply_analysis(loaded.analysis, cx);
                        this.review_file_digest = loaded.review_file_digest;
                        this.import_timing = ImportTiming::completed(started);
                        this.operation_status = Some(model::FrameOperationStatus::ImportedFrames {
                            frame_count: this
                                .analysis
                                .as_ref()
                                .map_or(0, |analysis| analysis.frames.len()),
                        });
                        this.warnings = loaded.warnings;
                        this.error = None;
                        this.persist_session(loaded.session, loaded.frames, None, cx);
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn import_perfetto_trace(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        if self.is_capturing || self.is_loading {
            return;
        }
        let started = Instant::now();
        self.is_loading = true;
        self.import_generation += 1;
        let generation = self.import_generation;
        self.error = None;
        self.operation_status = None;
        cx.notify();
        let data_dir = self.data_dir.clone();
        let task = cx
            .background_executor()
            .spawn(async move { import_perfetto_blocking(path, data_dir) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.import_generation != generation {
                    return;
                }
                this.is_loading = false;
                match result {
                    Ok(loaded) => {
                        this.imported_file_name = Some(loaded.file_name);
                        this.perfetto_trace_file = loaded.trace_path.clone();
                        this.viewer_only_trace = false;
                        this.probe_labels.clear();
                        this.artifact = loaded.artifact.clone();
                        this.current_session = Some(loaded.session.clone());
                        this.apply_analysis(loaded.analysis, cx);
                        this.import_timing = ImportTiming::completed(started);
                        this.operation_status = Some(model::FrameOperationStatus::ImportedFrames {
                            frame_count: this
                                .analysis
                                .as_ref()
                                .map_or(0, |analysis| analysis.frames.len()),
                        });
                        this.warnings = loaded.warnings;
                        this.error = None;
                        this.persist_session(
                            loaded.session,
                            loaded.frames,
                            loaded.trace_digest,
                            cx,
                        );
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn associate_perfetto_trace(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        if self.is_loading
            || self.is_capturing
            || self.analysis.is_none()
            || self.current_session.is_none()
        {
            return;
        }
        self.mcp_publication = None; // A viewer association is not capture provenance.
        self.perfetto_trace_file = Some(path);
        self.viewer_only_trace = true;
        self.error = None;
        cx.notify();
    }

    // ---------------- analysis/state ----------------

    fn apply_analysis(&mut self, analysis: FrameAnalysisResult, cx: &mut Context<Self>) {
        // Captured/replaced analysis must never inherit an older file-import
        // cost. Import callers record their own duration after publication.
        self.mcp_publication = None;
        self.import_timing.clear();
        self.review_file_digest = None;
        self.selected_frame_id = analysis.frames.first().map(|frame| frame.sample.frame_id);
        self.observed_refresh_rates_hz = observed_refresh_rates(&analysis);
        self.analysis = Some(Arc::new(analysis));
        self.rebuild_filtered();
        cx.notify();
    }

    pub(in crate::frame) fn rebuild_filtered(&mut self) {
        self.filtered_analysis = self.analysis.as_ref().map(|analysis| {
            Arc::new(model::filtered(
                analysis.as_ref(),
                self.source_filter,
                self.view_filter,
            ))
        });
    }

    pub(in crate::frame) fn selected_frame(&self) -> Option<&AnalyzedFrame> {
        let analysis = self
            .filtered_analysis
            .as_deref()
            .or(self.analysis.as_deref())?;
        model::selected_frame(analysis, self.selected_frame_id)
    }

    pub(in crate::frame) fn select_frame(&mut self, frame_id: u64, cx: &mut Context<Self>) {
        if self.filtered_analysis.as_deref().is_some_and(|analysis| {
            analysis
                .frames
                .iter()
                .any(|frame| frame.sample.frame_id == frame_id)
        }) {
            self.selected_frame_id = Some(frame_id);
            cx.notify();
        }
    }

    pub(in crate::frame) fn set_view_filter(
        &mut self,
        filter: FrameViewFilter,
        cx: &mut Context<Self>,
    ) {
        self.view_filter = filter;
        self.rebuild_filtered();
        self.selected_frame_id = self
            .filtered_analysis
            .as_deref()
            .and_then(|analysis| analysis.frames.first())
            .map(|frame| frame.sample.frame_id);
        cx.notify();
    }

    pub(in crate::frame) fn set_source_filter(
        &mut self,
        source: Option<FrameSource>,
        cx: &mut Context<Self>,
    ) {
        self.source_filter = source;
        self.rebuild_filtered();
        self.selected_frame_id = self
            .filtered_analysis
            .as_deref()
            .and_then(|analysis| analysis.frames.first())
            .map(|frame| frame.sample.frame_id);
        cx.notify();
    }

    pub(in crate::frame) fn previous_frame(&mut self, cx: &mut Context<Self>) {
        if let Some(analysis) = self.filtered_analysis.as_deref()
            && let Some(id) = model::previous_frame_id(analysis, self.selected_frame_id)
        {
            self.selected_frame_id = Some(id);
            cx.notify();
        }
    }

    pub(in crate::frame) fn next_frame(&mut self, cx: &mut Context<Self>) {
        if let Some(analysis) = self.filtered_analysis.as_deref()
            && let Some(id) = model::next_frame_id(analysis, self.selected_frame_id)
        {
            self.selected_frame_id = Some(id);
            cx.notify();
        }
    }

    pub(in crate::frame) fn previous_cluster(&mut self, cx: &mut Context<Self>) {
        if let Some(analysis) = self.filtered_analysis.as_deref()
            && let Some(id) = model::previous_cluster_frame_id(analysis, self.selected_frame_id)
        {
            self.selected_frame_id = Some(id);
            cx.notify();
        }
    }

    pub(in crate::frame) fn next_cluster(&mut self, cx: &mut Context<Self>) {
        if let Some(analysis) = self.filtered_analysis.as_deref()
            && let Some(id) = model::next_cluster_frame_id(analysis, self.selected_frame_id)
        {
            self.selected_frame_id = Some(id);
            cx.notify();
        }
    }

    // ---------------- capture ----------------

    pub(in crate::frame) fn start_capture(&mut self, cx: &mut Context<Self>) {
        if self.is_capturing || self.is_loading {
            return;
        }
        let (Some(serial), Some(process)) = (
            self.selected_device_serial.clone(),
            self.selected_process().cloned(),
        ) else {
            return;
        };
        self.is_loading = true;
        self.error = None;
        self.capture_generation += 1;
        let generation = self.capture_generation;
        let data_dir = self.data_dir.clone();
        cx.notify();
        let task = cx.background_executor().spawn(async move {
            let session_id = new_id("frame-capture");
            let mut capture =
                capture::open_online_capture(&serial, &process, session_id, data_dir)?;
            let warnings = capture.start().await?;
            let metadata = capture.metadata().clone();
            Ok::<_, String>((capture, metadata, warnings))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.capture_generation != generation {
                    return;
                }
                this.is_loading = false;
                match result {
                    Ok((capture, metadata, warnings)) => {
                        this.imported_file_name = None;
                        this.import_timing.clear();
                        this.analysis = None;
                        this.review_file_digest = None;
                        this.filtered_analysis = None;
                        this.selected_frame_id = None;
                        this.perfetto_trace_file = None;
                        this.viewer_only_trace = false;
                        this.mcp_publication = None;
                        this.artifact = None;
                        this.online_frames.clear();
                        this.observed_refresh_rates_hz = metadata.observed_refresh_rates_hz.clone();
                        this.online_capture = Some(capture);
                        this.current_session = Some(metadata.clone());
                        this.is_capturing = true;
                        this.operation_status = Some(model::FrameOperationStatus::Capturing {
                            package_name: metadata
                                .package_name
                                .clone()
                                .unwrap_or_else(|| "—".to_owned()),
                            source: model::capture_source_label(metadata.source).to_owned(),
                            frame_count: None,
                        });
                        this.warnings = warnings
                            .into_iter()
                            .chain(metadata.provenance_warnings.clone())
                            .collect();
                        this.warnings.dedup();
                        this.error = None;
                        this.schedule_capture_poll(cx);
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn schedule_capture_poll(&mut self, cx: &mut Context<Self>) {
        if !self.is_capturing {
            return;
        }
        if self.online_capture.is_none() {
            self.is_capturing = false;
            return;
        }
        let generation = self.capture_generation;
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(POLL_INTERVAL).await;
            this.update(cx, |this, cx| {
                if this.capture_generation != generation || !this.is_capturing {
                    return;
                }
                this.poll_capture_once(cx);
            })
            .ok();
        })
        .detach();
    }

    fn poll_capture_once(&mut self, cx: &mut Context<Self>) {
        let Some(mut capture) = self.online_capture.take() else {
            self.is_capturing = false;
            return;
        };
        let generation = self.capture_generation;
        let task = cx.background_executor().spawn(async move {
            let result = capture.poll().await;
            (capture, result)
        });
        cx.spawn(async move |this, cx| {
            let (capture, result) = task.await;
            this.update(cx, |this, cx| {
                this.online_capture = Some(capture);
                if this.capture_generation != generation || !this.is_capturing {
                    return;
                }
                match result {
                    Ok(batch) => {
                        this.online_frames.extend(batch.frames);
                        this.warnings.extend(batch.warnings);
                        this.warnings.dedup();
                        let analysis = FrameJankAnalyzer::default().analyze(&this.online_frames);
                        this.observed_refresh_rates_hz = observed_refresh_rates(&analysis);
                        if this.selected_frame_id.is_none() {
                            this.selected_frame_id =
                                analysis.frames.last().map(|frame| frame.sample.frame_id);
                        }
                        this.analysis = Some(Arc::new(analysis));
                        this.rebuild_filtered();
                        let metadata = this.current_session.clone();
                        this.operation_status = Some(model::FrameOperationStatus::Capturing {
                            package_name: metadata
                                .as_ref()
                                .and_then(|metadata| metadata.package_name.clone())
                                .unwrap_or_else(|| "—".to_owned()),
                            source: metadata
                                .as_ref()
                                .map(|metadata| model::capture_source_label(metadata.source))
                                .unwrap_or("gfxinfo")
                                .to_owned(),
                            frame_count: Some(this.online_frames.len()),
                        });
                        cx.notify();
                        this.schedule_capture_poll(cx);
                    }
                    Err(error) => this.stop_capture_internal(Some(error), cx),
                }
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::frame) fn stop_capture(&mut self, cx: &mut Context<Self>) {
        self.stop_capture_internal(None, cx);
    }

    fn stop_capture_internal(&mut self, error: Option<String>, cx: &mut Context<Self>) {
        self.capture_generation += 1;
        self.is_capturing = false;
        let capture = self.online_capture.take();
        let frame_count = self.online_frames.len();
        self.operation_status = Some(model::FrameOperationStatus::CaptureStopped { frame_count });
        if capture.is_some() && frame_count == 0 && error.is_none() {
            self.warnings
                .push("No frame samples were collected during the capture interval.".to_owned());
            self.warnings.dedup();
        }
        if let Some(error) = error {
            self.error = Some(error);
        }
        if let Some(mut capture) = capture {
            let session = self.current_session.clone();
            let mut warnings = self.warnings.clone();
            let task = cx.background_executor().spawn(async move {
                let stop_warnings = match capture.stop().await {
                    Ok(warnings) => warnings,
                    Err(error) => vec![format!(
                        "Unable to close online frame capture cleanly: {error}"
                    )],
                };
                (stop_warnings, session)
            });
            let frames = self.online_frames.clone();
            cx.spawn(async move |this, cx| {
                let (stop_warnings, session) = task.await;
                this.update(cx, |this, cx| {
                    warnings.extend(stop_warnings);
                    warnings.dedup();
                    this.warnings = warnings;
                    if let Some(mut session) = session
                        && !frames.is_empty()
                    {
                        let observed: Vec<f64> = frames
                            .iter()
                            .filter_map(|frame| frame.refresh_rate_hz)
                            .collect();
                        session.observed_refresh_rates_hz.extend(observed);
                        sort_dedup_rates(&mut session.observed_refresh_rates_hz);
                        this.current_session = Some(session.clone());
                        this.persist_session(session, frames, None, cx);
                    }
                    cx.notify();
                })
                .ok();
            })
            .detach();
        }
        cx.notify();
    }

    pub(in crate::frame) fn capture_frame_timeline(&mut self, cx: &mut Context<Self>) {
        if self.is_capturing || self.is_loading {
            return;
        }
        let (Some(serial), Some(process)) = (
            self.selected_device_serial.clone(),
            self.selected_process().cloned(),
        ) else {
            return;
        };
        if !self
            .devices
            .iter()
            .any(|device| device.serial == serial && device.online)
        {
            return;
        }
        self.is_loading = true;
        self.import_generation += 1;
        let generation = self.import_generation;
        let cancel = CancellationToken::new();
        self.timeline_operation = Some(FrameTimelineOperation {
            generation,
            serial: serial.clone(),
            process: process.clone(),
            cancel: cancel.clone(),
        });
        self.error = None;
        self.operation_status = Some(model::FrameOperationStatus::Capturing {
            package_name: process.package_name.clone(),
            source: "Perfetto FrameTimeline".to_owned(),
            frame_count: None,
        });
        cx.notify();
        let data_dir = self.data_dir.clone();
        let task = cx.background_executor().spawn(async move {
            // Fail before starting a device recording if this installation cannot analyze it.
            let tool = crate::perfetto_tool::locate(&cancel)?;
            let captured = capture::capture_frame_timeline(
                &serial,
                &process,
                DEFAULT_FRAME_TIMELINE_CAPTURE_MILLIS,
                data_dir.clone(),
                &cancel,
            )
            .await?;
            let raw_artifact = artifact::FrameArtifact::captured(
                &captured.trace_file,
                &serial,
                process.pid,
                &process.package_name,
            )?;
            let processed = perfetto::analyze_with_tool(tool, &captured.trace_file, Some(process.pid), &cancel)?;
            if cancel.is_cancelled() {
                return Err(
                    "FrameTimeline capture cancelled; the previous report is retained.".to_owned(),
                );
            }
            raw_artifact.verify_analysis(processed.source_digest)?;
            if processed.frames.is_empty() {
                return Err(format!("No FrameTimeline rows for selected process {} (pid {}). Reproduce a visible frame or reselect the process after reinstalling.",process.package_name,process.pid));
            }
            let final_artifact = raw_artifact
                .clone()
                .analyzed(&processed.tool_version, processed.capabilities.clone());
            let session_id = format!(
                "perfetto-{}",
                final_artifact.id.trim_start_matches("frame-").to_owned()
            );
            let frames: Vec<FrameSample> = processed
                .frames
                .into_iter()
                .map(|mut frame| {
                    frame.states.insert("aps.target.process".into(), process.name.clone());
                    frame.package_name = Some(process.package_name.clone());
                    frame.process_id = Some(process.pid);
                    frame.session_id = session_id.clone();
                    frame
                })
                .collect();
            let session = FrameCaptureSession {
                id: session_id,
                source: FrameSource::Perfetto,
                started_at_unix_ms: captured.started_at_unix_ms,
                package_name: Some(process.package_name.clone()),
                device_serial: Some(serial.clone()),
                device_api_level: Some(captured.android_api_level),
                agent_protocol: None,
                source_capabilities: Some(FrameSourceCapabilities {
                    realtime: false,
                    stage_breakdown: false,
                    platform_jank_classification: true,
                    expected_frame_deadline: true,
                    app_state_labels: true,
                }),
                observed_refresh_rates_hz: captured.observed_refresh_rates_hz,
                imported_file: None,
                imported_file_sha256: None,
                imported_at_unix_ms: None,
                provenance_complete: true,
                provenance_warnings: Vec::new(),
                perfetto_trace_file: final_artifact.trace_path.as_ref().map(|p| p.display().to_string()),
                artifact: Some(final_artifact.id.clone()),
            };
            let receipt = mcp_receipt::Receipt::for_capture(
                &session, &frames, &final_artifact, &process, processed.measured_process.as_deref(), processed.probe_query_complete,
            );
            let labels = data_dir.as_deref().map(|d| probe_receipt::labels(d,&process.package_name)).transpose();
            Ok::<_, String>((
                receipt, labels, processed.probe_warning,
                frames,
                session,
                final_artifact,
                processed.source_digest,
                captured.recording,
            ))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this
                    .timeline_operation
                    .as_ref()
                    .map(|operation| operation.generation)
                    != Some(generation)
                {
                    return;
                }
                let operation = this
                    .timeline_operation
                    .take()
                    .expect("matched timeline operation");
                this.is_loading = false;
                if !operation.can_publish(this.import_generation) {
                    this.operation_status = Some(model::FrameOperationStatus::CaptureCancelled);
                    // Unconfirmed remote cleanup includes its exact recovery
                    // paths, even when the UI target changed during capture.
                    if let Err(error) = result {
                        this.error = Some(error);
                    }
                    cx.notify();
                    return;
                }
                match result {
                    Ok((
                        receipt,
                        labels,
                        probe_warning,
                        frames,
                        session,
                        final_artifact,
                        trace_digest,
                        mut recording,
                    )) => {
                        if let Some(dir) = this.data_dir.as_deref() {
                            if let Err(error) = final_artifact.save(Some(dir)) {
                                this.operation_status =
                                    Some(model::FrameOperationStatus::CaptureStopped {
                                        frame_count: 0,
                                    });
                                this.error = Some(error);
                                cx.notify();
                                return;
                            }
                        }
                        this.imported_file_name = None;
                        this.perfetto_trace_file = final_artifact.trace_path.clone();
                        this.viewer_only_trace = false;
                        this.artifact = Some(final_artifact);
                        this.current_session = Some(session.clone());
                        let analysis = FrameJankAnalyzer::default().analyze(&frames);
                        this.apply_analysis(analysis, cx);
                        this.mcp_publication = receipt
                            .zip(this.analysis.as_ref())
                            .map(|(r, full)| r.bind(full));
                        this.operation_status = Some(model::FrameOperationStatus::CaptureStopped {
                            frame_count: frames.len(),
                        });
                        this.probe_labels.clear();
                        this.warnings = probe_warning.into_iter().collect();
                        match labels {
                            Ok(Some(map)) => this.probe_labels = map,
                            Ok(None) => {}
                            Err(e) => this.warnings.push(e),
                        }
                        this.error = None;
                        // Only a successful, current publication becomes a
                        // persistent Frame session. Stale/error tasks stay RAII.
                        recording.retain_local_artifacts();
                        this.persist_session(session, frames, Some(trace_digest), cx);
                    }
                    Err(error) => {
                        this.operation_status =
                            Some(model::FrameOperationStatus::CaptureStopped { frame_count: 0 });
                        this.error = Some(error);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn reconcile_frame_timeline_target(&mut self, cx: &mut Context<Self>) {
        let changed = self.timeline_operation.as_ref().is_some_and(|operation| {
            !operation.matches_target(
                self.selected_device_serial.as_deref(),
                self.selected_process(),
                self.devices
                    .iter()
                    .any(|device| device.serial == operation.serial && device.online),
            )
        });
        if changed {
            self.cancel_frame_timeline(cx);
        }
    }

    pub(in crate::frame) fn cancel_frame_timeline(&mut self, cx: &mut Context<Self>) {
        let Some(operation) = &self.timeline_operation else {
            return;
        };
        if operation.cancel.is_cancelled() {
            return;
        }
        operation.cancel.cancel();
        self.import_generation += 1;
        self.operation_status = Some(model::FrameOperationStatus::CaptureCancelling);
        // Keep is_loading until this exact task finishes owned remote cleanup.
        // A second recording cannot replace the cleanup operation in flight.
        cx.notify();
    }

    fn persist_session(
        &mut self,
        session: FrameCaptureSession,
        frames: Vec<FrameSample>,
        trace_digest: Option<SourceDigest>,
        cx: &mut Context<Self>,
    ) {
        let store = self.session_store.clone();
        let task = cx.background_executor().spawn(async move {
            if trace_digest.is_some() {
                store.save_with_trace(&session, &frames, trace_digest)
            } else {
                store.save(&session, &frames)
            }
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if let Err(error) = result {
                    let warning = this.language.format(
                        TextKey::FrameSessionDatabaseUpdateFailed,
                        &[("value1", &error)],
                    );
                    if !this.warnings.contains(&warning) {
                        this.warnings.push(warning);
                        cx.notify();
                    }
                }
            })
            .ok();
        })
        .detach();
    }

    // ---------------- export / viewer / layout ----------------

    pub(crate) fn export_csv(&mut self, cx: &mut Context<Self>) {
        let Some(analysis) = self.analysis.clone() else {
            return;
        };
        let directory = export_directory(self.data_dir.as_deref());
        let dialog = cx.prompt_for_new_path(&directory, Some("frame-analysis.csv"));
        cx.spawn(async move |this, cx| {
            let result = dialog.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(Some(path))) => match export::export_csv(&analysis, &path) {
                    Ok(()) => {
                        this.operation_status = Some(model::FrameOperationStatus::Exported {
                            file_name: path
                                .file_name()
                                .and_then(|name| name.to_str())
                                .unwrap_or("frame-analysis.csv")
                                .to_owned(),
                        });
                        this.error = None;
                    }
                    Err(error) => this.error = Some(error),
                },
                Ok(Err(error)) => this.error = Some(format!("{error}")),
                _ => {}
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn export_json(&mut self, cx: &mut Context<Self>) {
        let Some(analysis) = self.analysis.clone() else {
            return;
        };
        let data_dir = self.data_dir.clone();
        let directory = export_directory(data_dir.as_deref());
        let dialog = cx.prompt_for_new_path(&directory, Some("frame-analysis.json"));
        cx.spawn(async move |this, cx| {
            let path = match dialog.await {
                Ok(Ok(Some(path))) => path,
                Ok(Err(err)) => {
                    let _ = this.update(cx, |this, cx| {
                        this.error = Some(err.to_string());
                        cx.notify();
                    });
                    return;
                }
                _ => return,
            };
            // Resolve authority after the dialog, against the exact report selected
            // at invocation. Reassociation/import in the meantime cannot add trust.
            let Ok(receipt) = this.update(cx, |this, _| {
                this.mcp_publication
                    .as_ref()
                    .and_then(|p| p.for_export(&analysis))
            }) else {
                return;
            };
            let result = cx
                .background_executor()
                .spawn(async move {
                    mcp_receipt::export_report(
                        &analysis,
                        &path,
                        receipt.as_ref(),
                        data_dir.as_deref(),
                    )?;
                    Ok::<_, String>(path)
                })
                .await;
            let _ = this.update(cx, |this, cx| {
                match result {
                    Ok(path) => {
                        this.operation_status = Some(model::FrameOperationStatus::Exported {
                            file_name: path
                                .file_name()
                                .and_then(|n| n.to_str())
                                .unwrap_or("frame-analysis.json")
                                .to_owned(),
                        });
                        this.error = None;
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            });
        })
        .detach();
    }

    pub(in crate::frame) fn open_trace(&mut self, cx: &mut Context<Self>) {
        if self.is_loading || self.is_capturing {
            return;
        }
        let Some(trace) = self.perfetto_trace_file.clone() else {
            return;
        };
        if self.viewer_only_trace {
            // An arbitrary viewer link has no proven time alignment with the
            // selected frame. Do not turn a comparable-looking clock into a jump.
            self.show_trace(&trace, None, cx);
            return;
        }
        let Some(artifact) = self.artifact.clone().filter(|artifact| {
            artifact.trace_path.as_ref() == Some(&trace)
                && self
                    .current_session
                    .as_ref()
                    .and_then(|s| s.artifact.as_ref())
                    == Some(&artifact.id)
        }) else {
            return;
        };
        let selection = self.frame_binding();
        let task = cx
            .background_executor()
            .spawn(async move { artifact.verify_current_trace() });
        cx.spawn(async move |this, cx| {
            let verified = task.await;
            this.update(cx, |this, cx| {
                if this.viewer_only_trace
                    || this.perfetto_trace_file.as_ref() != Some(&trace)
                    || this.frame_binding() != selection
                {
                    return;
                }
                match verified {
                    Ok(()) => {
                        let timestamp = this.selected_frame().and_then(|frame| {
                            frame
                                .sample
                                .intended_vsync_ns
                                .or(frame.sample.actual_vsync_ns)
                        });
                        this.show_trace(&trace, timestamp, cx);
                    }
                    Err(error) => {
                        this.error = Some(error);
                        cx.notify();
                    }
                }
            })
            .ok();
        })
        .detach();
    }

    fn show_trace(&mut self, trace: &Path, timestamp: Option<i64>, cx: &mut Context<Self>) {
        let assets = crate::perfetto_tool::viewer_assets_dir();
        if let Err(error) = self
            .viewer
            .open(trace, timestamp, assets.as_deref(), None, None)
        {
            self.error = Some(error);
            cx.notify();
        }
    }

    pub(in crate::frame) fn inspect_layout(&mut self, cx: &mut Context<Self>) {
        let Some(frame) = self.selected_frame() else {
            return;
        };
        if frame.sample.package_name.is_none() {
            return;
        }
        let request = FrameLayoutInspectionRequest {
            device_serial: self.selected_device_serial.clone(),
        };
        if self.is_capturing {
            self.stop_capture(cx);
        }
        cx.emit(request);
    }

    fn save_probe_receipt(&mut self, r: &probe_receipt::Receipt, cx: &mut Context<Self>) {
        let result = self
            .data_dir
            .as_deref()
            .ok_or("No local frame data directory.".to_owned())
            .and_then(|d| probe_receipt::save(d, r));
        if let Err(err) = result {
            self.error = Some(format!(
                "Patch exported, but its local source binding was not saved: {err}"
            ));
        }
        cx.notify();
    }
    pub(crate) fn frame_binding(&self) -> Option<(String, u64)> {
        if self.is_capturing || self.is_loading {
            return None;
        }
        self.selected_frame()
            .map(|f| (f.sample.session_id.clone(), f.sample.frame_id))
    }

    pub(crate) fn probe_binding(&self) -> Option<(String, u64)> {
        if self.is_loading || self.is_capturing {
            return None;
        }
        self.selected_frame()
            .filter(|f| can_probe(f))
            .map(|f| (f.sample.session_id.clone(), f.sample.frame_id))
    }
    pub(crate) fn probe_plan_request(&self) -> Result<AnalysisRequest, String> {
        if self.is_loading || self.is_capturing {
            return Err("Finish capture before analyzing a probe.".into());
        }
        let id = self
            .selected_frame()
            .ok_or("Select a frame.")?
            .sample
            .frame_id;
        ai::probe_plan(self.filtered_analysis.as_deref().ok_or("No analysis")?, id)
    }
    pub(crate) fn analysis_request(&self) -> Result<AnalysisRequest, String> {
        let analysis = self
            .analysis
            .as_deref()
            .ok_or_else(|| "Import or capture frames before AI analysis".to_owned())?;
        match self.review_file_digest {
            Some(digest) => ai::analysis_request_for_file(analysis, self.selected_frame_id, digest),
            None => ai::analysis_request(analysis, self.selected_frame_id),
        }
    }

    pub(crate) fn can_analyze(&self) -> bool {
        self.analysis_request().is_ok()
    }

    pub(in crate::frame) fn failure(&self, error: impl std::fmt::Display) -> String {
        format!("{error}")
    }
}

fn export_directory(data_dir: Option<&Path>) -> PathBuf {
    data_dir
        .map(Path::to_path_buf)
        .or_else(|| std::env::current_dir().ok())
        .unwrap_or_else(|| PathBuf::from("."))
}

fn new_id(prefix: &str) -> String {
    let unix = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    format!("{prefix}-{}-{unix}", std::process::id())
}

fn sort_dedup_rates(rates: &mut Vec<f64>) {
    rates.sort_by(|left, right| left.total_cmp(right));
    rates.dedup_by(|left, right| (*left - *right).abs() < f64::EPSILON);
}

fn observed_refresh_rates(analysis: &FrameAnalysisResult) -> Vec<f64> {
    let mut rates: Vec<f64> = analysis
        .frames
        .iter()
        .filter_map(|frame| frame.sample.refresh_rate_hz)
        .collect();
    sort_dedup_rates(&mut rates);
    rates
}

struct LoadedFrameStats {
    review_file_digest: Option<SourceDigest>,
    analysis: FrameAnalysisResult,
    session: FrameCaptureSession,
    frames: Vec<FrameSample>,
    warnings: Vec<String>,
    file_name: String,
    trace_path: Option<PathBuf>,
    artifact: Option<artifact::FrameArtifact>,
    trace_digest: Option<SourceDigest>,
}

fn read_frame_stats(path: &Path, max_bytes: u64) -> Result<Vec<u8>, String> {
    let check = |metadata: std::fs::Metadata| metadata.is_file() && metadata.len() <= max_bytes;
    if !check(std::fs::metadata(path).map_err(|_| "FrameStats input does not exist")?) {
        return Err("FrameStats input must be a bounded regular file".to_owned());
    }
    let file = std::fs::File::open(path).map_err(|_| "Cannot open FrameStats input")?;
    if !check(
        file.metadata()
            .map_err(|_| "Cannot inspect open FrameStats input")?,
    ) {
        return Err("FrameStats input must be a bounded regular file".to_owned());
    }
    read_frame_stats_bytes(file, max_bytes)
}

fn read_frame_stats_bytes(input: impl Read, max_bytes: u64) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    input
        .take(max_bytes + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "Cannot read FrameStats input")?;
    if bytes.len() as u64 > max_bytes {
        return Err("FrameStats input exceeds the byte limit".into());
    }
    Ok(bytes)
}

fn import_frame_stats_blocking(
    path: PathBuf,
    session_id: String,
) -> Result<LoadedFrameStats, String> {
    let bytes = read_frame_stats(&path, MAX_IMPORT_BYTES)?;
    import_frame_stats_snapshot(&path, &bytes, session_id)
}

fn import_frame_stats_snapshot(
    path: &Path,
    bytes: &[u8],
    session_id: String,
) -> Result<LoadedFrameStats, String> {
    // Hash the immutable buffer that is actually parsed, not a second open of
    // the path (which may already refer to a replacement file).
    let digest = SourceDigest::hash_reader(bytes, MAX_IMPORT_BYTES)?;
    let text =
        std::str::from_utf8(bytes).map_err(|_| "FrameStats input must be bounded UTF-8 text")?;
    let loaded = decode_frame_stats(path, text, session_id, digest)?;
    if !path.is_file() || SourceDigest::hash_file(path, MAX_IMPORT_BYTES).ok() != Some(digest) {
        return Err("FrameStats input changed during import".into());
    }
    Ok(loaded)
}

fn decode_frame_stats(
    path: &Path,
    text: &str,
    session_id: String,
    digest: SourceDigest,
) -> Result<LoadedFrameStats, String> {
    // File > Open accepts both a gfxinfo FrameStats capture and this app's own
    // exported frame-analysis.json report (Compose schemaVersion 3).
    let exported = analysis_json::looks_like_export(text);
    let (frames, parse_warnings, package_name) = if exported {
        let frames = analysis_json::parse_export(text, &session_id)?;
        let package = frames.iter().find_map(|frame| frame.package_name.clone());
        (frames, Vec::new(), package)
    } else {
        let package = Some("imported".to_owned());
        let parsed = aps_frame::parse_gfxinfo(text, &session_id, package.as_deref(), true)?;
        (parsed.frames, parsed.warnings, package)
    };
    if frames.is_empty() {
        return Err(format!(
            "No usable frame rows were found in {}.",
            path.file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("the selected file")
        ));
    }
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("framestats.txt")
        .to_owned();
    let started_at_unix_ms = now_unix_ms();
    let session_source = if exported {
        frames
            .first()
            .map_or(FrameSource::Gfxinfo, |frame| frame.source)
    } else {
        FrameSource::Gfxinfo
    };
    let session = FrameCaptureSession {
        id: session_id,
        source: session_source,
        started_at_unix_ms,
        package_name,
        device_serial: None,
        device_api_level: None,
        agent_protocol: None,
        source_capabilities: Some(FrameSourceCapabilities {
            realtime: false,
            stage_breakdown: frames.iter().any(|frame| !frame.stages.values().is_empty()),
            platform_jank_classification: exported
                && frames.iter().any(|frame| {
                    frame.platform_jank.is_some() || !frame.platform_jank_types.is_empty()
                }),
            expected_frame_deadline: frames
                .iter()
                .any(|frame| frame.expected_duration_ns.is_some()),
            app_state_labels: exported
                && frames
                    .iter()
                    .any(|frame| !frame.states.is_empty() || frame.interaction_state.is_some()),
        }),
        observed_refresh_rates_hz: frames
            .iter()
            .filter_map(|frame| frame.refresh_rate_hz)
            .collect(),
        imported_file: Some(path.display().to_string()),
        imported_file_sha256: Some(digest.to_hex()),
        imported_at_unix_ms: Some(started_at_unix_ms),
        provenance_complete: false,
        provenance_warnings: if exported {
            vec![
                "The imported frame-analysis.json does not identify its device or Android API level."
                    .to_owned(),
                "The exported frame rows were re-analyzed by the current analyzer; the exported summary was not used."
                    .to_owned(),
            ]
        } else {
            vec![
                "The imported framestats file does not identify its device or Android API level."
                    .to_owned(),
            ]
        },
        perfetto_trace_file: None,
        artifact: None,
    };
    let analysis = FrameJankAnalyzer::default().analyze(&frames);
    let warnings = parse_warnings
        .into_iter()
        .chain(session.provenance_warnings.clone())
        .collect();
    Ok(LoadedFrameStats {
        review_file_digest: Some(digest),
        analysis,
        session,
        frames,
        warnings,
        file_name,
        trace_path: None,
        artifact: None,
        trace_digest: None,
    })
}

fn import_perfetto_blocking(
    path: PathBuf,
    data_dir: Option<PathBuf>,
) -> Result<LoadedFrameStats, String> {
    if !path.is_file() {
        return Err(format!("Trace file does not exist: {}", path.display()));
    }
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("trace.pftrace")
        .to_owned();
    let raw_artifact = artifact::FrameArtifact::imported(&path)?;
    let cancel = CancellationToken::new();
    let processed = perfetto::analyze_trace(&path, &cancel)?;
    if processed.frames.is_empty() {
        return Err(format!("No FrameTimeline rows were found in {file_name}."));
    }
    raw_artifact.verify_analysis(processed.source_digest)?;
    let final_artifact = raw_artifact
        .clone()
        .analyzed(&processed.tool_version, processed.capabilities.clone());
    if let Some(dir) = data_dir.as_deref() {
        final_artifact.save(Some(dir))?;
    }
    let session_id = format!(
        "perfetto-{}",
        final_artifact.id.trim_start_matches("frame-").to_owned()
    );
    let frames: Vec<FrameSample> = processed
        .frames
        .into_iter()
        .map(|mut frame| {
            frame.session_id = session_id.clone();
            frame
        })
        .collect();
    let started_at_unix_ms = now_unix_ms();
    let session = FrameCaptureSession {
        id: session_id,
        source: FrameSource::Perfetto,
        started_at_unix_ms,
        package_name: None,
        device_serial: None,
        device_api_level: None,
        agent_protocol: None,
        source_capabilities: Some(FrameSourceCapabilities {
            realtime: false,
            stage_breakdown: false,
            platform_jank_classification: true,
            expected_frame_deadline: true,
            app_state_labels: true,
        }),
        observed_refresh_rates_hz: Vec::new(),
        imported_file: Some(path.display().to_string()),
        imported_file_sha256: Some(final_artifact.sha256.clone()),
        imported_at_unix_ms: Some(started_at_unix_ms),
        provenance_complete: true,
        provenance_warnings: Vec::new(),
        perfetto_trace_file: final_artifact
            .trace_path
            .as_ref()
            .map(|p| p.display().to_string()),
        artifact: Some(final_artifact.id.clone()),
    };
    let analysis = FrameJankAnalyzer::default().analyze(&frames);
    Ok(LoadedFrameStats {
        review_file_digest: None,
        analysis,
        session,
        frames,
        warnings: processed.probe_warning.into_iter().collect(),
        file_name,
        trace_path: final_artifact.trace_path.clone(),
        artifact: Some(final_artifact),
        trace_digest: Some(processed.source_digest),
    })
}

fn now_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}

impl Render for FrameWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        page::render(self, window, cx)
    }
}

/// In-window menu bar commands for the Frame workspace. Each screen publishes
/// only the commands its own functionality supports.
#[derive(Clone, PartialEq, Eq)]
pub(crate) enum FrameMenuCommand {
    ImportFrameStats,
    ImportPerfettoTrace,
    AssociatePerfettoTrace,
    ExportCsv,
    ExportJson,
    RefreshDevices,
    CaptureFrameTimeline,
    CancelFrameTimeline,
    StartCapture,
    StopCapture,
    OpenTrace,
    SetViewFilter(FrameViewFilter),
    PreviousFrame,
    NextFrame,
    CopyEvidence,
    InspectLayout,
}

impl crate::shell::menubar::MenuHost for FrameWorkspace {
    type Command = FrameMenuCommand;

    fn menu_groups(
        &self,
        _language: aps_ui_foundation::Language,
    ) -> Vec<crate::shell::menubar::MenuGroup<Self::Command>> {
        use crate::shell::menubar::MenuGroup;

        // Same predicates the header, filters and detail panel apply; the menu
        // is built when it opens, so they always describe the current workspace.
        let busy = self.is_capturing || self.is_loading;
        let has_analysis = self.analysis.is_some();
        let process_ready = self.selected_process().is_some();
        let device_ready = self.selected_device_serial.as_ref().is_some_and(|serial| {
            self.devices
                .iter()
                .any(|device| &device.serial == serial && device.online)
        });
        let selected = self.selected_frame();
        let previous = self
            .filtered_analysis
            .as_deref()
            .and_then(|analysis| model::previous_frame_id(analysis, self.selected_frame_id));
        let next = self
            .filtered_analysis
            .as_deref()
            .and_then(|analysis| model::next_frame_id(analysis, self.selected_frame_id));
        let has_package = selected.is_some_and(|frame| frame.sample.package_name.is_some());

        // Compose's Frame Profiler File > Open… imports a FrameStats capture and
        // its File > Export carries the CSV/JSON reports; the shared action makes
        // the Open row own Cmd+O for the visible page.
        let file = MenuGroup::new(TextKey::ShellMenuFile)
            .item_action_when(
                !busy,
                TextKey::ShellMenuOpen,
                FrameMenuCommand::ImportFrameStats,
                crate::shell::OpenActiveRouteFile,
            )
            .submenu(TextKey::ShellMenuImport, !busy, |menu| {
                menu.item_when(
                    !busy,
                    TextKey::FrameImportPerfettoFrametimeline,
                    FrameMenuCommand::ImportPerfettoTrace,
                )
                .item_when(
                    !busy && has_analysis,
                    TextKey::FrameAssociatePerfettoTrace,
                    FrameMenuCommand::AssociatePerfettoTrace,
                )
            })
            .separator()
            .submenu(TextKey::ShellMenuExport, true, |menu| {
                menu.item_when(
                    has_analysis,
                    TextKey::FrameExportCsv,
                    FrameMenuCommand::ExportCsv,
                )
                .item_when(
                    has_analysis,
                    TextKey::FrameExportJson,
                    FrameMenuCommand::ExportJson,
                )
            });

        let mut frames = MenuGroup::new(TextKey::ShellMenuFrames)
            .item_when(
                !busy && !self.is_refreshing_devices,
                TextKey::FrameRefresh,
                FrameMenuCommand::RefreshDevices,
            )
            .separator()
            .item_when(
                !busy && process_ready && device_ready,
                TextKey::FrameCaptureFrametimeline,
                FrameMenuCommand::CaptureFrameTimeline,
            );
        if let Some(operation) = &self.timeline_operation {
            frames = frames.item_when(
                !operation.cancel.is_cancelled(),
                TextKey::FrameCancelFrametimeline,
                FrameMenuCommand::CancelFrameTimeline,
            );
        }
        frames = if self.is_capturing {
            frames.item(TextKey::FrameStopCapture, FrameMenuCommand::StopCapture)
        } else {
            frames.item_action_when(
                process_ready && device_ready && !self.is_loading,
                TextKey::FrameStartCapture,
                FrameMenuCommand::StartCapture,
                crate::shell::StartCaptureActiveRoute,
            )
        };
        frames = frames.separator().item_when(
            self.perfetto_trace_file.is_some(),
            TextKey::FrameOpenTraceInPerfetto,
            FrameMenuCommand::OpenTrace,
        );

        let mut view = MenuGroup::new(TextKey::ShellMenuView);
        for filter in model::VIEW_FILTERS {
            view = view.check(
                model::view_filter_label(filter),
                self.view_filter == filter,
                FrameMenuCommand::SetViewFilter(filter),
            );
        }
        view = view
            .separator()
            .item_when(
                previous.is_some(),
                TextKey::FramePreviousFrame,
                FrameMenuCommand::PreviousFrame,
            )
            .item_when(
                next.is_some(),
                TextKey::FrameNextFrame,
                FrameMenuCommand::NextFrame,
            )
            .separator()
            .item_when(
                selected.is_some(),
                TextKey::FrameCopyEvidence,
                FrameMenuCommand::CopyEvidence,
            )
            .item_action_when(
                has_package,
                TextKey::FrameCorrelateInLayoutInspector,
                FrameMenuCommand::InspectLayout,
                crate::shell::GoToSourceActiveRoute,
            );

        vec![file, frames, view]
    }

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        _window: &mut gpui_kit::Window,
        cx: &mut gpui_kit::Context<Self>,
    ) {
        match command {
            FrameMenuCommand::ImportFrameStats => self.pick_import_frame_stats(cx),
            FrameMenuCommand::ImportPerfettoTrace => self.pick_import_perfetto(cx),
            FrameMenuCommand::AssociatePerfettoTrace => self.pick_associate_perfetto(cx),
            FrameMenuCommand::ExportCsv => self.export_csv(cx),
            FrameMenuCommand::ExportJson => self.export_json(cx),
            FrameMenuCommand::RefreshDevices => self.refresh_devices(cx),
            FrameMenuCommand::CaptureFrameTimeline => self.capture_frame_timeline(cx),
            FrameMenuCommand::CancelFrameTimeline => self.cancel_frame_timeline(cx),
            FrameMenuCommand::StartCapture => self.start_capture(cx),
            FrameMenuCommand::StopCapture => self.stop_capture(cx),
            FrameMenuCommand::OpenTrace => self.open_trace(cx),
            FrameMenuCommand::SetViewFilter(filter) => self.set_view_filter(filter, cx),
            FrameMenuCommand::PreviousFrame => self.previous_frame(cx),
            FrameMenuCommand::NextFrame => self.next_frame(cx),
            FrameMenuCommand::CopyEvidence => {
                let Some(frame) = self.selected_frame() else {
                    return;
                };
                let evidence = model::copy_evidence_text(frame, self.language);
                let frame_id = frame.sample.frame_id;
                cx.write_to_clipboard(ClipboardItem::new_string(evidence));
                self.ui.set_copy_feedback(frame_id, true);
                cx.notify();
            }
            FrameMenuCommand::InspectLayout => self.inspect_layout(cx),
        }
    }
}

/// Route-shortcut entry points (IDEA-style window shortcuts).
impl FrameWorkspace {
    pub(crate) fn start_capture_primary(&mut self, cx: &mut Context<Self>) {
        self.start_capture(cx);
    }

    pub(crate) fn inspect_layout_primary(&mut self, cx: &mut Context<Self>) {
        self.inspect_layout(cx);
    }
}

impl NoticeSource for FrameWorkspace {
    fn notice_snapshot(&self) -> Vec<NoticeSnapshot> {
        let mut notices = Vec::new();
        if let Some(error) = &self.error {
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(error, 8192)));
        }
        for warning in self.warnings.iter().take(8) {
            notices.push(NoticeSnapshot::warning(crate::evidence::clipped(
                warning, 8192,
            )));
        }
        notices.retain(|notice| !notice.message.trim().is_empty());
        notices.truncate(8);
        notices
    }
}

impl Drop for FrameWorkspace {
    fn drop(&mut self) {
        if let Some(operation) = &self.timeline_operation {
            operation.cancel.cancel();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn timeline_target_and_publication_require_the_same_live_operation() {
        let operation = FrameTimelineOperation {
            generation: 7,
            serial: "device-one".into(),
            process: capture::FrameProcessOption {
                pid: 42,
                name: "dev.sample".into(),
                package_name: "dev.sample".into(),
            },
            cancel: CancellationToken::new(),
        };
        assert!(operation.matches_target(Some("device-one"), Some(&operation.process), true));
        assert!(!operation.matches_target(Some("device-two"), Some(&operation.process), true));
        assert!(!operation.matches_target(Some("device-one"), None, true));
        assert!(!operation.matches_target(Some("device-one"), Some(&operation.process), false));
        let mut changed = operation.process.clone();
        changed.package_name = "other.app".into();
        assert!(!operation.matches_target(Some("device-one"), Some(&changed), true));
        changed = operation.process.clone();
        changed.pid += 1;
        assert!(!operation.matches_target(Some("device-one"), Some(&changed), true));
        assert!(operation.can_publish(7));
        assert!(!operation.can_publish(8));
        operation.cancel.cancel();
        assert!(!operation.can_publish(7));
    }

    #[::core::prelude::v1::test]
    fn timeline_cancellation_preserves_old_report_and_retains_only_current_success() {
        let source = include_str!("frame.rs");
        let capture = source
            .split("pub(in crate::frame) fn capture_frame_timeline(")
            .nth(1)
            .unwrap()
            .split("fn reconcile_frame_timeline_target(")
            .next()
            .unwrap();
        assert!(!capture.contains("self.analysis = None"));
        assert!(!capture.contains("self.perfetto_trace_file = None"));
        assert!(
            capture.find("perfetto_tool::locate(&cancel)").unwrap()
                < capture.find("capture::capture_frame_timeline(").unwrap()
        );
        assert!(
            capture.contains("Some(process.pid)")
                && capture.contains("perfetto::analyze_with_tool(")
        );
        assert!(capture.contains("operation.can_publish(this.import_generation)"));
        assert!(
            capture
                .find("operation.can_publish(this.import_generation)")
                .unwrap()
                < capture.find("recording.retain_local_artifacts()").unwrap()
        );
        let failed = capture.rsplit("Err(error) =>").next().unwrap();
        assert!(!failed.contains("retain_local_artifacts"));
        assert!(!failed.contains("this.analysis ="));
        let cancel = source
            .split("pub(in crate::frame) fn cancel_frame_timeline(")
            .nth(1)
            .unwrap()
            .split("fn persist_session(")
            .next()
            .unwrap();
        assert!(cancel.contains("operation.cancel.cancel()"));
        assert!(cancel.contains("self.import_generation += 1"));
        assert!(!cancel.contains("self.is_loading = false"));
        assert!(source.contains("impl Drop for FrameWorkspace"));
    }

    #[::core::prelude::v1::test]
    fn file_import_time_is_recorded_only_after_validated_analysis_publication() {
        let source = include_str!("frame.rs");
        for (start, end) in [
            ("fn import_frame_stats(", "fn import_perfetto_trace("),
            ("fn import_perfetto_trace(", "fn associate_perfetto_trace("),
        ] {
            let import = source
                .split(start)
                .nth(1)
                .unwrap()
                .split(end)
                .next()
                .unwrap();
            assert!(import.contains("let started = Instant::now()"));
            assert!(
                import
                    .find("if this.import_generation != generation")
                    .unwrap()
                    < import
                        .find("this.import_timing = ImportTiming::completed(started)")
                        .unwrap()
            );
            assert!(
                import
                    .find("this.apply_analysis(loaded.analysis, cx)")
                    .unwrap()
                    < import
                        .find("this.import_timing = ImportTiming::completed(started)")
                        .unwrap()
            );
            assert!(
                !import
                    .split("Err(error) =>")
                    .nth(1)
                    .unwrap()
                    .contains("import_timing")
            );
        }
        let picker = source
            .split("fn pick_file(")
            .nth(1)
            .unwrap()
            .split("fn import_frame_stats(")
            .next()
            .unwrap();
        assert!(!picker.contains("Instant::now"));
        let association = source
            .split("fn associate_perfetto_trace(")
            .nth(1)
            .unwrap()
            .split("fn apply_analysis(")
            .next()
            .unwrap();
        // Linking an existing trace does not parse or publish new data and
        // therefore must preserve the meaningful data-import measurement.
        assert!(!association.contains("Instant::now"));
        assert!(!association.contains("self.import_timing"));
        assert!(association.contains("self.viewer_only_trace = true"));
        assert!(!association.contains("persist_session"));
        assert!(!association.contains("session.perfetto_trace_file ="));
        let apply = source
            .split("fn apply_analysis(")
            .nth(1)
            .unwrap()
            .split("fn rebuild_filtered(")
            .next()
            .unwrap();
        assert!(apply.contains("self.import_timing.clear()"));
        let status = include_str!("frame/page.rs")
            .split("fn status_bar(")
            .nth(1)
            .unwrap()
            .split("fn body(")
            .next()
            .unwrap();
        assert!(status.contains("this.import_timing.text(language)"));
        assert!(status.contains("crate::studio::selectable(text)"));
        assert_eq!(status.matches(".id(\"frame-status-bar\")").count(), 1);
    }

    use aps_frame::ExpectedDurationSource;
    use aps_frame::model::FrameStages;
    use std::collections::{BTreeMap, BTreeSet};
    use std::sync::atomic::{AtomicUsize, Ordering};

    static NEXT_DIRECTORY: AtomicUsize = AtomicUsize::new(0);

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "aps-frame-import-{}-{}",
                std::process::id(),
                NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&path).unwrap();
            Self(path)
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn sample() -> FrameSample {
        FrameSample {
            frame_id: 1,
            session_id: "session-1".into(),
            source: FrameSource::Gfxinfo,
            package_name: Some("com.example.app".into()),
            process_id: Some(4242),
            activity_name: Some("MainActivity".into()),
            window_id: Some("window:1".into()),
            intended_vsync_ns: Some(16_666_667),
            actual_vsync_ns: Some(16_666_667),
            frame_completed_ns: None,
            present_ns: None,
            expected_duration_ns: Some(16_666_667),
            expected_duration_source: ExpectedDurationSource::FrameInterval,
            refresh_rate_hz: Some(60.0),
            frame_timeline_vsync_id: None,
            total_duration_ns: Some(20_000_000),
            stages: FrameStages {
                draw_ns: Some(8_000_000),
                ..FrameStages::default()
            },
            platform_jank: None,
            platform_jank_types: BTreeSet::new(),
            platform_jank_rule_id: None,
            platform_jank_rule_version: None,
            states: BTreeMap::from([("screen".to_owned(), "feed".to_owned())]),
            eligible_for_jank: true,
            dropped_before_sample: 0,
            layout_snapshot_id: None,
            fragment_name: None,
            page_name: None,
            interaction_state: None,
            render_thread_name: None,
            surface_flinger_jank_type: None,
            jank_stats_jank: None,
            jank_stats_reasons: BTreeSet::new(),
            jank_stats_rule_id: None,
            jank_stats_rule_version: None,
        }
    }

    fn exported(directory: &Path) -> PathBuf {
        let analysis = FrameJankAnalyzer::default().analyze(&[sample()]);
        let path = directory.join("frame-analysis.json");
        export::export_json(&analysis, &path).unwrap();
        path
    }

    #[::core::prelude::v1::test]
    fn open_accepts_the_exported_analysis_report() {
        let directory = TestDirectory::new();
        let path = exported(directory.path());
        let loaded = import_frame_stats_blocking(path, "frame-import-test".into()).unwrap();
        assert_eq!(loaded.analysis.frames.len(), 1);
        assert_eq!(loaded.frames.len(), 1);
        assert_eq!(loaded.frames[0].session_id, "frame-import-test");
        assert_eq!(loaded.file_name, "frame-analysis.json");
        assert!(loaded.session.imported_file_sha256.is_some());
        assert_eq!(loaded.session.source, FrameSource::Gfxinfo);
        assert!(
            loaded
                .warnings
                .iter()
                .any(|warning| warning.contains("re-analyzed"))
        );
    }

    #[::core::prelude::v1::test]
    fn open_rejects_an_unsupported_report_version() {
        let directory = TestDirectory::new();
        let path = directory.path().join("frame-analysis.json");
        std::fs::write(&path, "{\"schemaVersion\": 2, \"frames\": []}").unwrap();
        let error = match import_frame_stats_blocking(path, "frame-import-test".into()) {
            Ok(_) => panic!("an unsupported report version must fail closed"),
            Err(error) => error,
        };
        assert!(error.contains("Unsupported frame-analysis.json schema version 2"));
    }

    const FRAMESTATS: &str = r"---PROFILEDATA---
Flags,IntendedVsync,Vsync,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,GpuCompleted,DisplayPresentTime
0,100000000,100000000,101000000,102000000,103000000,105000000,108333333,8333333,106000000,106500000,107000000,108000000,110000000,111000000,110000000
---PROFILEDATA---";

    #[::core::prelude::v1::test]
    fn open_still_accepts_gfxinfo_frame_stats_text() {
        let directory = TestDirectory::new();
        let path = directory.path().join("framestats.txt");
        std::fs::write(&path, FRAMESTATS).unwrap();
        let loaded = import_frame_stats_blocking(path, "frame-import-test".into()).unwrap();
        assert_eq!(loaded.analysis.frames.len(), 1);
        assert_eq!(loaded.session.source, FrameSource::Gfxinfo);
        assert_eq!(loaded.warnings.len(), 1);
        assert!(loaded.warnings[0].contains("framestats file"));
    }

    #[::core::prelude::v1::test]
    fn opened_file_uses_same_scoped_evidence_as_headless_not_gui_history_id() {
        use aps_analysis::frame::FrameScope;
        let directory = TestDirectory::new();
        let stats = directory.path().join("framestats.txt");
        std::fs::write(&stats, FRAMESTATS).unwrap();
        for path in [stats, exported(directory.path())] {
            let bytes = std::fs::read(&path).unwrap();
            let offline = aps_analysis::frame::FrameReport::from_bytes(&bytes).unwrap();
            let gui = import_frame_stats_blocking(path, "local-history-id".into()).unwrap();
            let digest = gui.review_file_digest.unwrap();
            assert_eq!(offline.digest(), digest);
            assert_eq!(gui.session.id, "local-history-id");
            for selection in [None, Some(gui.frames[0].frame_id)] {
                let request =
                    ai::analysis_request_for_file(&gui.analysis, selection, digest).unwrap();
                let evidence = offline
                    .analysis_evidence(selection.map_or(FrameScope::Report, FrameScope::Selected))
                    .unwrap();
                assert_eq!(request.evidence[0].payload, evidence.payload);
                assert_eq!(request.evidence[0].id, evidence.id);
            }
        }
    }

    #[::core::prelude::v1::test]
    fn imported_framestats_identity_is_the_decoded_bytes_not_a_replacement_path() {
        let directory = TestDirectory::new();
        let path = directory.path().join("framestats.txt");
        std::fs::write(&path, FRAMESTATS).unwrap();
        let parsed_bytes = read_frame_stats(&path, MAX_IMPORT_BYTES).unwrap();
        let expected =
            SourceDigest::hash_reader(parsed_bytes.as_slice(), MAX_IMPORT_BYTES).unwrap();
        let loaded =
            import_frame_stats_snapshot(&path, &parsed_bytes, "bound-session".into()).unwrap();
        assert_eq!(loaded.session.imported_file_sha256, Some(expected.to_hex()));
        assert_eq!(loaded.frames[0].session_id, "bound-session");
        let replacement = FRAMESTATS.replace("100000000", "100000001");
        assert_eq!(FRAMESTATS.len(), replacement.len());
        std::fs::write(&path, replacement).unwrap();
        let result = import_frame_stats_snapshot(&path, &parsed_bytes, "bound-session".into());
        assert!(matches!(result, Err(error) if error == "FrameStats input changed during import"));
    }

    #[::core::prelude::v1::test]
    fn framestats_reader_rejects_growth_beyond_its_hard_byte_boundary() {
        assert_eq!(read_frame_stats_bytes(&b"abc"[..], 3).unwrap(), b"abc");
        assert!(read_frame_stats_bytes(&b"abcd"[..], 3).is_err());
    }

    #[::core::prelude::v1::test]
    fn imported_perfetto_history_accepts_only_the_original_analyzed_bytes() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        if !root
            .join("build/perfetto-tools")
            .join(if cfg!(windows) {
                "trace_processor_shell.exe"
            } else {
                "trace_processor_shell"
            })
            .is_file()
        {
            return;
        }
        let directory = TestDirectory::new();
        let trace = directory.path().join("original.pftrace");
        std::fs::write(
            &trace,
            include_bytes!("../../aps-perfetto/tests/fixtures/frame-probes.pftrace"),
        )
        .unwrap();
        let loaded =
            import_perfetto_blocking(trace.clone(), Some(directory.path().to_owned())).unwrap();
        let digest = loaded.trace_digest.unwrap();
        assert_eq!(loaded.session.imported_file_sha256, Some(digest.to_hex()));
        assert!(loaded.artifact.unwrap().verify_current_trace().is_ok());
        let store = sessions::FrameSessionStore::new(directory.path().join("sessions"));
        store
            .save_with_trace(&loaded.session, &loaded.frames, Some(digest))
            .unwrap();
        assert_eq!(
            store.verify_trace(&loaded.session.id, &trace).unwrap(),
            digest
        );
        std::fs::write(&trace, b"replacement bytes").unwrap();
        assert!(store.verify_trace(&loaded.session.id, &trace).is_err());
    }
}
