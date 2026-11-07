//! Offline ART method-event workspace. Import and page projection stay off the
//! UI thread. Generation checks also cover the picker, so closing never allows
//! an earlier selection/import completion to reopen a trace.
use aps_method_recording::{
    ClockSource, DiagnosticKind, DurationTotals, ImportLimits, MethodId, MethodRecordingSession,
    MethodStatistics, NodeId, Page, Thread, ThreadId, TimelineQuery,
};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable, Selectable,
        button::{Button, ButtonVariants},
    },
    prelude::*,
    *,
};
use std::{path::PathBuf, sync::Arc};

const ROW_PAGE: usize = 100;
const THREAD_PAGE: usize = 32;
const STACK_PREVIEW: usize = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MethodView {
    TopMethods,
    CallTree,
    Timeline,
    Events,
    Diagnostics,
}
impl MethodView {
    const ALL: [Self; 5] = [
        Self::TopMethods,
        Self::CallTree,
        Self::Timeline,
        Self::Events,
        Self::Diagnostics,
    ];
    fn label(self, language: Language) -> &'static str {
        match self {
            Self::TopMethods => language.text(TextKey::MethodTopMethods),
            Self::CallTree => language.text(TextKey::MethodCallTree),
            Self::Timeline => language.text(TextKey::MethodTimelineIntervals),
            Self::Events => language.text(TextKey::MethodMethodEvents),
            Self::Diagnostics => language.text(TextKey::MethodDiagnostics),
        }
    }
}
struct Column {
    label: String,
    width: f32,
}
struct Row {
    cells: Vec<String>,
    node: Option<NodeId>,
}
struct MethodPage {
    language: Language,
    columns: Vec<Column>,
    rows: Vec<Row>,
    offset: usize,
    total: usize,
    has_more: bool,
}
struct StackPreview {
    frames: Vec<String>,
    total: usize,
}

pub struct MethodWorkspace {
    language: Language,
    session: Option<Arc<MethodRecordingSession>>,
    path: Option<PathBuf>,
    threads: Option<Page<Thread>>,
    selected_thread: Option<ThreadId>,
    view: MethodView,
    page: Option<MethodPage>,
    stack: Option<StackPreview>,
    busy: bool,
    generation: u64,
    error: Option<String>,
}

impl MethodWorkspace {
    pub fn new(language: Language) -> Self {
        Self {
            language,
            session: None,
            path: None,
            threads: None,
            selected_thread: None,
            view: MethodView::TopMethods,
            page: None,
            stack: None,
            busy: false,
            generation: 0,
            error: None,
        }
    }
    pub fn set_language(&mut self, language: Language, cx: &mut Context<Self>) {
        if self.language == language {
            return;
        }
        self.language = language;
        // Columns are app-owned copy, not imported evidence. Rebuild the bounded
        // projection for the new language without touching symbols or clocks.
        if !self.busy && self.session.is_some() {
            self.load(
                self.selected_thread,
                self.view,
                self.page.as_ref().map_or(0, |page| page.offset),
                self.threads.as_ref().map_or(0, |page| page.offset),
                cx,
            );
        }
        cx.notify();
    }

    pub fn analysis_request(&self) -> Result<aps_ai::AnalysisRequest, String> {
        use crate::evidence::{clipped, object, request};
        if self.busy {
            return Err(self.language.text(TextKey::MethodWaitForQuery).into());
        }
        let session = self
            .session
            .as_ref()
            .ok_or_else(|| self.language.text(TextKey::MethodImportFirst))?;
        let page = self
            .page
            .as_ref()
            .ok_or_else(|| self.language.text(TextKey::MethodNoPublishedEvidence))?;
        let rows = page
            .rows
            .iter()
            .take(12)
            .map(|row| {
                object([
                    (
                        "cells",
                        aps_json::Value::Array(
                            row.cells
                                .iter()
                                .map(|cell| clipped(cell, 512).into())
                                .collect(),
                        ),
                    ),
                    (
                        "nodeId",
                        row.node
                            .map_or(aps_json::Value::Null, |node| format!("{node:?}").into()),
                    ),
                ])
            })
            .collect();
        request(
            "method-recording",
            "Current bounded offline ART method projection; no source path or raw event payload.",
            &format!(
                "{} rows of {} in {}",
                page.rows.len(),
                page.total,
                self.view.label(self.language)
            ),
            object([
                ("view", self.view.label(self.language).into()),
                ("offset", page.offset.into()),
                (
                    "selectedThread",
                    self.selected_thread
                        .map_or(aps_json::Value::Null, |id| format!("{id:?}").into()),
                ),
                (
                    "threadCount",
                    session
                        .threads_page(0, 1)
                        .map(|page| page.total)
                        .unwrap_or(0)
                        .into(),
                ),
                ("rows", aps_json::Value::Array(rows)),
            ]),
        )
    }

