use super::*;
use std::time::Duration;

const CAPTURE_INTERVAL: Duration = Duration::from_secs(1);

#[derive(Default)]
pub(super) struct ScanLoop {
    pub(super) enabled: bool,
    epoch: u64,
    pending: bool,
}

impl ScanLoop {
    fn start(&mut self) {
        self.epoch = self.epoch.wrapping_add(1);
        self.enabled = true;
        self.pending = false;
    }

    fn stop(&mut self) {
        self.enabled = false;
        self.pending = false;
        self.epoch = self.epoch.wrapping_add(1);
    }

    fn schedule(&mut self) -> Option<u64> {
        if !self.enabled || self.pending {
            return None;
        }
        self.pending = true;
        Some(self.epoch)
    }

    fn take_tick(&mut self, epoch: u64) -> bool {
        if !self.enabled || !self.pending || self.epoch != epoch {
            return false;
        }
        self.pending = false;
        true
    }
}

impl InspectorWorkspace {
    pub(crate) fn toggle_auto_scan(&mut self, cx: &mut Context<Self>) {
        if self.auto_scan.enabled {
            self.stop_live_capture(cx);
            return;
        }
        if self.busy || self.capturing || self.compose_inspection_confirmation_pending {
            return;
        }
        if self.target_serial.as_deref().is_none_or(str::is_empty) {
            self.error = Some(self.language.text(TextKey::InspectorNoDevice).into());
            cx.notify();
            return;
        }
        if self.compose_agent && self.compose_bridge.is_none() {
            self.resume_auto_scan_after_confirmation_pending = true;
            self.prepare_compose(cx);
            return;
        }
        self.auto_scan.start();
        self.capture_once(cx);
        cx.notify();
    }

    pub(super) fn pause_auto_scan(&mut self) {
        self.auto_scan.stop();
        self.auto_scan_task = None;
    }

    pub(super) fn resume_auto_scan_after_confirmation(&mut self, cx: &mut Context<Self>) {
        if !self.resume_auto_scan_after_confirmation_pending {
            return;
        }
        self.resume_auto_scan_after_confirmation_pending = false;
        self.auto_scan.start();
        self.schedule_auto_scan(cx);
    }

    pub(crate) fn stop_live_capture(&mut self, cx: &mut Context<Self>) {
        self.resume_auto_scan_after_confirmation_pending = false;
        if !self.auto_scan.enabled {
            return;
        }
        self.pause_auto_scan();
        if self.capturing {
            self.cancel.cancel();
            self.generation += 1;
            // The worker still owns cleanup, especially for Compose injection.
            // Keep busy/capturing until its completion callback has reaped it.
        }
        cx.notify();
    }

    pub(super) fn continue_auto_scan(&mut self, cx: &mut Context<Self>) {
        if self.error.is_some() || self.compose_inspection_confirmation_pending {
            self.pause_auto_scan();
            return;
        }
        self.schedule_auto_scan(cx);
    }

