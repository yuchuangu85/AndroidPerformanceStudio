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
mod export;
mod model;
mod page;
mod perfetto;
mod sessions;

use crate::notifications::{NoticeSnapshot, NoticeSource};
use aps_adb::{AdbDevice, AndroidApplicationProcess};
use aps_ai::AnalysisRequest;
use aps_contracts::SourceDigest;
use aps_frame::analysis::AnalyzedFrame;
use aps_frame::{
    FrameAnalysisResult, FrameCaptureSession, FrameJankAnalyzer, FrameSample, FrameSource,
    FrameSourceCapabilities, FrameTimelineCapabilities, FrameViewFilter,
};
use aps_platform::CancellationToken;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{prelude::*, *};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const POLL_INTERVAL: Duration = Duration::from_millis(250);
const DEFAULT_FRAME_TIMELINE_CAPTURE_MILLIS: u64 = 10_000;
const MAX_IMPORT_BYTES: u64 = 512 * 1024 * 1024;

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
    pub(in crate::frame) filtered_analysis: Option<Arc<FrameAnalysisResult>>,
    pub(in crate::frame) imported_file_name: Option<String>,
    pub(in crate::frame) observed_refresh_rates_hz: Vec<f64>,
    pub(in crate::frame) selected_frame_id: Option<u64>,
    pub(in crate::frame) perfetto_trace_file: Option<PathBuf>,
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
    pub(in crate::frame) viewer: crate::trace::viewer::ViewerServer,
    pub(in crate::frame) studio: Option<WeakEntity<crate::shell::Studio>>,
    pub(in crate::frame) ui: page::PageUi,
    pub(in crate::frame) language_generation: u64,
    pub(in crate::frame) import_generation: u64,
    pub(in crate::frame) capture_generation: u64,
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
            imported_file_name: None,
            observed_refresh_rates_hz: Vec::new(),
            selected_frame_id: None,
            perfetto_trace_file: None,
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
            viewer: crate::trace::viewer::ViewerServer::new(9418),
            studio: None,
            ui: page::PageUi::new(window, cx),
            language_generation: 0,
            import_generation: 0,
            capture_generation: 0,
        };
        this
    }

    pub fn set_studio(&mut self, studio: WeakEntity<crate::shell::Studio>) {
        self.studio = Some(studio);
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
        self.error = None;
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
        cx.notify();
    }

    // ---------------- device / process discovery ----------------

    pub(crate) fn refresh_devices(&mut self, cx: &mut Context<Self>) {
        if self.is_capturing || self.is_refreshing_devices {
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
                        this.artifact = None;
                        this.current_session = Some(loaded.session.clone());
                        this.apply_analysis(loaded.analysis, cx);
                        this.operation_status = Some(model::FrameOperationStatus::ImportedFrames {
                            frame_count: this
                                .analysis
                                .as_ref()
                                .map_or(0, |analysis| analysis.frames.len()),
                        });
                        this.warnings = loaded.warnings;
                        this.error = None;
                        this.persist_session(loaded.session, loaded.frames, cx);
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
                        this.artifact = loaded.artifact.clone();
                        this.current_session = Some(loaded.session.clone());
                        this.apply_analysis(loaded.analysis, cx);
                        this.operation_status = Some(model::FrameOperationStatus::ImportedFrames {
                            frame_count: this
                                .analysis
                                .as_ref()
                                .map_or(0, |analysis| analysis.frames.len()),
                        });
                        this.warnings = loaded.warnings;
                        this.error = None;
                        this.persist_session(loaded.session, loaded.frames, cx);
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
        let (Some(analysis), Some(mut session)) =
            (self.analysis.clone(), self.current_session.clone())
        else {
            return;
        };
        session.perfetto_trace_file = Some(path.display().to_string());
        self.current_session = Some(session.clone());
        self.perfetto_trace_file = Some(path);
        self.error = None;
        let frames: Vec<FrameSample> = analysis
            .frames
            .iter()
            .map(|frame| frame.sample.clone())
            .collect();
        self.persist_session(session, frames, cx);
        cx.notify();
    }

    // ---------------- analysis/state ----------------

    fn apply_analysis(&mut self, analysis: FrameAnalysisResult, cx: &mut Context<Self>) {
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
                        this.analysis = None;
                        this.filtered_analysis = None;
                        this.selected_frame_id = None;
                        this.perfetto_trace_file = None;
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
                        this.persist_session(session, frames, cx);
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
        self.is_loading = true;
        self.import_generation += 1;
        let generation = self.import_generation;
        self.error = None;
        self.operation_status = Some(model::FrameOperationStatus::Capturing {
            package_name: process.package_name.clone(),
            source: "Perfetto FrameTimeline".to_owned(),
            frame_count: None,
        });
        cx.notify();
        let data_dir = self.data_dir.clone();
        let task = cx.background_executor().spawn(async move {
            let captured = capture::capture_frame_timeline(
                &serial,
                &process,
                DEFAULT_FRAME_TIMELINE_CAPTURE_MILLIS,
                data_dir.clone(),
            )
            .await?;
            let raw_artifact = artifact::FrameArtifact::captured(
                &captured.trace_file,
                &serial,
                process.pid,
                &process.package_name,
            )?;
            if let Some(dir) = data_dir.as_deref() {
                raw_artifact.save(Some(dir))?;
            }
            let cancel = CancellationToken::new();
            let processed = perfetto::analyze_trace(&captured.trace_file, &cancel)?;
            if processed.frames.is_empty() {
                return Err("Captured trace contains no FrameTimeline rows.".to_owned());
            }
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
                observed_refresh_rates_hz: Vec::new(),
                imported_file: None,
                imported_file_sha256: None,
                imported_at_unix_ms: None,
                provenance_complete: true,
                provenance_warnings: Vec::new(),
                perfetto_trace_file: Some(captured.trace_file.display().to_string()),
                artifact: Some(final_artifact.id.clone()),
            };
            Ok::<_, String>((frames, session, final_artifact, captured.trace_file))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.import_generation != generation {
                    return;
                }
                this.is_loading = false;
                match result {
                    Ok((frames, session, final_artifact, trace_file)) => {
                        this.imported_file_name = None;
                        this.perfetto_trace_file = Some(trace_file);
                        this.artifact = Some(final_artifact);
                        this.current_session = Some(session.clone());
                        let analysis = FrameJankAnalyzer::default().analyze(&frames);
                        this.apply_analysis(analysis, cx);
                        this.operation_status = Some(model::FrameOperationStatus::CaptureStopped {
                            frame_count: frames.len(),
                        });
                        this.warnings.clear();
                        this.error = None;
                        this.persist_session(session, frames, cx);
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

    fn persist_session(
        &mut self,
        session: FrameCaptureSession,
        frames: Vec<FrameSample>,
        cx: &mut Context<Self>,
    ) {
        let store = self.session_store.clone();
        let task = cx
            .background_executor()
            .spawn(async move { store.save(&session, &frames) });
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
        let directory = export_directory(self.data_dir.as_deref());
        let dialog = cx.prompt_for_new_path(&directory, Some("frame-analysis.json"));
        cx.spawn(async move |this, cx| {
            let result = dialog.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(Some(path))) => match export::export_json(&analysis, &path) {
                    Ok(()) => {
                        this.operation_status = Some(model::FrameOperationStatus::Exported {
                            file_name: path
                                .file_name()
                                .and_then(|name| name.to_str())
                                .unwrap_or("frame-analysis.json")
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

    pub(in crate::frame) fn open_trace(&mut self, cx: &mut Context<Self>) {
        let Some(trace) = self.perfetto_trace_file.clone() else {
            return;
        };
        let timestamp = self.selected_frame().and_then(|frame| {
            frame
                .sample
                .intended_vsync_ns
                .or(frame.sample.actual_vsync_ns)
        });
        let assets = crate::perfetto_tool::viewer_assets_dir();
        if let Err(error) = self
            .viewer
            .open(&trace, timestamp, assets.as_deref(), None, None)
        {
            self.error = Some(error);
            cx.notify();
        }
    }

    pub(in crate::frame) fn inspect_layout(&mut self, cx: &mut Context<Self>) {
        let Some(studio) = self.studio.clone() else {
            return;
        };
        let Some(frame) = self.selected_frame() else {
            return;
        };
        let sample = &frame.sample;
        let Some(package_name) = sample.package_name.clone() else {
            return;
        };
        let request = crate::shell::FrameLayoutInspectionRequest {
            device_serial: self.selected_device_serial.clone(),
            package_name,
            activity_name: sample.activity_name.clone(),
            window_id: sample.window_id.clone(),
            frame_id: frame.sample.frame_id,
        };
        if self.is_capturing {
            self.stop_capture(cx);
        }
        studio
            .update(cx, |studio, cx| studio.open_frame_layout(request, cx))
            .ok();
    }

    pub(crate) fn analysis_request(&self) -> Result<AnalysisRequest, String> {
        let analysis = self
            .analysis
            .as_deref()
            .ok_or_else(|| "Import or capture frames before AI analysis".to_owned())?;
        ai::analysis_request(analysis, self.selected_frame_id)
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

fn sha256_hex(path: &Path) -> Result<String, String> {
    SourceDigest::hash_file(path, MAX_IMPORT_BYTES)
        .map(|digest| digest.to_hex())
        .map_err(|_| "Frame input could not be hashed".to_owned())
}

struct LoadedFrameStats {
    analysis: FrameAnalysisResult,
    session: FrameCaptureSession,
    frames: Vec<FrameSample>,
    warnings: Vec<String>,
    file_name: String,
    trace_path: Option<PathBuf>,
    artifact: Option<artifact::FrameArtifact>,
}

fn import_frame_stats_blocking(
    path: PathBuf,
    session_id: String,
) -> Result<LoadedFrameStats, String> {
    let metadata =
        std::fs::metadata(&path).map_err(|_| "FrameStats input does not exist".to_owned())?;
    if !metadata.is_file() || metadata.len() > MAX_IMPORT_BYTES {
        return Err("FrameStats input must be a bounded regular file".to_owned());
    }
    let text = std::fs::read_to_string(&path)
        .map_err(|_| "FrameStats input must be bounded UTF-8 text".to_owned())?;
    // File > Open accepts both a gfxinfo FrameStats capture and this app's own
    // exported frame-analysis.json report (Compose schemaVersion 3).
    let exported = analysis_json::looks_like_export(&text);
    let (frames, parse_warnings, package_name) = if exported {
        let frames = analysis_json::parse_export(&text, &session_id)?;
        let package = frames.iter().find_map(|frame| frame.package_name.clone());
        (frames, Vec::new(), package)
    } else {
        let package = Some("imported".to_owned());
        let parsed = aps_frame::parse_gfxinfo(&text, &session_id, package.as_deref(), true)?;
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
    let sha256 = sha256_hex(&path)?;
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
        imported_file_sha256: Some(sha256),
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
        analysis,
        session,
        frames,
        warnings,
        file_name,
        trace_path: None,
        artifact: None,
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
    if let Some(dir) = data_dir.as_deref() {
        raw_artifact.save(Some(dir))?;
    }
    let cancel = CancellationToken::new();
    let processed = perfetto::analyze_trace(&path, &cancel)?;
    if processed.frames.is_empty() {
        return Err(format!("No FrameTimeline rows were found in {file_name}."));
    }
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
        perfetto_trace_file: Some(path.display().to_string()),
        artifact: Some(final_artifact.id.clone()),
    };
    let analysis = FrameJankAnalyzer::default().analyze(&frames);
    Ok(LoadedFrameStats {
        analysis,
        session,
        frames,
        warnings: Vec::new(),
        file_name,
        trace_path: Some(path),
        artifact: Some(final_artifact),
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
        let process_ready = self.selected_process_pid.is_some();
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
                !busy && process_ready,
                TextKey::FrameCaptureFrametimeline,
                FrameMenuCommand::CaptureFrameTimeline,
            );
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
                has_package && self.studio.is_some(),
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

#[cfg(test)]
mod tests {
    use super::*;
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
}
