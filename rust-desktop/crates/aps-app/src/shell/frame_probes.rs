//! Handoff between an evidence-bound frame, local source, and the AI review page.
use super::*;

impl Studio {
    pub(super) fn handle_frame_probe(
        &mut self,
        page: &Entity<FrameWorkspace>,
        action: &crate::frame::FrameProbeAction,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.destination != Destination::Frame {
            return;
        }
        match action {
            crate::frame::FrameProbeAction::Locate(marker) => {
                let Some(binding) = page.read(cx).frame_binding() else {
                    return;
                };
                let Some(lookup) = page.read(cx).probe_lookup(marker) else {
                    return;
                };
                let registry = self.source.read(cx).probe_registry();
                let Ok(epoch) = self.ai_request_epoch.begin() else {
                    return;
                };
                cx.spawn_in(window, async move |this, cx| {
                    let location = cx
                        .background_executor()
                        .spawn(async move { lookup.resolve(registry) })
                        .await;
                    let _ = this.update_in(cx, |s, _, cx| {
                        if !s.ai_request_epoch.take_current(
                            epoch,
                            Destination::Frame,
                            s.destination,
                        ) || s.frame.read(cx).frame_binding() != Some(binding)
                        {
                            return;
                        }
                        match location {
                            Ok(loc) => {
                                s.navigate(Destination::SourceWorkspaces, cx);
                                s.source
                                    .update(cx, |src, cx| src.navigate_to_source(loc, cx));
                            }
                            Err(err) => {
                                s.notice = Some(err);
                                cx.notify();
                            }
                        }
                    });
                })
                .detach();
            }
            crate::frame::FrameProbeAction::Optimize(marker) => {
                let Some(binding) = page.read(cx).probe_binding() else {
                    return;
                };
                let job = match page.read(cx).optimization_job(marker) {
                    Ok(j) => j,
                    Err(e) => {
                        self.notice = Some(e);
                        cx.notify();
                        return;
                    }
                };
                let registry = self.source.read(cx).probe_registry();
                let Ok(epoch) = self.ai_request_epoch.begin() else {
                    return;
                };
                cx.spawn_in(window, async move |this, cx| {
                    let prepared = cx
                        .background_executor()
                        .spawn(async move { job.prepare(registry) })
                        .await;
                    let _ = this.update_in(cx, |s, w, cx| {
                        if !s
                            .ai_request_epoch
                            .accepts(epoch, Destination::Frame, s.destination)
                            || s.frame.read(cx).probe_binding() != Some(binding)
                        {
                            return;
                        }
                        s.ai_request_epoch.cancel();
                        match prepared {
                            Ok((r, guard)) => {
                                s.bind_ai_request(Destination::Frame, Ok(r), None, w, cx);
                                if s.ai_origin == Some(Destination::Frame) {
                                    s.ai.update(cx, |ai, _| ai.bind_frame_optimization(guard));
                                }
                            }
                            Err(e) => {
                                s.notice = Some(e);
                                cx.notify();
                            }
                        }
                    });
                })
                .detach();
            }
            crate::frame::FrameProbeAction::Plan => {
                let r = page.read(cx).probe_plan_request();
                self.present_ai_request(Destination::Frame, r, window, cx);
            }
            crate::frame::FrameProbeAction::Patch => {
                let Some(binding) = page.read(cx).probe_binding() else {
                    return;
                };
                let lang = self.settings.language.resolved();
                let Some(source) = self.source.read(cx).local_probe_source() else {
                    self.navigate(Destination::SourceWorkspaces, cx);
                    self.notice = Some(lang.text(TextKey::FrameProbeChooseSource).into());
                    cx.notify();
                    return;
                };
                let loc = source.location.clone();
                cx.spawn_in(window, async move |this, cx| {
                    let result = cx
                        .background_executor()
                        .spawn(async move {
                            let text = source.read()?;
                            crate::frame::ProbeDraft::new(source.location, text)
                        })
                        .await;
                    let _ = this.update_in(cx, |studio, window, cx| {
                        if studio.destination != Destination::Frame
                            || studio.frame.read(cx).probe_binding().as_ref() != Some(&binding)
                            || studio
                                .source
                                .read(cx)
                                .local_probe_source()
                                .is_none_or(|s| s.location != loc)
                        {
                            return;
                        }
                        match result {
                            Ok(draft) => crate::frame::show_probe_draft(
                                draft,
                                binding.1,
                                lang,
                                studio.frame.clone(),
                                window,
                                cx,
                            ),
                            Err(err) => {
                                studio.notice = Some(err);
                                cx.notify();
                            }
                        }
                    });
                })
                .detach();
            }
        }
    }
}