    fn schedule_auto_scan(&mut self, cx: &mut Context<Self>) {
        let Some(epoch) = self.auto_scan.schedule() else {
            return;
        };
        self.auto_scan_task = Some(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(CAPTURE_INTERVAL).await;
            let _ = this.update(cx, |this, cx| {
                if !this.auto_scan.take_tick(epoch) {
                    return;
                }
                if this.busy || this.capturing {
                    this.schedule_auto_scan(cx);
                    return;
                }
                this.capture_once(cx);
            });
        }));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn scan_is_manual_by_default_and_queues_only_one_timer() {
        let mut scan = ScanLoop::default();
        assert!(!scan.enabled);
        assert_eq!(scan.schedule(), None);
        scan.start();
        let epoch = scan.schedule().unwrap();
        assert_eq!(scan.schedule(), None);
        assert!(scan.take_tick(epoch));
        assert!(!scan.take_tick(epoch));
        assert_eq!(scan.schedule(), Some(epoch));
    }

    #[::core::prelude::v1::test]
    fn stopped_or_restarted_scan_rejects_old_timer_callbacks() {
        let mut scan = ScanLoop::default();
        scan.start();
        let old = scan.schedule().unwrap();
        scan.stop();
        assert!(!scan.take_tick(old));
        assert_eq!(scan.schedule(), None);
        scan.start();
        let new = scan.schedule().unwrap();
        assert_ne!(old, new);
        assert!(!scan.take_tick(old));
        assert!(scan.take_tick(new));
    }

    #[::core::prelude::v1::test]
    fn stopping_inflight_capture_preserves_the_worker_cleanup_gate() {
        let source = include_str!("live.rs");
        let stop = source
            .split_once("pub(crate) fn stop_live_capture(")
            .unwrap()
            .1
            .split_once("fn continue_auto_scan(")
            .unwrap()
            .0;
        assert!(stop.contains("self.cancel.cancel()"));
        assert!(stop.contains("self.generation += 1"));
        assert!(!stop.contains("self.capturing = false"));
        assert!(!stop.contains("self.archive ="));
        assert!(!stop.contains("self.timeline ="));
    }

    #[::core::prelude::v1::test]
    fn failed_capture_stops_loop_without_automatic_compose_fallback() {
        let source = include_str!("live.rs");
        let completion = source
            .split_once("fn continue_auto_scan(")
            .unwrap()
            .1
            .split_once("fn schedule_auto_scan(")
            .unwrap()
            .0;
        assert!(completion.contains("self.error.is_some()"));
        assert!(completion.contains("self.pause_auto_scan()"));
        assert!(!completion.contains("self.refresh("));
    }

    #[::core::prelude::v1::test]
    fn successful_captures_schedule_next_frame_and_sensitive_actions_pause_scan() {
        let source = include_str!("../inspector.rs");
        let captures = source
            .split_once("fn capture_once(")
            .unwrap()
            .1
            .split_once("fn cancel_operation(")
            .unwrap()
            .0;
        assert_eq!(captures.matches("this.continue_auto_scan(cx)").count(), 2);
        for marker in [
            "pub fn refresh(",
            "fn prepare_compose(",
            "fn open(&mut self,",
        ] {
            let body = source.split_once(marker).unwrap().1;
            let start = body.find("self.pause_auto_scan()").unwrap();
            assert!(start < body.find("cx.spawn(").unwrap_or(usize::MAX));
        }
        let control = source
            .split_once("fn scan_mode_control(")
            .unwrap()
            .1
            .split_once("/// Compose `PanelToggleButton")
            .unwrap()
            .0;
        assert!(control.contains("enabled || auto_scan"));
        assert!(control.contains("this.toggle_auto_scan(cx)"));
        assert!(!control.contains("InspectorLiveCaptureUnavailable"));
    }

    #[::core::prelude::v1::test]
    fn confirmation_still_only_authorizes_and_never_restarts_auto_scan() {
        let source = include_str!("../inspector.rs");
        let confirmation = source
            .split_once("let confirm_button = Button::new(")
            .unwrap()
            .1
            .split_once("div()")
            .unwrap()
            .0;
        assert!(confirmation.contains("this.compose_agent = true"));
        assert!(!confirmation.contains("toggle_auto_scan("));
        assert!(!confirmation.contains("capture_compose("));
    }

    #[::core::prelude::v1::test]
    #[ignore = "requires APS_INSPECTOR_LIVE_SERIAL and a connected device; captures the current foreground app"]
    fn device_scan_appends_two_real_captures_and_stop_preserves_history() {
        let serial = std::env::var("APS_INSPECTOR_LIVE_SERIAL").expect("device serial");
        let adb = aps_adb::AdbLocator::new().locate(None).unwrap();
        let mut scan = ScanLoop::default();
        let mut history = timeline::History::default();
        scan.start();
        for generation in 1..=2 {
            let frame = aps_layout_inspector::capture_compatible(
                &adb,
                &serial,
                aps_layout_inspector::CompatibleTarget::ForegroundApp,
                generation,
                &CancellationToken::new(),
            )
            .unwrap();
            assert!(!frame.archive.windows.is_empty());
            assert!(frame.archive.screenshot_png.is_some());
            let archive = Arc::new(frame.archive);
            history.append(
                archive,
                timeline::Source::Adb,
                true,
                ImportTiming::default(),
            );
            eprintln!(
                "capture {generation}: package={} nodes={}",
                history.selected().unwrap().archive.frame.package_name,
                history
                    .selected()
                    .unwrap()
                    .archive
                    .windows
                    .iter()
                    .map(|w| w.nodes.len())
                    .sum::<usize>()
            );
            if generation == 1 {
                let epoch = scan.schedule().unwrap();
                std::thread::sleep(CAPTURE_INTERVAL);
                assert!(scan.take_tick(epoch));
            }
        }
        let epoch = scan.schedule().unwrap();
        scan.stop();
        assert!(!scan.take_tick(epoch));
        assert_eq!(history.frames().len(), 2);
        assert!(history.select(history.frames()[0].id));
    }
}