    pub fn pick(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        self.busy = true;
        self.error = None;
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(
                self.language
                    .text(TextKey::MethodImportBinaryFirstArtMethodTrace)
                    .into(),
            ),
        });
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                match result {
                    Ok(Ok(Some(paths))) if paths.len() == 1 => {
                        this.open(paths[0].clone(), generation, cx)
                    }
                    Ok(Err(error)) => {
                        this.busy = false;
                        this.refresh_language(cx);
                        this.error = Some(error.to_string());
                        cx.notify();
                    }
                    _ => {
                        this.busy = false;
                        this.refresh_language(cx);
                        cx.notify();
                    }
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn open(&mut self, path: PathBuf, generation: u64, cx: &mut Context<Self>) {
        let language = self.language;
        let task = cx.background_executor().spawn(async move {
            let session = Arc::new(
                MethodRecordingSession::import_file(&path, ImportLimits::default())
                    .map_err(|error| error.to_string())?,
            );
            let threads = session
                .threads_page(0, THREAD_PAGE)
                .map_err(|error| error.to_string())?;
            let page = make_page(&session, None, MethodView::TopMethods, 0, language)?;
            Ok::<_, String>((path, session, threads, page))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok((path, session, threads, page)) => {
                        this.path = Some(path);
                        this.session = Some(session);
                        this.threads = Some(threads);
                        this.page = Some(page);
                        this.selected_thread = None;
                        this.view = MethodView::TopMethods;
                        this.stack = None;
                    }
                    Err(error) => this.error = Some(error),
                }
                // A language change while work was pending cannot publish old
                // column labels into the newly selected language.
                if this.language != language && this.session.is_some() {
                    this.load(
                        this.selected_thread,
                        this.view,
                        this.page.as_ref().map_or(0, |page| page.offset),
                        this.threads.as_ref().map_or(0, |page| page.offset),
                        cx,
                    );
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    fn load(
        &mut self,
        selected_thread: Option<ThreadId>,
        view: MethodView,
        offset: usize,
        thread_offset: usize,
        cx: &mut Context<Self>,
    ) {
        if self.busy {
            return;
        }
        let Some(session) = self.session.clone() else {
            return;
        };
        if view == MethodView::CallTree && selected_thread.is_none() {
            return;
        }
        self.busy = true;
        self.error = None;
        self.stack = None;
        self.page = None;
        self.selected_thread = selected_thread;
        self.view = view;
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        let language = self.language;
        let task = cx.background_executor().spawn(async move {
            let threads = session
                .threads_page(thread_offset, THREAD_PAGE)
                .map_err(|error| error.to_string())?;
            let page = make_page(&session, selected_thread, view, offset, language)?;
            Ok::<_, String>((threads, page))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok((threads, page)) => {
                        this.threads = Some(threads);
                        this.page = Some(page);
                    }
                    Err(error) => this.error = Some(error),
                }
                if this.language != language {
                    this.load(
                        this.selected_thread,
                        this.view,
                        this.page.as_ref().map_or(offset, |page| page.offset),
                        this.threads
                            .as_ref()
                            .map_or(thread_offset, |page| page.offset),
                        cx,
                    );
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn choose_thread(&mut self, thread: Option<ThreadId>, cx: &mut Context<Self>) {
        let view = if thread.is_none() && self.view == MethodView::CallTree {
            MethodView::TopMethods
        } else {
            self.view
        };
        self.load(
            thread,
            view,
            0,
            self.threads.as_ref().map_or(0, |page| page.offset),
            cx,
        );
    }
    fn change_view(&mut self, view: MethodView, cx: &mut Context<Self>) {
        self.load(
            self.selected_thread,
            view,
            0,
            self.threads.as_ref().map_or(0, |page| page.offset),
            cx,
        );
    }
    fn page(&mut self, offset: usize, cx: &mut Context<Self>) {
        self.load(
            self.selected_thread,
            self.view,
            offset,
            self.threads.as_ref().map_or(0, |page| page.offset),
            cx,
        );
    }
    fn thread_page(&mut self, offset: usize, cx: &mut Context<Self>) {
        self.load(
            self.selected_thread,
            self.view,
            self.page.as_ref().map_or(0, |page| page.offset),
            offset,
            cx,
        );
    }
    fn inspect_stack(&mut self, node: NodeId, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(session) = self.session.clone() else {
            return;
        };
        self.busy = true;
        self.error = None;
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        let task = cx.background_executor().spawn(async move {
            let ids = session.stack(node).map_err(|error| error.to_string())?;
            let total = ids.len();
            let frames = ids
                .into_iter()
                .take(STACK_PREVIEW)
                .map(|id| raw_symbol(&session, id))
                .collect();
            Ok::<_, String>(StackPreview { frames, total })
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(stack) => this.stack = Some(stack),
                    Err(error) => this.error = Some(error),
                }
                this.refresh_language(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn refresh_language(&mut self, cx: &mut Context<Self>) {
        if !self.busy
            && self
                .page
                .as_ref()
                .is_some_and(|page| page.language != self.language)
        {
            self.load(
                self.selected_thread,
                self.view,
                self.page.as_ref().map_or(0, |page| page.offset),
                self.threads.as_ref().map_or(0, |page| page.offset),
                cx,
            );
        }
    }
    fn close(&mut self, cx: &mut Context<Self>) {
        // Reads/replay are input-bounded but not cooperatively interruptible.
        // Drop every UI reference and reject any pending picker/import/page
        // result. A bounded background task can finish without publishing.
        self.generation = self.generation.wrapping_add(1);
        self.busy = false;
        self.session = None;
        self.path = None;
        self.threads = None;
        self.selected_thread = None;
        self.view = MethodView::TopMethods;
        self.page = None;
        self.stack = None;
        self.error = None;
        cx.notify();
    }
}

impl Render for MethodWorkspace {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
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
                    .items_center()
                    .gap_2()
                    .child(
                        Button::new("method-import")
                            .primary()
                            .label(lang.text(TextKey::MethodImportArtTrace))
                            .disabled(busy)
                            .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
                    )
                    .child(
                        Button::new("method-close")
                            .label(lang.text(TextKey::CommonClose))
                            .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
                    ),
            )
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(cx.theme().muted_foreground)
                    .child(crate::studio::selectable(
                        lang.text(TextKey::MethodOfflineLocalInputBoundedMethodEventsNot),
                    )),
            )
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(cx.theme().warning)
                    .child(crate::studio::selectable(
                        lang.text(TextKey::MethodSupportedExistingBinaryFirstSlowV2V3),
                    )),
            )
            .children(self.error.clone().map(|error| {
                div()
                    .p_2()
                    .text_color(cx.theme().danger)
                    .child(crate::studio::selectable(error))
            }));
        if let Some(path) = &self.path {
            body = body.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .child(crate::studio::selectable(path.display().to_string())),
            );
        }
        if busy {
            body = body.child(crate::studio::selectable(
                lang.text(TextKey::MethodReadingReplayingQuerying),
            ));
        }
        if let Some(session) = &self.session {
            let summary = session.summary();
            let header = session.header();
            let clock = clock_label(header.clock_source, lang);
            body =
                body.child(div().text_size(px(typography::TITLE)).child(
                    crate::studio::selectable(format!(
                        "{} {} · {} {} · {} {} · {} {}",
                        summary.event_count,
                        lang.text(TextKey::MethodEvents),
                        summary.thread_count,
                        lang.text(TextKey::MethodThreads),
                        summary.method_count,
                        lang.text(TextKey::MethodMethods),
                        summary.interval_count,
                        lang.text(TextKey::MethodObservedIntervals)
                    )),
                ))
                .child(crate::studio::selectable(format!(
                    "v{} · {} · {} {} ns · {} {} ns",
                    header.version,
                    clock,
                    lang.text(TextKey::MethodHeaderStart),
                    header.start_time_nanos,
                    lang.text(TextKey::MethodSumOfActiveThreadDurations),
                    summary.observed_active_time.primary_nanos
                )))
                .child(div().text_size(px(typography::SUPPORTING)).child(
                    crate::studio::selectable(
                        lang.text(TextKey::MethodHeaderStartIsNotAddedToEvent),
                    ),
                ))
                .child(
                    div()
                        .text_size(px(typography::SUPPORTING))
                        .text_color(if summary.has_integrity_diagnostics {
                            cx.theme().warning
                        } else {
                            cx.theme().muted_foreground
                        })
                        .child(if summary.has_integrity_diagnostics {
                            lang.text(
                                TextKey::MethodIntegrityDiagnosticsArePresentDurationsCoverValid,
                            )
                        } else {
                            lang.text(TextKey::MethodNoParserReplayDiagnosticsThisStillDoes)
                        }),
                );
            if let Some(thread) = self.selected_thread.and_then(|id| session.thread(id)) {
                body = body.child(crate::studio::selectable(format!(
                    "{} {} · {}",
                    lang.text(TextKey::MethodSelectedThread),
                    thread.id,
                    thread.name
                )));
            } else {
                body = body.child(crate::studio::selectable(
                    lang.text(TextKey::MethodScopeAllThreads),
                ));
            }
            if let Some(threads) = &self.threads {
                let offset = threads.offset;
                let has_more = threads.has_more;
                body = body
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap_2()
                            .child(
                                Button::new("method-all-threads")
                                    .label(lang.text(TextKey::MethodAllThreads))
                                    .selected(self.selected_thread.is_none())
                                    .disabled(busy)
                                    .on_click(
                                        cx.listener(|this, _, _, cx| this.choose_thread(None, cx)),
                                    ),
                            )
                            .child(crate::studio::selectable(format!(
                                "{} {}–{} / {}",
                                lang.text(TextKey::MethodThreadCatalog),
                                offset + usize::from(!threads.rows.is_empty()),
                                offset + threads.rows.len(),
                                threads.total
                            )))
                            .child(
                                Button::new("method-threads-prev")
                                    .label(lang.text(TextKey::MethodPreviousThreads))
                                    .disabled(busy || offset == 0)
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.thread_page(offset.saturating_sub(THREAD_PAGE), cx)
                                    })),
                            )
                            .child(
                                Button::new("method-threads-next")
                                    .label(lang.text(TextKey::MethodNextThreads))
                                    .disabled(busy || !has_more)
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.thread_page(offset.saturating_add(THREAD_PAGE), cx)
                                    })),
                            ),
                    )
                    .child(
                        div()
                            .id("method-thread-catalog")
                            .overflow_x_scroll()
                            .flex()
                            .gap_1()
                            .children(threads.rows.iter().map(|thread| {
                                let id = thread.id;
                                Button::new(("method-thread", id as usize))
                                    .label(format!(
                                        "{} · {} · {}",
                                        id, thread.name, thread.event_count
                                    ))
                                    .selected(self.selected_thread == Some(id))
                                    .disabled(busy)
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.choose_thread(Some(id), cx)
                                    }))
                            })),
                    );
            }
            body = body.child(
                div()
                    .flex()
                    .gap_1()
                    .children(MethodView::ALL.into_iter().map(|view| {
                        Button::new(("method-view", view as usize))
                            .label(view.label(lang))
                            .selected(self.view == view)
                            .disabled(
                                busy || (view == MethodView::CallTree
                                    && self.selected_thread.is_none()),
                            )
                            .on_click(cx.listener(move |this, _, _, cx| this.change_view(view, cx)))
                    })),
            );
            if self.selected_thread.is_none() {
                body = body.child(div().text_size(px(typography::SUPPORTING)).child(
                    crate::studio::selectable(
                        lang.text(TextKey::MethodSelectOneThreadToInspectItsCall),
                    ),
                ));
            }
            let diagnostics = session.diagnostics();
            if !diagnostics.counts.is_empty() {
                body = body.child(div().text_size(px(typography::SUPPORTING)).children(
                    diagnostics.counts.iter().map(|(kind, count)| {
                        div().child(crate::studio::selectable(format!(
                            "{}: {count}",
                            diagnostic_label(*kind, lang)
                        )))
                    }),
                ));
            }
            if diagnostics.omitted_entries > 0 {
                body = body.child(div().text_size(px(typography::SUPPORTING)).child(
                    crate::studio::selectable(format!(
                        "{} {}",
                        diagnostics.omitted_entries,
                        lang.text(TextKey::MethodAdditionalDiagnosticMessagesOmittedByImportCap)
                    )),
                ));
            }
        }
        if let Some(page) = &self.page {
            let offset = page.offset;
            body = body.child(
                div()
                    .flex()
                    .items_center()
                    .gap_2()
                    .child(crate::studio::selectable(format!(
                        "{}–{} / {}",
                        offset + usize::from(!page.rows.is_empty()),
                        offset + page.rows.len(),
                        page.total
                    )))
                    .child(
                        Button::new("method-page-prev")
                            .label(lang.text(TextKey::CommonPrevious))
                            .disabled(busy || offset == 0)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.page(offset.saturating_sub(ROW_PAGE), cx)
                            })),
                    )
                    .child(
                        Button::new("method-page-next")
                            .label(lang.text(TextKey::CommonNext))
                            .disabled(busy || !page.has_more)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.page(offset.saturating_add(ROW_PAGE), cx)
                            })),
                    ),
            );
            let table_width = page
                .columns
                .iter()
                .map(|column| column.width + 8.)
                .sum::<f32>()
                + 100.;
            body = body.child(
                div().id("method-data-table").overflow_x_scroll().child(
                    div()
                        .w(px(table_width))
                        .flex()
                        .flex_col()
                        .child(div().flex().gap_2().p_2().bg(cx.theme().muted).children(
                            page.columns.iter().map(|column| {
                                div()
                                    .w(px(column.width))
                                    .flex_shrink_0()
                                    .text_size(px(typography::SUPPORTING))
                                    .child(column.label.clone())
                            }),
                        ))
                        .children(page.rows.iter().enumerate().map(|(index, row)| {
                            let node = row.node;
                            div()
                                .flex()
                                .gap_2()
                                .p_2()
                                .border_b_1()
                                .border_color(cx.theme().border)
                                .children(row.cells.iter().zip(page.columns.iter()).map(
                                    |(cell, column)| {
                                        div()
                                            .w(px(column.width))
                                            .flex_shrink_0()
                                            .text_size(px(typography::BODY))
                                            .child(cell.clone())
                                    },
                                ))
                                .children(node.map(|node| {
                                    Button::new(("method-stack", index))
                                        .label(lang.text(TextKey::MethodStack))
                                        .disabled(busy)
                                        .on_click(cx.listener(move |this, _, _, cx| {
                                            this.inspect_stack(node, cx)
                                        }))
                                }))
                        })),
                ),
            );
            if page.rows.is_empty() {
                body = body.child(crate::studio::selectable(
                    lang.text(TextKey::MethodNoRowsInThisScope),
                ));
            }
            if self.view == MethodView::Timeline {
                body = body.child(div().text_size(px(typography::SUPPORTING)).child(
                    crate::studio::selectable(
                        lang.text(TextKey::MethodIntervalsAreReturnedInEventFileOrder),
                    ),
                ));
            }
        }
        if let Some(stack) = &self.stack {
            body =
                body.child(div().text_size(px(typography::TITLE)).child(
                    crate::studio::selectable(format!(
                        "{} · {} {}",
                        lang.text(TextKey::MethodSelectedCallPath),
                        stack.total,
                        lang.text(TextKey::MethodFrames)
                    )),
                ))
                .child(
                    div()
                        .id("method-selected-stack")
                        .overflow_x_scroll()
                        .font_family("monospace")
                        .text_size(px(typography::BODY))
                        .children(stack.frames.iter().enumerate().map(|(index, frame)| {
                            div().child(crate::studio::selectable(format!(
                                "{}  {}",
                                index + 1,
                                frame
                            )))
                        })),
                );
            if stack.total > stack.frames.len() {
                body = body.child(crate::studio::selectable(format!(
                    "{} {}",
                    lang.text(TextKey::MethodAdditionalFramesOmittedFromThisPreview),
                    stack.total - stack.frames.len()
                )));
            }
        }
        if self.session.is_none() && !busy {
            body = body.child(crate::studio::selectable(
                lang.text(TextKey::MethodImportASupportedLocalArtArtifactTo),
            ));
        }
        body
    }
}

