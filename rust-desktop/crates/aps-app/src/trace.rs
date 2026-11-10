//! Trace Analyzer workspace: Compose perfetto-viewer parity for the Trace route.
//!
//! The workspace imports one explicit local trace, keeps a warm Trace Processor
//! session for diagnostics/search, persists recent captured sessions and
//! bookmarks, runs the Compose diagnostic catalog/presets and opens the external
//! Perfetto UI. Trace content never leaves the machine without the existing AI
//! preflight and its explicit approvals.

mod ai;
mod capture;
mod capture_page;
mod export;
mod model;
mod page;
mod sessions;
pub(crate) mod viewer;

use aps_ai::AnalysisRequest;
use aps_perfetto::{
    DiagnosticCategory, DiagnosticFindingLevel, DiagnosticMetricKind, DiagnosticPreset,
    DiagnosticQuery, DiagnosticResult, QueryPage, QueryPreset, SearchHit, TraceProcessor,
    TraceSession,
};
use aps_platform::CancellationToken;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{prelude::*, *};
use std::{
    collections::{BTreeSet, HashMap},
    path::{Path, PathBuf},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

const RECENT_PAGE: usize = 25;
const SEARCH_HIT_LIMIT: usize = 200;
const MISSING_VALUE: &str = "—";

pub struct TraceWorkspace {
    pub(in crate::trace) language: Language,
    pub(in crate::trace) data_dir: Option<PathBuf>,
    pub(in crate::trace) session_index: sessions::SessionIndex,
    pub(in crate::trace) bookmark_index: sessions::BookmarkIndex,
    pub(in crate::trace) sessions: Vec<sessions::SessionRecord>,
    pub(in crate::trace) workspace_sessions: Vec<model::TraceWorkspaceSession>,
    pub(in crate::trace) sessions_loaded: bool,
    pub(in crate::trace) sessions_error: Option<String>,
    pub(in crate::trace) session_search: String,
    pub(in crate::trace) selected_session: Option<String>,
    pub(in crate::trace) active_trace: Option<sessions::TraceArtifactRecord>,
    pub(in crate::trace) active_trace_path: Option<PathBuf>,
    pub(in crate::trace) workspace_artifact: Option<model::TraceWorkspaceArtifact>,
    pub(in crate::trace) trace_session: Option<Arc<TraceSession>>,
    pub(in crate::trace) tool: Option<TraceProcessor>,
    pub(in crate::trace) tool_error: Option<String>,
    pub(in crate::trace) importing: bool,
    pub(in crate::trace) picking: bool,
    pub(in crate::trace) importing_cancel: CancellationToken,
    pub(in crate::trace) outcomes: HashMap<String, DiagnosticOutcome>,
    pub(in crate::trace) running_queries: BTreeSet<String>,
    pub(in crate::trace) selected_query: Option<String>,
    pub(in crate::trace) selected_preset: String,
    pub(in crate::trace) preset_running: bool,
    pub(in crate::trace) insights: Vec<model::TraceInsightEvidence>,
    pub(in crate::trace) result_table: Option<model::TraceResultTable>,
    pub(in crate::trace) presets: Vec<model::TraceDiagnosticPresetOption>,
    pub(in crate::trace) insight_filter: model::TraceInsightFilter,
    pub(in crate::trace) detail_tab: model::TraceDetailTab,
    pub(in crate::trace) trace_search_query: String,
    pub(in crate::trace) searched_term: Option<String>,
    pub(in crate::trace) search_hits: Vec<model::TraceSearchHit>,
    pub(in crate::trace) search_running: bool,
    pub(in crate::trace) search_error: Option<String>,
    pub(in crate::trace) bookmarks: Vec<sessions::BookmarkRecord>,
    pub(in crate::trace) workspace_bookmarks: Vec<model::TraceWorkspaceBookmark>,
    pub(in crate::trace) capture: capture::Capture,
    pub(in crate::trace) capture_visible: bool,
    pub(in crate::trace) viewer: viewer::ViewerServer,
    pub(in crate::trace) status: Option<String>,
    pub(in crate::trace) error: Option<String>,
    pub(in crate::trace) studio: Option<WeakEntity<crate::shell::Studio>>,
    pub(in crate::trace) ui: page::PageUi,
    pub(in crate::trace) trace_generation: u64,
    pub(in crate::trace) preset_generation: u64,
    pub(in crate::trace) search_generation: u64,
    pub(in crate::trace) sessions_generation: u64,
    pub(in crate::trace) bookmark_generation: u64,
}

/// One diagnostic query's published outcome (result or a localized failure).
#[derive(Clone, Debug)]
pub(in crate::trace) struct DiagnosticOutcome {
    pub result: Option<DiagnosticResult>,
    pub error: Option<String>,
}

impl TraceWorkspace {
    pub fn new(
        language: Language,
        settings_path: Option<&Path>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let data_dir = settings_path
            .and_then(Path::parent)
            .map(|root| root.join("trace-analyzer"));
        let sessions_root = data_dir.clone().unwrap_or_default();
        let mut this = Self {
            language,
            data_dir: data_dir.clone(),
            session_index: sessions::SessionIndex::new(sessions_root.join("sessions")),
            bookmark_index: sessions::BookmarkIndex::new(sessions_root.join("sessions")),
            sessions: Vec::new(),
            workspace_sessions: Vec::new(),
            sessions_loaded: false,
            sessions_error: None,
            session_search: String::new(),
            selected_session: None,
            active_trace: None,
            active_trace_path: None,
            workspace_artifact: None,
            trace_session: None,
            tool: None,
            tool_error: None,
            importing: false,
            picking: false,
            importing_cancel: CancellationToken::new(),
            outcomes: HashMap::new(),
            running_queries: BTreeSet::new(),
            selected_query: None,
            selected_preset: aps_perfetto::CORE_PRESET_ID.to_owned(),
            preset_running: false,
            insights: Vec::new(),
            result_table: None,
            presets: Vec::new(),
            insight_filter: model::TraceInsightFilter::All,
            detail_tab: model::TraceDetailTab::Result,
            trace_search_query: String::new(),
            searched_term: None,
            search_hits: Vec::new(),
            search_running: false,
            search_error: None,
            bookmarks: Vec::new(),
            workspace_bookmarks: Vec::new(),
            capture: capture::Capture::new(data_dir),
            capture_visible: false,
            viewer: viewer::ViewerServer::new(9417),
            status: None,
            error: None,
            studio: None,
            ui: page::PageUi::new(window, cx),
            trace_generation: 0,
            preset_generation: 0,
            search_generation: 0,
            sessions_generation: 0,
            bookmark_generation: 0,
        };
        this.rebuild_presets();
        this.load_sessions(cx);
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
        self.capture.set_language(language, window, cx);
        self.rebuild_presets();
        self.rebuild_insights();
        self.rebuild_bookmarks();
        self.rebuild_sessions();
        self.ui.set_language(language, window, cx);
        cx.notify();
    }

    fn failure(&self, error: impl std::fmt::Display) -> String {
        self.language
            .format(TextKey::Failed, &[("value1", &format!("{error}"))])
    }

    fn rebuild_presets(&mut self) {
        self.presets = aps_perfetto::diagnostic_presets()
            .iter()
            .map(|preset| model::TraceDiagnosticPresetOption {
                id: preset.id.to_owned(),
                title: self.preset_title(preset),
                query_ids: preset.query_ids.iter().map(|id| (*id).to_owned()).collect(),
            })
            .collect();
    }

    fn preset_title(&self, preset: &DiagnosticPreset) -> String {
        let key = match preset.id {
            "core" => Some(TextKey::TracePresetCore),
            "jank" => Some(TextKey::TracePresetJank),
            "scheduling" => Some(TextKey::TracePresetScheduling),
            "binder" => Some(TextKey::TracePresetBinder),
            "io" => Some(TextKey::TracePresetIo),
            "memory" => Some(TextKey::TracePresetMemory),
            "power" => Some(TextKey::TracePresetPower),
            "all" => Some(TextKey::TracePresetAll),
            _ => None,
        };
        key.map_or_else(
            || preset.title.to_owned(),
            |key| self.language.text(key).to_owned(),
        )
    }

    fn load_sessions(&mut self, cx: &mut Context<Self>) {
        self.sessions_generation += 1;
        let generation = self.sessions_generation;
        let index = self.session_index.clone();
        let task = cx
            .background_executor()
            .spawn(async move { index.list(RECENT_PAGE) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.sessions_generation != generation {
                    return;
                }
                this.sessions_loaded = true;
                match result {
                    Ok(records) => {
                        this.sessions = records;
                        this.sessions_error = None;
                    }
                    Err(error) => this.sessions_error = Some(error),
                }
                this.rebuild_sessions();
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn rebuild_sessions(&mut self) {
        self.workspace_sessions = self
            .sessions
            .iter()
            .map(sessions::SessionRecord::workspace_session)
            .collect();
    }

    fn rebuild_bookmarks(&mut self) {
        self.workspace_bookmarks = self
            .bookmarks
            .iter()
            .map(|bookmark| model::TraceWorkspaceBookmark {
                bookmark_id: bookmark.id.clone(),
                label: bookmark.label.clone(),
                timestamp_nanos: bookmark.timestamp_nanos,
            })
            .collect();
    }

    fn rebuild_artifact(&mut self) {
        self.workspace_artifact = self.active_trace.as_ref().map(|artifact| {
            let path = self.active_trace_path.as_deref();
            model::TraceWorkspaceArtifact {
                artifact_id: artifact.id.clone(),
                display_name: artifact.display_name.clone(),
                location_label: path
                    .map(|path| path.display().to_string())
                    .unwrap_or_default(),
                completeness_label: artifact.completeness_label.clone(),
                limitations: artifact.limitations.clone(),
                viewer_available: path.is_some_and(Path::is_file),
            }
        });
    }

    fn rebuild_insights(&mut self) {
        let Some(artifact) = self.active_trace.as_ref() else {
            self.insights.clear();
            return;
        };
        let artifact_id = artifact.id.clone();
        let base_limitations = artifact.limitations.clone();
        let mut insights = Vec::new();
        for query in aps_perfetto::diagnostic_queries() {
            let outcome = self.outcomes.get(query.id);
            let result = outcome.and_then(|outcome| outcome.result.as_ref());
            let finding = result.and_then(|result| aps_perfetto::evaluate_finding(query, result));
            let severity = match (
                outcome.and_then(|outcome| outcome.error.as_ref()),
                finding.as_ref(),
            ) {
                (Some(_), _) => model::TraceInsightSeverity::Warning,
                (None, Some(finding)) => severity_for(finding.level),
                (None, None) => model::TraceInsightSeverity::Info,
            };
            let mut limitations = base_limitations.clone();
            if limitations.is_empty() {
                limitations.push(
                    self.language
                        .text(TextKey::DiagnosticDataSourceLimitation)
                        .to_owned(),
                );
            }
            if finding.as_ref().is_some_and(|finding| finding.heuristic) {
                limitations.push(
                    self.language
                        .text(TextKey::TraceSeverityHeuristic)
                        .to_owned(),
                );
            }
            insights.push(model::TraceInsightEvidence {
                query_id: query.id.to_owned(),
                artifact_id: artifact_id.clone(),
                title: self.query_title(query),
                result_rows_summary: result.map(|result| {
                    self.language.format(
                        TextKey::DiagnosticResultRows,
                        &[("value1", &result.rows.len())],
                    )
                }),
                limitations,
                error: outcome.and_then(|outcome| outcome.error.clone()),
                timestamp_nanos: result.and_then(first_timeline_timestamp),
                severity,
                category: workspace_category(query),
                aggregate: finding
                    .as_ref()
                    .and_then(|finding| self.aggregate_text(finding)),
            });
        }
        self.insights = insights;
        self.rebuild_result_table();
    }

    fn rebuild_result_table(&mut self) {
        let (Some(artifact), Some(query_id)) =
            (self.active_trace.as_ref(), self.selected_query.clone())
        else {
            self.result_table = None;
            return;
        };
        let Some(query) = aps_perfetto::diagnostic_query(&query_id) else {
            self.result_table = None;
            return;
        };
        let Some(result) = self
            .outcomes
            .get(&query_id)
            .and_then(|outcome| outcome.result.as_ref())
        else {
            self.result_table = None;
            return;
        };
        let title = self.query_title(query);
        self.result_table = Some(self.workspace_table(artifact.id.clone(), query, title, result));
    }

    fn workspace_table(
        &self,
        artifact_id: String,
        query: &DiagnosticQuery,
        title: String,
        result: &DiagnosticResult,
    ) -> model::TraceResultTable {
        let timestamp_column = timeline_timestamp_column(query);
        let rows: Vec<Vec<String>> = result
            .rows
            .iter()
            .map(|row| {
                row.iter()
                    .map(|value| value.clone().unwrap_or_else(|| MISSING_VALUE.to_owned()))
                    .collect()
            })
            .collect();
        let row_timestamps_nanos = result
            .rows
            .iter()
            .map(|row| {
                timestamp_column.and_then(|index| {
                    row.get(index)
                        .and_then(|value| value.as_deref())
                        .and_then(|value| value.parse::<i64>().ok())
                        .filter(|value| *value >= 0)
                })
            })
            .collect();
        model::TraceResultTable {
            query_id: query.id.to_owned(),
            artifact_id,
            title,
            description: query.description.to_owned(),
            sql: query.sql.to_owned(),
            schema_version: result.schema_version.to_owned(),
            columns: result.columns.clone(),
            rows,
            row_timestamps_nanos,
            truncated: result.truncated,
        }
    }

    fn query_title(&self, query: &DiagnosticQuery) -> String {
        query_title_key(query.id).map_or_else(
            || query.title.to_owned(),
            |key| self.language.text(key).to_owned(),
        )
    }

    fn aggregate_text(&self, finding: &aps_perfetto::DiagnosticFinding) -> Option<String> {
        if finding.level == DiagnosticFindingLevel::NoData {
            return Some(self.language.text(TextKey::TraceMetricNoData).to_owned());
        }
        let number = format_metric_number(finding.metric.value);
        let key = match finding.metric.kind {
            DiagnosticMetricKind::JankyFrames => TextKey::TraceMetricJankyFrames,
            DiagnosticMetricKind::MaxLatencyMs => TextKey::TraceMetricMaxLatency,
            DiagnosticMetricKind::MaxBlockedMs => TextKey::TraceMetricMaxBlocked,
            DiagnosticMetricKind::MaxWakeupUs => TextKey::TraceMetricMaxWakeup,
            DiagnosticMetricKind::PeakRssKb => TextKey::TraceMetricPeakRss,
            DiagnosticMetricKind::TopCpuMs => TextKey::TraceMetricTopCpu,
            DiagnosticMetricKind::TopFrequencyMhz => TextKey::TraceMetricTopFrequency,
            DiagnosticMetricKind::TopStateMs => TextKey::TraceMetricTopState,
            DiagnosticMetricKind::Rows => return None,
        };
        let headline = if finding.metric.kind == DiagnosticMetricKind::JankyFrames {
            self.language.format(
                key,
                &[
                    ("value1", &number),
                    (
                        "value2",
                        &finding
                            .metric
                            .total
                            .map(format_metric_number)
                            .unwrap_or_else(|| MISSING_VALUE.to_owned()),
                    ),
                ],
            )
        } else {
            self.language.format(key, &[("value1", &number)])
        };
        Some(match finding.metric.subject.as_deref() {
            Some(subject) if !headline.is_empty() => self.language.format(
                TextKey::TraceMetricSubject,
                &[("value1", &headline), ("value2", &subject)],
            ),
            _ => headline,
        })
    }

    // ---------------- trace import / lifecycle ----------------

    pub(crate) fn pick_trace(&mut self, cx: &mut Context<Self>) {
        if self.picking || self.importing {
            return;
        }
        self.picking = true;
        let prompt: SharedString = self.language.text(TextKey::OpenPerfettoTrace).into();
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(prompt),
        });
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| {
                this.picking = false;
                match result {
                    Ok(Ok(Some(paths))) if paths.len() == 1 => {
                        this.import_trace(paths[0].clone(), cx)
                    }
                    Ok(Err(error)) => {
                        this.error = Some(this.failure(error));
                    }
                    _ => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Shell entry point for Home menu/quick-action trace imports.
    pub(crate) fn open_trace_path(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        self.import_trace(path, cx);
    }

    fn import_trace(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        if self.importing {
            return;
        }
        self.importing = true;
        self.error = None;
        self.status = Some(
            self.language
                .text(TextKey::TraceWorkspaceRunning)
                .to_owned(),
        );
        self.trace_generation += 1;
        let generation = self.trace_generation;
        self.close_trace_state();
        let previous_cancel = std::mem::take(&mut self.importing_cancel);
        previous_cancel.cancel();
        let cancel = CancellationToken::new();
        self.importing_cancel = cancel.clone();
        let tool = self.tool.clone();
        let background_path = path.clone();
        cx.notify();
        let task = cx.background_executor().spawn(async move {
            let tool = match tool {
                Some(tool) => tool,
                None => crate::perfetto_tool::locate(&cancel)?,
            };
            let session = TraceSession::open(tool.clone(), &background_path, &cancel)
                .map_err(|error| error.to_string())?;
            let duration_nanos = trace_duration_nanos(&session, &cancel);
            Ok::<_, String>((tool, Arc::new(session), duration_nanos))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.trace_generation != generation {
                    return;
                }
                this.importing = false;
                this.status = None;
                match result {
                    Ok((tool, session, duration_nanos)) => {
                        this.tool = Some(tool);
                        this.tool_error = None;
                        this.publish_trace(path, session, duration_nanos, cx);
                    }
                    Err(error) => this.error = Some(this.failure(error)),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn publish_trace(
        &mut self,
        path: PathBuf,
        session: Arc<TraceSession>,
        duration_nanos: u64,
        cx: &mut Context<Self>,
    ) {
        let artifact_id = session.source_digest().to_hex();
        let display_name = display_name_from_path(&path);
        let artifact = sessions::TraceArtifactRecord {
            id: artifact_id.clone(),
            display_name: display_name.clone(),
            completeness_label: "COMPLETE".to_owned(),
            limitations: Vec::new(),
            warnings: Vec::new(),
        };
        self.tool_error = None;
        self.active_trace = Some(artifact);
        self.active_trace_path = Some(path.clone());
        self.trace_session = Some(session);
        self.outcomes.clear();
        self.insights.clear();
        self.result_table = None;
        self.selected_query = None;
        self.running_queries.clear();
        self.preset_running = false;
        self.trace_search_query.clear();
        self.searched_term = None;
        self.search_hits.clear();
        self.search_error = None;
        self.bookmarks.clear();
        self.workspace_bookmarks.clear();
        self.rebuild_artifact();
        self.rebuild_insights();
        self.load_bookmarks(&artifact_id, cx);
        // The imported trace identity and duration live in the footer status
        // bar; the page renders no separate header notice row.
        self.status = Some(format!(
            "{display_name} · {}",
            model::format_trace_duration_nanos(Some(duration_nanos as i64))
        ));
        self.open_external_viewer(None, cx);
        cx.notify();
    }

    fn close_trace_state(&mut self) {
        self.importing_cancel.cancel();
        self.trace_session = None;
        self.active_trace = None;
        self.active_trace_path = None;
        self.workspace_artifact = None;
        self.outcomes.clear();
        self.insights.clear();
        self.result_table = None;
        self.selected_query = None;
        self.bookmarks.clear();
        self.workspace_bookmarks.clear();
        self.search_hits.clear();
        self.searched_term = None;
        self.viewer.stop();
    }

    pub(in crate::trace) fn close_trace(&mut self, cx: &mut Context<Self>) {
        self.trace_generation += 1;
        self.preset_generation += 1;
        self.search_generation += 1;
        self.close_trace_state();
        self.status = None;
        self.error = None;
        cx.notify();
    }

    fn load_bookmarks(&mut self, artifact_id: &str, cx: &mut Context<Self>) {
        self.bookmark_generation += 1;
        let generation = self.bookmark_generation;
        let index = self.bookmark_index.clone();
        let artifact_id = artifact_id.to_owned();
        let task = cx
            .background_executor()
            .spawn(async move { index.list(&artifact_id) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.bookmark_generation != generation {
                    return;
                }
                match result {
                    Ok(records) => {
                        this.bookmarks = records;
                        this.rebuild_bookmarks();
                    }
                    Err(error) => this.error = Some(this.failure(error)),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // ---------------- sessions ----------------

    pub(in crate::trace) fn session_search_changed(
        &mut self,
        value: String,
        cx: &mut Context<Self>,
    ) {
        self.session_search = value;
        cx.notify();
    }

    pub(in crate::trace) fn select_session(&mut self, id: String, cx: &mut Context<Self>) {
        self.selected_session = Some(id);
        cx.notify();
    }

    pub(in crate::trace) fn open_session(&mut self, id: String, cx: &mut Context<Self>) {
        let Some(record) = self.sessions.iter().find(|record| record.id == id) else {
            return;
        };
        let path = record.trace_path.clone();
        self.selected_session = Some(id);
        self.import_trace(path, cx);
    }

    pub(in crate::trace) fn delete_session(&mut self, id: String, cx: &mut Context<Self>) {
        let index = self.session_index.clone();
        let delete_id = id.clone();
        let task = cx
            .background_executor()
            .spawn(async move { index.delete(&delete_id) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(()) => {
                        if this.selected_session.as_deref() == Some(id.as_str()) {
                            this.selected_session = None;
                        }
                        this.load_sessions(cx);
                    }
                    Err(error) => this.error = Some(this.failure(error)),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // ---------------- diagnostics ----------------

    pub(in crate::trace) fn select_preset(&mut self, id: String, cx: &mut Context<Self>) {
        if self.presets.iter().any(|preset| preset.id == id) {
            self.selected_preset = id;
            cx.notify();
        }
    }

    pub(in crate::trace) fn run_diagnostic(&mut self, query_id: String, cx: &mut Context<Self>) {
        if self.trace_session.is_none() {
            self.error = Some(
                self.language
                    .text(TextKey::TraceWorkspaceNoTrace)
                    .to_owned(),
            );
            cx.notify();
            return;
        }
        if self.running_queries.contains(&query_id) || self.preset_running {
            return;
        }
        let Some(query) = aps_perfetto::diagnostic_query(&query_id) else {
            self.error = Some(format!("Unknown diagnostic query: {query_id}"));
            cx.notify();
            return;
        };
        let Some(session) = self.trace_session.clone() else {
            return;
        };
        let cancel = self.importing_cancel.clone();
        self.running_queries.insert(query_id.clone());
        self.status = Some(
            self.language
                .text(TextKey::TraceWorkspaceRunning)
                .to_owned(),
        );
        self.error = None;
        cx.notify();
        let task = cx
            .background_executor()
            .spawn(async move { session.diagnostic(query, &cancel) });
        let generation = self.trace_generation;
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.trace_generation != generation {
                    return;
                }
                this.running_queries.remove(&query_id);
                match result {
                    Ok(result) => {
                        this.outcomes.insert(
                            query_id.clone(),
                            DiagnosticOutcome {
                                result: Some(result),
                                error: None,
                            },
                        );
                    }
                    Err(error) => {
                        this.outcomes.insert(
                            query_id.clone(),
                            DiagnosticOutcome {
                                result: None,
                                error: Some(this.failure(error)),
                            },
                        );
                    }
                }
                if this.selected_query.is_none()
                    || this.selected_query.as_deref() == Some(query_id.as_str())
                {
                    this.selected_query = Some(query_id.clone());
                }
                this.rebuild_insights();
                this.status = None;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::trace) fn run_preset(&mut self, cx: &mut Context<Self>) {
        if self.trace_session.is_none() {
            self.error = Some(
                self.language
                    .text(TextKey::TraceWorkspaceNoTrace)
                    .to_owned(),
            );
            cx.notify();
            return;
        }
        if self.preset_running {
            return;
        }
        let Some(preset) = aps_perfetto::diagnostic_presets()
            .iter()
            .find(|preset| preset.id == self.selected_preset)
        else {
            return;
        };
        let query_ids: Vec<&'static str> = preset.query_ids.to_vec();
        self.preset_running = true;
        self.preset_generation += 1;
        let generation = self.preset_generation;
        self.status = Some(
            self.language
                .text(TextKey::TraceWorkspaceRunning)
                .to_owned(),
        );
        self.error = None;
        cx.notify();
        self.run_preset_query(generation, query_ids, 0, 0, cx);
    }

    fn run_preset_query(
        &mut self,
        generation: u64,
        query_ids: Vec<&'static str>,
        index: usize,
        succeeded: usize,
        cx: &mut Context<Self>,
    ) {
        if self.preset_generation != generation {
            return;
        }
        if index >= query_ids.len() {
            self.preset_running = false;
            let failed = query_ids.len() - succeeded;
            let preset_title = self
                .presets
                .iter()
                .find(|preset| preset.id == self.selected_preset)
                .map(|preset| preset.title.clone())
                .unwrap_or_else(|| self.selected_preset.clone());
            self.status = Some(self.language.format(
                TextKey::TracePresetCompleted,
                &[
                    ("value1", &preset_title),
                    ("value2", &succeeded),
                    ("value3", &failed),
                ],
            ));
            cx.notify();
            return;
        }
        let query_id = query_ids[index];
        let Some(query) = aps_perfetto::diagnostic_query(query_id) else {
            self.run_preset_query(generation, query_ids, index + 1, succeeded, cx);
            return;
        };
        let Some(session) = self.trace_session.clone() else {
            self.preset_running = false;
            return;
        };
        let cancel = self.importing_cancel.clone();
        self.running_queries.insert(query_id.to_owned());
        cx.notify();
        let task = cx
            .background_executor()
            .spawn(async move { session.diagnostic(query, &cancel) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.running_queries.remove(query_id);
                if this.preset_generation != generation {
                    return;
                }
                let mut next_succeeded = succeeded;
                match result {
                    Ok(result) => {
                        next_succeeded += 1;
                        this.outcomes.insert(
                            query_id.to_owned(),
                            DiagnosticOutcome {
                                result: Some(result),
                                error: None,
                            },
                        );
                    }
                    Err(error) => {
                        this.outcomes.insert(
                            query_id.to_owned(),
                            DiagnosticOutcome {
                                result: None,
                                error: Some(this.failure(error)),
                            },
                        );
                    }
                }
                this.rebuild_insights();
                cx.notify();
                this.run_preset_query(generation, query_ids, index + 1, next_succeeded, cx);
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::trace) fn view_insight_result(
        &mut self,
        query_id: String,
        cx: &mut Context<Self>,
    ) {
        if aps_perfetto::diagnostic_query(&query_id).is_some() {
            self.selected_query = Some(query_id);
            self.detail_tab = model::TraceDetailTab::Result;
            self.rebuild_result_table();
            cx.notify();
        }
    }

    pub(in crate::trace) fn set_insight_filter(
        &mut self,
        filter: model::TraceInsightFilter,
        cx: &mut Context<Self>,
    ) {
        self.insight_filter = filter;
        cx.notify();
    }

    pub(in crate::trace) fn set_detail_tab(
        &mut self,
        tab: model::TraceDetailTab,
        cx: &mut Context<Self>,
    ) {
        self.detail_tab = tab;
        cx.notify();
    }

    // ---------------- slice search ----------------

    pub(in crate::trace) fn trace_search_changed(&mut self, value: String, cx: &mut Context<Self>) {
        self.trace_search_query = value;
        cx.notify();
    }

    pub(in crate::trace) fn run_trace_search(&mut self, cx: &mut Context<Self>) {
        if self.search_running {
            return;
        }
        let term = self.trace_search_query.trim().to_owned();
        if term.is_empty() {
            self.search_error = Some(
                self.language
                    .text(TextKey::TraceWorkspaceSearchHint)
                    .to_owned(),
            );
            cx.notify();
            return;
        }
        let Some(session) = self.trace_session.clone() else {
            self.search_error = Some(
                self.language
                    .text(TextKey::TraceWorkspaceNoTrace)
                    .to_owned(),
            );
            cx.notify();
            return;
        };
        let cancel = self.importing_cancel.clone();
        self.search_generation += 1;
        let generation = self.search_generation;
        self.search_running = true;
        self.search_error = None;
        self.searched_term = Some(term.clone());
        self.detail_tab = model::TraceDetailTab::Search;
        cx.notify();
        let task = cx
            .background_executor()
            .spawn(async move { session.search_slices(&term, SEARCH_HIT_LIMIT, &cancel) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.search_generation != generation {
                    return;
                }
                this.search_running = false;
                match result {
                    Ok(hits) => {
                        this.search_hits = hits.into_iter().map(search_hit_view).collect();
                        this.search_error = None;
                    }
                    Err(error) => {
                        this.search_hits.clear();
                        this.search_error = Some(this.failure(error));
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // ---------------- viewer / bookmarks / capture ----------------

    fn open_external_viewer(&mut self, timestamp_nanos: Option<i64>, cx: &mut Context<Self>) {
        if std::env::var_os("APS_TRACE_NO_BROWSER").is_some() {
            return;
        }
        let Some(path) = self.active_trace_path.clone() else {
            return;
        };
        let assets = crate::perfetto_tool::viewer_assets_dir();
        if let Err(error) = self
            .viewer
            .open(&path, timestamp_nanos, assets.as_deref(), None, None)
        {
            self.error = Some(self.failure(error));
            cx.notify();
        }
    }

    pub(in crate::trace) fn open_viewer(&mut self, cx: &mut Context<Self>) {
        self.open_external_viewer(None, cx);
    }

    pub(in crate::trace) fn open_in_timeline(
        &mut self,
        timestamp_nanos: i64,
        cx: &mut Context<Self>,
    ) {
        self.open_external_viewer(Some(timestamp_nanos), cx);
    }

    pub(in crate::trace) fn add_bookmark(
        &mut self,
        request: model::TraceBookmarkRequest,
        cx: &mut Context<Self>,
    ) {
        let Some(artifact) = self.active_trace.as_ref() else {
            return;
        };
        let record = sessions::BookmarkRecord {
            id: new_id("bookmark"),
            artifact_id: artifact.id.clone(),
            label: request.label,
            timestamp_nanos: request.timestamp_nanos,
            source_query_id: request.source_query_id,
            created_unix_ms: now_unix_ms(),
        };
        let index = self.bookmark_index.clone();
        let to_save = record.clone();
        let artifact_id = record.artifact_id.clone();
        let task = cx
            .background_executor()
            .spawn(async move { index.add(&to_save) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| match result {
                Ok(()) => this.load_bookmarks(&artifact_id, cx),
                Err(error) => {
                    this.error = Some(this.failure(error));
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::trace) fn delete_bookmark(
        &mut self,
        bookmark_id: String,
        cx: &mut Context<Self>,
    ) {
        let Some(artifact) = self.active_trace.as_ref() else {
            return;
        };
        let artifact_id = artifact.id.clone();
        let index = self.bookmark_index.clone();
        let task = cx
            .background_executor()
            .spawn(async move { index.delete(&bookmark_id) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| match result {
                Ok(()) => this.load_bookmarks(&artifact_id, cx),
                Err(error) => {
                    this.error = Some(this.failure(error));
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::trace) fn toggle_capture(&mut self, cx: &mut Context<Self>) {
        self.capture_visible = !self.capture_visible;
        if self.capture_visible {
            self.capture.refresh_devices(cx);
        }
        cx.notify();
    }

    /// Shell entry point for Home "Start Capture".
    pub(crate) fn open_capture_panel(&mut self, cx: &mut Context<Self>) {
        self.capture_visible = true;
        self.capture.refresh_devices(cx);
        cx.notify();
    }

    /// Primary export of the Trace page (Compose assigns Cmd+E to the session
    /// package export).
    ///
    /// Compose synthesizes a session for a trace that has no stored record; the
    /// Rust package is built from a persisted session record, so the shortcut
    /// exports the active trace's saved session (or the selected session row)
    /// and is only published for those traces.
    pub(crate) fn export_primary(&mut self, cx: &mut Context<Self>) {
        if let Some(session_id) = self.export_session_id() {
            self.export_session_package(session_id, cx);
        }
    }

    /// Session that the primary (Cmd+E) export of the active trace acts on: the
    /// saved record of the loaded trace, otherwise the session list selection.
    pub(in crate::trace) fn export_session_id(&self) -> Option<String> {
        let active_path = self.active_trace_path.as_deref();
        active_path
            .and_then(|path| {
                self.sessions
                    .iter()
                    .find(|record| record.trace_path.as_path() == path)
                    .map(|record| record.id.clone())
            })
            .or_else(|| {
                self.selected_session
                    .clone()
                    .filter(|id| self.sessions.iter().any(|record| &record.id == id))
            })
    }

    pub(crate) fn refresh_devices(&mut self, cx: &mut Context<Self>) {
        self.capture.refresh_devices(cx);
    }

    pub(crate) fn select_capture_device(&mut self, serial: Option<String>, cx: &mut Context<Self>) {
        self.capture.select_device(serial, cx);
    }

    pub(crate) fn apply_shared_target_devices(
        &mut self,
        devices: &[aps_adb::AdbDevice],
        cx: &mut Context<Self>,
    ) {
        self.capture.apply_devices(devices, cx);
    }

    pub(in crate::trace) fn start_capture(
        &mut self,
        config: capture::CaptureConfig,
        cx: &mut Context<Self>,
    ) {
        self.capture.start(config, cx);
    }

    pub(in crate::trace) fn stop_capture(&mut self, cx: &mut Context<Self>) {
        self.capture.stop(cx);
    }

    pub(in crate::trace) fn on_capture_completed(
        &mut self,
        output: capture::CaptureOutput,
        cx: &mut Context<Self>,
    ) {
        let artifact = sessions::TraceArtifactRecord {
            id: output.artifact_id.clone(),
            display_name: output.display_name.clone(),
            completeness_label: output.completeness_label.clone(),
            limitations: output.limitations.clone(),
            warnings: output.warnings.clone(),
        };
        let record = sessions::SessionRecord {
            id: output.session_id.clone(),
            title: output.display_name.clone(),
            captured_at_label: format_unix_ms(output.captured_unix_ms),
            duration_label: model::format_trace_duration_nanos(Some(output.duration_nanos as i64)),
            captured_unix_ms: output.captured_unix_ms,
            duration_nanos: output.duration_nanos,
            trace_path: output.trace_path.clone(),
            artifact: Some(artifact),
            config_summary: output.config_summary.clone(),
            device_model: Some(output.device_model.clone()),
            android_sdk: Some(output.android_sdk),
        };
        let index = self.session_index.clone();
        let to_save = record.clone();
        let task = cx
            .background_executor()
            .spawn(async move { index.save(to_save) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(record) => {
                        this.status = Some(format!(
                            "{} · {}",
                            record.title,
                            this.language.format(
                                TextKey::CompletedMb,
                                &[("value1", &format_file_mb(&record.trace_path))],
                            )
                        ));
                        this.load_sessions(cx);
                    }
                    Err(error) => this.error = Some(this.failure(error)),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::trace) fn on_capture_failed(&mut self, error: String, cx: &mut Context<Self>) {
        self.error = Some(self.failure(error));
        cx.notify();
    }

    pub(in crate::trace) fn open_captured_trace(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        self.import_trace(path, cx);
    }

    // ---------------- export / AI ----------------

    pub(in crate::trace) fn export_raw_trace(&mut self, cx: &mut Context<Self>) {
        let Some(path) = self.active_trace_path.clone() else {
            return;
        };
        let suggested = export::suggested_raw_name(&path);
        let directory = export_directory(self.data_dir.as_deref(), &path);
        let dialog = cx.prompt_for_new_path(&directory, Some(&suggested));
        let source = path.clone();
        cx.spawn(async move |this, cx| {
            let result = dialog.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(Some(destination))) => {
                    match export::export_raw_trace(&source, &destination) {
                        Ok(()) => {
                            this.status = Some(destination.display().to_string());
                            this.error = None;
                        }
                        Err(error) => this.error = Some(this.failure(error)),
                    }
                    cx.notify();
                }
                Ok(Err(error)) => {
                    this.error = Some(this.failure(error));
                    cx.notify();
                }
                _ => {}
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::trace) fn export_session_package(
        &mut self,
        session_id: String,
        cx: &mut Context<Self>,
    ) {
        let Some(record) = self
            .sessions
            .iter()
            .find(|record| record.id == session_id)
            .cloned()
        else {
            return;
        };
        let suggested = export::suggested_session_name(&record);
        let directory = export_directory(self.data_dir.as_deref(), &record.trace_path);
        let dialog = cx.prompt_for_new_path(&directory, Some(&suggested));
        let directory = directory.clone();
        let _ = &directory;
        cx.spawn(async move |this, cx| {
            let result = dialog.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(Some(destination))) => {
                    match export::export_session_package(&record, &destination) {
                        Ok(()) => {
                            this.status = Some(destination.display().to_string());
                            this.error = None;
                        }
                        Err(error) => this.error = Some(this.failure(error)),
                    }
                    cx.notify();
                }
                Ok(Err(error)) => {
                    this.error = Some(this.failure(error));
                    cx.notify();
                }
                _ => {}
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn analysis_request(&self) -> Result<AnalysisRequest, String> {
        let artifact = self
            .active_trace
            .as_ref()
            .ok_or_else(|| "Import a trace and run a diagnostic before AI analysis".to_owned())?;
        let query_id = self
            .selected_query
            .as_ref()
            .ok_or_else(|| "Select a diagnostic result before AI analysis".to_owned())?;
        let query = aps_perfetto::diagnostic_query(query_id)
            .ok_or_else(|| "Unknown diagnostic query".to_owned())?;
        let result = self
            .outcomes
            .get(query_id)
            .and_then(|outcome| outcome.result.as_ref())
            .ok_or_else(|| "Run the selected diagnostic first".to_owned())?;
        ai::analysis_request(&artifact.id, query, result)
    }

    pub(crate) fn can_analyze(&self) -> bool {
        self.analysis_request().is_ok()
    }
}

impl Render for TraceWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        page::render(self, window, cx)
    }
}

// ---------------- free helpers ----------------

fn now_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}

fn new_id(prefix: &str) -> String {
    format!("{prefix}-{}-{}", std::process::id(), now_unix_ms())
}

fn format_unix_ms(unix_ms: u64) -> String {
    let seconds = unix_ms / 1000;
    let days = (seconds / 86_400) as i64;
    let remainder = seconds % 86_400;
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}-{month:02}-{day:02}-{:02}:{:02}:{:02}Z",
        remainder / 3600,
        (remainder % 3600) / 60,
        remainder % 60
    )
}

/// Howard Hinnant's days-from-civil inverse (proleptic Gregorian, UTC).
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = (if mp < 10 { mp + 3 } else { mp - 9 }) as u32;
    (if month <= 2 { year + 1 } else { year }, month, day)
}

fn display_name_from_path(path: &Path) -> String {
    path.file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("trace")
        .to_owned()
}

fn format_file_mb(path: &Path) -> String {
    let bytes = std::fs::metadata(path)
        .map(|metadata| metadata.len())
        .unwrap_or(0);
    format!("{:.1}", bytes as f64 / (1024.0 * 1024.0))
}

fn trace_duration_nanos(session: &TraceSession, cancel: &CancellationToken) -> u64 {
    let Ok(page) = session.query(QueryPreset::TraceBounds, 0, 1, cancel) else {
        return 0;
    };
    duration_from_page(&page)
}

fn duration_from_page(page: &QueryPage) -> u64 {
    let row = page.rows.first();
    let start = row
        .and_then(|row| row.first())
        .and_then(|value| value.as_deref())
        .and_then(|value| value.parse::<i64>().ok());
    let end = row
        .and_then(|row| row.get(1))
        .and_then(|value| value.as_deref())
        .and_then(|value| value.parse::<i64>().ok());
    match (start, end) {
        (Some(start), Some(end)) if end >= start => (end - start) as u64,
        _ => 0,
    }
}

fn export_directory(data_dir: Option<&Path>, trace_path: &Path) -> PathBuf {
    data_dir
        .map(Path::to_path_buf)
        .or_else(|| trace_path.parent().map(Path::to_path_buf))
        .unwrap_or_else(|| PathBuf::from("."))
}

fn search_hit_view(hit: SearchHit) -> model::TraceSearchHit {
    model::TraceSearchHit {
        timestamp_nanos: hit.timestamp_nanos,
        duration_nanos: hit.duration_nanos,
        name: hit.name,
        thread_label: hit.thread_label,
    }
}

fn first_timeline_timestamp(result: &DiagnosticResult) -> Option<i64> {
    let column = timeline_column(&result.columns)?;
    result.rows.iter().find_map(|row| {
        row.get(column)
            .and_then(|value| value.as_deref())
            .and_then(|value| value.parse::<i64>().ok())
    })
}

pub(in crate::trace) fn timeline_timestamp_column(query: &DiagnosticQuery) -> Option<usize> {
    query.columns.iter().position(|column| {
        *column == "timestamp_ns" || *column == "ts" || column.ends_with("_timestamp_ns")
    })
}

fn timeline_column(columns: &[String]) -> Option<usize> {
    columns.iter().position(|column| {
        column == "timestamp_ns" || column == "ts" || column.ends_with("_timestamp_ns")
    })
}

fn severity_for(level: DiagnosticFindingLevel) -> model::TraceInsightSeverity {
    match level {
        DiagnosticFindingLevel::Critical => model::TraceInsightSeverity::Critical,
        DiagnosticFindingLevel::Warning => model::TraceInsightSeverity::Warning,
        DiagnosticFindingLevel::Info | DiagnosticFindingLevel::NoData => {
            model::TraceInsightSeverity::Info
        }
    }
}

fn workspace_category(query: &DiagnosticQuery) -> model::TraceInsightCategory {
    if query.id == "frame_jank" {
        return model::TraceInsightCategory::Frame;
    }
    if query.id == "main_thread_blocked" {
        return model::TraceInsightCategory::LongTask;
    }
    match query.category {
        DiagnosticCategory::Binder => model::TraceInsightCategory::Binder,
        DiagnosticCategory::Graphics => model::TraceInsightCategory::Rendering,
        DiagnosticCategory::Cpu => model::TraceInsightCategory::Cpu,
        DiagnosticCategory::Memory => model::TraceInsightCategory::Memory,
        DiagnosticCategory::Input => model::TraceInsightCategory::Input,
        DiagnosticCategory::Io | DiagnosticCategory::Runtime | DiagnosticCategory::Power => {
            model::TraceInsightCategory::Other
        }
    }
}

fn query_title_key(id: &str) -> Option<TextKey> {
    Some(match id {
        "cpu_hotspots" => TextKey::DiagnosticCpuHotspotsTitle,
        "sched_switch" => TextKey::DiagnosticSchedSwitchTitle,
        "sched_waking" => TextKey::DiagnosticSchedWakingTitle,
        "run_queue" => TextKey::DiagnosticRunQueueTitle,
        "main_thread_blocked" => TextKey::DiagnosticMainThreadBlockedTitle,
        "cpu_freq_dist" => TextKey::DiagnosticCpuFrequencyTitle,
        "binder_latency" => TextKey::DiagnosticBinderLatencyTitle,
        "binder_ipc_long_tail" => TextKey::DiagnosticBinderIpcLongTailTitle,
        "binder_system_server" => TextKey::DiagnosticBinderSystemServerTitle,
        "binder_frame_alignment" => TextKey::DiagnosticBinderFrameAlignmentTitle,
        "frame_jank" => TextKey::DiagnosticFrameJankTitle,
        "file_io_syscalls" => TextKey::DiagnosticFileIoSyscallsTitle,
        "fsync_calls" => TextKey::DiagnosticFsyncCallsTitle,
        "block_io_pressure" => TextKey::DiagnosticBlockIoPressureTitle,
        "page_faults" => TextKey::DiagnosticPageFaultsTitle,
        "sqlite_activity" => TextKey::DiagnosticSqliteActivityTitle,
        "sqlite_lock_wait" => TextKey::DiagnosticSqliteLockWaitTitle,
        "art_gc_events" => TextKey::DiagnosticArtGcEventsTitle,
        "art_allocation_churn" => TextKey::DiagnosticArtAllocationChurnTitle,
        "art_jit_dex2oat" => TextKey::DiagnosticArtJitDex2oatTitle,
        "art_class_loading" => TextKey::DiagnosticArtClassLoadingTitle,
        "art_class_verification" => TextKey::DiagnosticArtClassVerificationTitle,
        "thermal_throttling" => TextKey::DiagnosticThermalThrottlingTitle,
        "dvfs_residency" => TextKey::DiagnosticDvfsResidencyTitle,
        "gpu_frequency_residency" => TextKey::DiagnosticGpuFrequencyResidencyTitle,
        "power_rails" => TextKey::DiagnosticPowerRailsTitle,
        "power_frame_alignment" => TextKey::DiagnosticPowerFrameAlignmentTitle,
        "mem_counters" => TextKey::DiagnosticMemoryTimelineTitle,
        "input_latency" => TextKey::DiagnosticInputLatencyTitle,
        "thread_states" => TextKey::DiagnosticThreadStatesTitle,
        "wakeup_latency" => TextKey::DiagnosticWakeupLatencyTitle,
        _ => return None,
    })
}

/// Whole numbers stay whole; fractional aggregates keep one decimal.
fn format_metric_number(value: f64) -> String {
    if !value.is_finite() {
        return MISSING_VALUE.to_owned();
    }
    if value.fract() == 0.0 && value.abs() < i64::MAX as f64 {
        format!("{}", value as i64)
    } else {
        format!("{value:.1}")
    }
}

/// In-window menu bar commands for the Trace workspace. Each screen publishes
/// only the commands its own functionality supports.
#[derive(Clone, PartialEq, Eq)]
pub(crate) enum TraceMenuCommand {
    /// A Compose File row whose Rust capability does not exist yet.
    Unavailable,
    ImportTrace,
    /// Primary export: the active trace's session package (Compose Cmd+E).
    ExportPrimary,
    ExportTrace,
    /// Reopens one entry of File > Open Recent, by session id.
    OpenRecent(String),
    CloseTrace,
    RunPreset,
    RunSelectedDiagnostic,
    SearchSlices,
    ToggleCapture,
    RefreshDevices,
    ShowResultTab,
    ShowQueryTab,
    ShowSearchTab,
    OpenViewer,
    OpenInTimeline,
    CopySql,
    OpenSelectedSession,
    DeleteSelectedSession,
    ExportSessionPackage,
}

impl crate::shell::menubar::MenuHost for TraceWorkspace {
    type Command = TraceMenuCommand;

    fn menu_groups(
        &self,
        _language: aps_ui_foundation::Language,
    ) -> Vec<crate::shell::menubar::MenuGroup<Self::Command>> {
        use crate::shell::menubar::MenuGroup;

        // Same predicates the toolbar and the panels apply; the menu is built
        // when it opens, so they always describe the current workspace.
        let active = self.active_trace.is_some() && !self.importing;
        let viewer_available = self
            .workspace_artifact
            .as_ref()
            .is_some_and(|artifact| artifact.viewer_available);
        let selected_query = self.selected_query.as_deref();
        let selected_query_running =
            selected_query.is_some_and(|query_id| self.running_queries.contains(query_id));
        let selected_session_exists = self
            .selected_session
            .as_deref()
            .is_some_and(|session_id| self.sessions.iter().any(|record| record.id == session_id));
        let selected_insight = self
            .insights
            .iter()
            .find(|insight| Some(insight.query_id.as_str()) == selected_query);
        let can_open_timeline = selected_insight.is_some_and(|insight| {
            model::can_open_insight_in_timeline(insight, self.workspace_artifact.as_ref())
        });
        let copy_sql = self
            .result_table
            .as_ref()
            .is_some_and(|table| !table.sql.trim().is_empty());
        let search_enabled =
            active && !self.trace_search_query.trim().is_empty() && !self.search_running;
        // Compose keeps the ten most recent trace files in File > Open Recent;
        // the durable session catalog is the Rust equivalent. Captured traces
        // live in managed storage under content-addressed file names, so the
        // readable session title is the row label.
        let recent: Vec<(String, String)> = self
            .sessions
            .iter()
            .take(10)
            .map(|record| (record.id.clone(), record.title.clone()))
            .collect();
        let export_session = self.export_session_id();

        vec![
            MenuGroup::new(TextKey::ShellMenuFile)
                // Compose File > Open… replaces the current trace; the shared
                // action routes Cmd+O to this workspace's picker.
                .item_action_when(
                    !self.picking && !self.importing,
                    TextKey::ShellMenuOpen,
                    TraceMenuCommand::ImportTrace,
                    crate::shell::OpenActiveRouteFile,
                )
                .item_action_when(
                    !self.picking && !self.importing && !recent.is_empty(),
                    TextKey::ShellMenuRecentFiles,
                    recent
                        .first()
                        .map(|(session_id, _)| TraceMenuCommand::OpenRecent(session_id.clone()))
                        .unwrap_or(TraceMenuCommand::Unavailable),
                    crate::shell::RecentFilesActiveRoute,
                )
                .submenu(TextKey::ShellMenuOpenRecent, true, |menu| {
                    let menu = if recent.is_empty() {
                        menu.item_when(
                            false,
                            TextKey::ShellMenuNoRecent,
                            TraceMenuCommand::Unavailable,
                        )
                    } else {
                        recent.iter().fold(menu, |menu, (session_id, label)| {
                            menu.item(
                                label.clone(),
                                TraceMenuCommand::OpenRecent(session_id.clone()),
                            )
                        })
                    };
                    // Compose clears only the in-memory recent list. Here the
                    // list is the durable captured-session catalog, so clearing
                    // it would delete saved sessions; keep the row disabled.
                    menu.separator().item_when(
                        false,
                        TextKey::ShellMenuClearRecent,
                        TraceMenuCommand::Unavailable,
                    )
                })
                .separator()
                .submenu(TextKey::ShellMenuExport, true, |menu| {
                    // The package is built from a stored session; a trace that
                    // was only imported has no capture metadata to export.
                    menu.item_action_when(
                        export_session.is_some(),
                        TextKey::SessionPackageZip,
                        TraceMenuCommand::ExportPrimary,
                        crate::shell::ExportActiveRoute,
                    )
                    .item_when(
                        self.active_trace_path.is_some(),
                        TextKey::RawTracePftrace,
                        TraceMenuCommand::ExportTrace,
                    )
                })
                .item_action_when(
                    self.active_trace.is_some() || self.trace_session.is_some(),
                    TextKey::TraceWorkspaceCloseTrace,
                    TraceMenuCommand::CloseTrace,
                    crate::shell::CloseActiveRoute,
                ),
            MenuGroup::new(TextKey::ShellMenuTrace)
                .item_when(
                    self.trace_session.is_some()
                        && !self.preset_running
                        && !self.presets.is_empty(),
                    TextKey::TraceWorkspaceRunPreset,
                    TraceMenuCommand::RunPreset,
                )
                .item_when(
                    self.trace_session.is_some()
                        && selected_query.is_some()
                        && !self.preset_running
                        && !selected_query_running,
                    TextKey::TraceWorkspaceRunDiagnostic,
                    TraceMenuCommand::RunSelectedDiagnostic,
                )
                .item_when(
                    search_enabled,
                    TextKey::TraceWorkspaceSearch,
                    TraceMenuCommand::SearchSlices,
                )
                .separator()
                .item_action_when(
                    self.capture_visible || self.capture.device_ready(),
                    TextKey::TraceWorkspaceCapture,
                    TraceMenuCommand::ToggleCapture,
                    crate::shell::StartCaptureActiveRoute,
                )
                .item_when(
                    self.capture_visible && !self.capture.refresh_in_progress(),
                    TextKey::ShellRefreshDevices,
                    TraceMenuCommand::RefreshDevices,
                ),
            MenuGroup::new(TextKey::ShellMenuView)
                .check(
                    TextKey::TraceWorkspaceTabResult,
                    self.detail_tab == model::TraceDetailTab::Result,
                    TraceMenuCommand::ShowResultTab,
                )
                .check(
                    TextKey::TraceWorkspaceTabQuery,
                    self.detail_tab == model::TraceDetailTab::Query,
                    TraceMenuCommand::ShowQueryTab,
                )
                .check(
                    TextKey::TraceWorkspaceTabSearch,
                    self.detail_tab == model::TraceDetailTab::Search,
                    TraceMenuCommand::ShowSearchTab,
                )
                .separator()
                .item_when(
                    active && viewer_available,
                    TextKey::TraceWorkspaceOpenViewer,
                    TraceMenuCommand::OpenViewer,
                )
                .item_when(
                    can_open_timeline,
                    TextKey::TraceWorkspaceOpenInTimeline,
                    TraceMenuCommand::OpenInTimeline,
                )
                .item_when(
                    copy_sql,
                    TextKey::TraceWorkspaceCopySql,
                    TraceMenuCommand::CopySql,
                ),
            MenuGroup::new(TextKey::ShellMenuSession)
                .item_when(
                    selected_session_exists,
                    TextKey::TraceWorkspaceOpen,
                    TraceMenuCommand::OpenSelectedSession,
                )
                .item_when(
                    selected_session_exists,
                    TextKey::TraceWorkspaceDelete,
                    TraceMenuCommand::DeleteSelectedSession,
                )
                .separator()
                .item_when(
                    selected_session_exists,
                    TextKey::TraceWorkspaceExportPackage,
                    TraceMenuCommand::ExportSessionPackage,
                ),
        ]
    }

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        _window: &mut gpui_kit::Window,
        cx: &mut gpui_kit::Context<Self>,
    ) {
        match command {
            TraceMenuCommand::Unavailable => {}
            TraceMenuCommand::ImportTrace => self.pick_trace(cx),
            TraceMenuCommand::ExportPrimary => self.export_primary(cx),
            TraceMenuCommand::ExportTrace => self.export_raw_trace(cx),
            TraceMenuCommand::OpenRecent(session_id) => self.open_session(session_id, cx),
            TraceMenuCommand::CloseTrace => self.close_trace(cx),
            TraceMenuCommand::RunPreset => self.run_preset(cx),
            TraceMenuCommand::RunSelectedDiagnostic => {
                if let Some(query_id) = self.selected_query.clone() {
                    self.run_diagnostic(query_id, cx);
                }
            }
            TraceMenuCommand::SearchSlices => self.run_trace_search(cx),
            TraceMenuCommand::ToggleCapture => self.toggle_capture(cx),
            TraceMenuCommand::RefreshDevices => self.refresh_devices(cx),
            TraceMenuCommand::ShowResultTab => {
                self.set_detail_tab(model::TraceDetailTab::Result, cx)
            }
            TraceMenuCommand::ShowQueryTab => self.set_detail_tab(model::TraceDetailTab::Query, cx),
            TraceMenuCommand::ShowSearchTab => {
                self.set_detail_tab(model::TraceDetailTab::Search, cx)
            }
            TraceMenuCommand::OpenViewer => self.open_viewer(cx),
            TraceMenuCommand::OpenInTimeline => {
                let timestamp = self
                    .insights
                    .iter()
                    .find(|insight| {
                        Some(insight.query_id.as_str()) == self.selected_query.as_deref()
                    })
                    .and_then(|insight| insight.timestamp_nanos);
                if let Some(timestamp) = timestamp {
                    self.open_in_timeline(timestamp, cx);
                }
            }
            TraceMenuCommand::CopySql => {
                let sql = self
                    .result_table
                    .as_ref()
                    .map(|table| table.sql.clone())
                    .filter(|sql| !sql.trim().is_empty());
                if let Some(sql) = sql {
                    cx.write_to_clipboard(ClipboardItem::new_string(sql));
                }
            }
            TraceMenuCommand::OpenSelectedSession => {
                if let Some(session_id) = self.selected_session.clone() {
                    self.open_session(session_id, cx);
                }
            }
            TraceMenuCommand::DeleteSelectedSession => {
                if let Some(session_id) = self.selected_session.clone() {
                    self.delete_session(session_id, cx);
                }
            }
            TraceMenuCommand::ExportSessionPackage => {
                if let Some(session_id) = self.selected_session.clone() {
                    self.export_session_package(session_id, cx);
                }
            }
        }
    }
}

/// Route-shortcut entry points (IDEA-style window shortcuts).
impl TraceWorkspace {
    pub(crate) fn open_most_recent_session(&mut self, cx: &mut Context<Self>) {
        let id = self.sessions.first().map(|record| record.id.clone());
        if let Some(id) = id {
            self.open_session(id, cx);
        }
    }

    pub(crate) fn close_primary(&mut self, cx: &mut Context<Self>) {
        self.close_trace(cx);
    }

    pub(crate) fn toggle_capture_primary(&mut self, cx: &mut Context<Self>) {
        self.toggle_capture(cx);
    }
}