fn make_page(
    session: &MethodRecordingSession,
    thread: Option<ThreadId>,
    view: MethodView,
    offset: usize,
    lang: Language,
) -> Result<MethodPage, String> {
    let columns = |items: &[(TextKey, f32)]| {
        items
            .iter()
            .map(|(key, width)| Column {
                label: lang.text(*key).to_owned(),
                width: *width,
            })
            .collect()
    };
    match view {
        MethodView::TopMethods => {
            let page = session
                .top_methods_page(thread, offset, ROW_PAGE)
                .map_err(|error| error.to_string())?;
            let rows = page
                .rows
                .iter()
                .map(|stats| {
                    let (class, name, signature) = method_fields(session, stats.method_id);
                    let mut cells =
                        vec![format!("0x{:x}", stats.method_id), class, name, signature];
                    cells.extend(stat_fields(stats));
                    Row { cells, node: None }
                })
                .collect();
            Ok(MethodPage {
                language: lang,
                columns: columns(&[
                    (TextKey::MethodMethodId, 100.),
                    (TextKey::MethodRawClass, 220.),
                    (TextKey::MethodRawMethod, 180.),
                    (TextKey::MethodSignature, 180.),
                    (TextKey::MethodEnterCalls, 100.),
                    (TextKey::MethodReturnedUnwoundIncomplete, 180.),
                    (TextKey::MethodSelfPrimaryNs, 130.),
                    (TextKey::MethodInclusivePrimaryNs, 140.),
                    (TextKey::MethodSelfCpuNs, 120.),
                    (TextKey::MethodInclusiveCpuNs, 130.),
                ]),
                rows,
                offset,
                total: page.total,
                has_more: page.has_more,
            })
        }
        MethodView::CallTree => {
            let thread = thread.ok_or_else(|| lang.text(TextKey::MethodSelectThreadFirst))?;
            let page = session
                .call_tree_page(thread, offset, ROW_PAGE)
                .map_err(|error| error.to_string())?;
            let rows = page
                .rows
                .iter()
                .map(|row| {
                    let (class, name, signature) = row
                        .method_id
                        .map(|id| method_fields(session, id))
                        .unwrap_or_else(|| {
                            (
                                String::new(),
                                lang.text(TextKey::MethodThreadRoot).into(),
                                String::new(),
                            )
                        });
                    let mut cells = vec![
                        row.id.to_string(),
                        row.parent_id
                            .map(|id| id.to_string())
                            .unwrap_or_else(|| "—".into()),
                        row.depth.to_string(),
                        class,
                        name,
                        signature,
                    ];
                    cells.extend(stat_fields(&row.statistics));
                    Row {
                        cells,
                        node: row.method_id.map(|_| row.id),
                    }
                })
                .collect();
            Ok(MethodPage {
                language: lang,
                columns: columns(&[
                    (TextKey::MethodNode, 75.),
                    (TextKey::MethodParent, 75.),
                    (TextKey::MethodDepth, 65.),
                    (TextKey::MethodRawClass, 200.),
                    (TextKey::MethodRawMethod, 180.),
                    (TextKey::MethodSignature, 160.),
                    (TextKey::MethodEnterCalls, 100.),
                    (TextKey::MethodReturnedUnwoundIncomplete, 180.),
                    (TextKey::MethodSelfPrimaryNs, 130.),
                    (TextKey::MethodInclusivePrimaryNs, 140.),
                    (TextKey::MethodSelfCpuNs, 120.),
                    (TextKey::MethodInclusiveCpuNs, 130.),
                ]),
                rows,
                offset,
                total: page.total,
                has_more: page.has_more,
            })
        }
        MethodView::Timeline => {
            let page = session
                .timeline_page(TimelineQuery {
                    thread_id: thread,
                    offset,
                    limit: ROW_PAGE,
                    ..Default::default()
                })
                .map_err(|error| error.to_string())?;
            let rows = page
                .rows
                .iter()
                .map(|row| Row {
                    cells: vec![
                        row.thread_id.to_string(),
                        row.start_nanos.to_string(),
                        row.end_nanos.to_string(),
                        row.duration_nanos().to_string(),
                        optional(row.cpu_start_nanos),
                        optional(row.cpu_end_nanos),
                        row.depth.to_string(),
                        row.leaf_node_id.to_string(),
                    ],
                    node: Some(row.leaf_node_id),
                })
                .collect();
            Ok(MethodPage {
                language: lang,
                columns: columns(&[
                    (TextKey::CpuThread, 85.),
                    (TextKey::MethodStartPrimaryNs, 140.),
                    (TextKey::MethodEndPrimaryNsExclusive, 170.),
                    (TextKey::MethodDurationPrimaryNs, 150.),
                    (TextKey::MethodCpuStartNs, 130.),
                    (TextKey::MethodCpuEndNs, 130.),
                    (TextKey::MethodDepth, 70.),
                    (TextKey::MethodLeafNode, 90.),
                ]),
                rows,
                offset,
                total: page.total,
                has_more: page.has_more,
            })
        }
        MethodView::Events => {
            let page = session
                .events_page(thread, offset, ROW_PAGE)
                .map_err(|error| error.to_string())?;
            let rows = page
                .rows
                .iter()
                .map(|event| {
                    let (class, name, signature) = method_fields(session, event.method_id);
                    Row {
                        cells: vec![
                            event.thread_id.to_string(),
                            format!("{:?}", event.action),
                            format!("0x{:x}", event.method_id),
                            class,
                            name,
                            signature,
                            event.time_nanos.to_string(),
                            optional(event.cpu_nanos),
                            event.source_offset.to_string(),
                        ],
                        node: None,
                    }
                })
                .collect();
            Ok(MethodPage {
                language: lang,
                columns: columns(&[
                    (TextKey::CpuThread, 85.),
                    (TextKey::MethodAction, 90.),
                    (TextKey::MethodMethodId, 100.),
                    (TextKey::MethodRawClass, 220.),
                    (TextKey::MethodRawMethod, 180.),
                    (TextKey::MethodSignature, 180.),
                    (TextKey::MethodPrimaryTimeNs, 150.),
                    (TextKey::MethodCpuTimeNs, 140.),
                    (TextKey::MethodSourceByteOffset, 130.),
                ]),
                rows,
                offset,
                total: page.total,
                has_more: page.has_more,
            })
        }
        MethodView::Diagnostics => {
            let matches = |diagnostic: &&aps_method_recording::Diagnostic| {
                thread.is_none_or(|id| diagnostic.thread_id.is_none_or(|tid| tid == id))
            };
            let total = session.diagnostics().entries.iter().filter(matches).count();
            let rows = session
                .diagnostics()
                .entries
                .iter()
                .filter(matches)
                .skip(offset)
                .take(ROW_PAGE)
                .map(|diagnostic| Row {
                    cells: vec![
                        diagnostic_label(diagnostic.kind, lang).into(),
                        optional(diagnostic.thread_id),
                        optional(diagnostic.event_index),
                        optional(diagnostic.offset),
                        diagnostic.message.clone(),
                    ],
                    node: None,
                })
                .collect::<Vec<_>>();
            let has_more = offset.saturating_add(rows.len()) < total;
            Ok(MethodPage {
                language: lang,
                columns: columns(&[
                    (TextKey::MethodKind, 190.),
                    (TextKey::CpuThread, 90.),
                    (TextKey::MethodEventIndex, 100.),
                    (TextKey::MethodByteOffset, 110.),
                    (TextKey::MethodDiagnosticDetail, 700.),
                ]),
                rows,
                offset,
                total,
                has_more,
            })
        }
    }
}
fn method_fields(session: &MethodRecordingSession, id: MethodId) -> (String, String, String) {
    session
        .method(id)
        .filter(|method| method.declared)
        .map(|method| {
            (
                method.class_name.clone(),
                method.method_name.clone(),
                method.signature.clone(),
            )
        })
        .unwrap_or_else(|| (String::new(), format!("0x{id:x}"), String::new()))
}
fn raw_symbol(session: &MethodRecordingSession, id: MethodId) -> String {
    let (class, method, signature) = method_fields(session, id);
    // Preserve original slash spelling, names and signatures; separators and
    // raw numeric identity are presentation only, never a rewritten symbol.
    format!("0x{id:x}  {class}\t{method}\t{signature}")
}
fn stat_fields(stats: &MethodStatistics) -> Vec<String> {
    vec![
        stats.call_count.to_string(),
        format!(
            "{} / {} / {}",
            stats.completed_calls, stats.unrolled_calls, stats.incomplete_calls
        ),
        stats.self_time.primary_nanos.to_string(),
        stats.inclusive_time.primary_nanos.to_string(),
        cpu(stats.self_time),
        cpu(stats.inclusive_time),
    ]
}
fn cpu(value: DurationTotals) -> String {
    optional(value.cpu_nanos)
}
fn optional<T: std::fmt::Display>(value: Option<T>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "—".into())
}
fn clock_label(clock: ClockSource, lang: Language) -> &'static str {
    match clock {
        ClockSource::ThreadCpu => lang.text(TextKey::MethodPrimaryThreadCpu),
        ClockSource::UnspecifiedSingle => {
            lang.text(TextKey::MethodPrimaryUnspecifiedSingleClockNotAssumedWall)
        }
        ClockSource::WallAndThreadCpu => lang.text(TextKey::MethodPrimaryWallSeparateThreadCpu),
    }
}
fn diagnostic_label(kind: DiagnosticKind, lang: Language) -> &'static str {
    match kind {
        DiagnosticKind::UnknownPacket => lang.text(TextKey::MethodUnknownPacketPartialImport),
        DiagnosticKind::UnusedAction => lang.text(TextKey::MethodReservedAction),
        DiagnosticKind::TrailingBytes => lang.text(TextKey::MethodTrailingBytes),
        DiagnosticKind::MalformedTable => lang.text(TextKey::MethodMalformedCatalog),
        DiagnosticKind::DuplicateThread => lang.text(TextKey::MethodDuplicateThreadDeclaration),
        DiagnosticKind::DuplicateMethod => lang.text(TextKey::MethodDuplicateMethodDeclaration),
        DiagnosticKind::UncataloguedThread => lang.text(TextKey::MethodThreadWithoutDeclaration),
        DiagnosticKind::UncataloguedMethod => lang.text(TextKey::MethodMethodWithoutSymbols),
        DiagnosticKind::CaptureOverflow => lang.text(TextKey::MethodCaptureOverflow),
        DiagnosticKind::ClockRegression => lang.text(TextKey::MethodPrimaryClockRegression),
        DiagnosticKind::CpuClockRegression => lang.text(TextKey::MethodCpuClockRegression),
        DiagnosticKind::UnmatchedExit => lang.text(TextKey::MethodExitWithoutMatchingEnter),
        DiagnosticKind::MismatchedExit => lang.text(TextKey::MethodAncestorExitWithOpenChildren),
        DiagnosticKind::UnclosedEnter => lang.text(TextKey::MethodIncompleteInvocation),
    }
}

/// In-window menu bar commands for the Method workspace. Each screen publishes
/// only the commands its own functionality supports.
#[derive(Clone, PartialEq, Eq)]
pub(crate) enum MethodMenuCommand {
    /// Pick and import a binary-first ART method trace.
    Import,
    /// Drop the imported recording and every pending projection.
    Close,
    /// Switch the published report projection.
    ChangeView(MethodView),
}

/// Locale key of one method report view. The page's view row and the View menu
/// must name the same projection.
fn method_view_label_key(view: MethodView) -> TextKey {
    match view {
        MethodView::TopMethods => TextKey::MethodTopMethods,
        MethodView::CallTree => TextKey::MethodCallTree,
        MethodView::Timeline => TextKey::MethodTimelineIntervals,
        MethodView::Events => TextKey::MethodMethodEvents,
        MethodView::Diagnostics => TextKey::MethodDiagnostics,
    }
}

impl crate::shell::menubar::MenuHost for MethodWorkspace {
    type Command = MethodMenuCommand;

    fn menu_groups(
        &self,
        _language: Language,
    ) -> Vec<crate::shell::menubar::MenuGroup<Self::Command>> {
        use crate::shell::menubar::MenuGroup;
        let busy = self.busy;
        let mut groups = vec![
            MenuGroup::new(TextKey::ShellMenuFile)
                .item_when(
                    !busy,
                    TextKey::MethodImportArtTrace,
                    MethodMenuCommand::Import,
                )
                .item_when(
                    self.session.is_some() && !busy,
                    TextKey::CommonClose,
                    MethodMenuCommand::Close,
                ),
        ];
        // A report view only describes an imported recording, and the call tree
        // additionally needs one thread to scope it.
        if self.session.is_some() {
            let mut views = MenuGroup::new(TextKey::ShellMenuView);
            for candidate in MethodView::ALL {
                if candidate == MethodView::CallTree && self.selected_thread.is_none() {
                    continue;
                }
                views = views.check(
                    method_view_label_key(candidate),
                    self.view == candidate,
                    MethodMenuCommand::ChangeView(candidate),
                );
            }
            groups.push(views);
        }
        groups
    }

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match command {
            MethodMenuCommand::Import => self.pick(cx),
            MethodMenuCommand::Close => self.close(cx),
            MethodMenuCommand::ChangeView(view) => self.change_view(view, cx),
        }
    }
}
