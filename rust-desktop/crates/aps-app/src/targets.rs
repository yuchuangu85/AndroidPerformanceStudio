//! Application-wide ADB target controls rendered in the window title bar.
//!
//! One workspace owns the shared device and process selection for the window: it
//! refreshes `adb devices -l` and the selected device's installed packages plus
//! application processes every three seconds, keeps the selection stable across
//! refreshes, and publishes changes so feature pages can follow the title-bar
//! target. Discovery is read-only; selection never authorizes capture.

use aps_adb::{AdbClient, AdbDevice, AdbLocator, AndroidApplicationProcess};
use aps_platform::CancellationToken;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::{
        ActiveTheme, IconName, IndexPath,
        combobox::{Combobox, ComboboxState},
        searchable_list::{SearchableListItem, SearchableVec},
    },
    prelude::*,
    *,
};
use std::time::Duration;

/// How often the fixed title-bar device and process lists refresh themselves.
const REFRESH_INTERVAL: Duration = Duration::from_secs(3);

/// Selection change published to the shell so pages can follow the title target.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TargetSelectionChanged {
    pub serial: Option<String>,
    pub pid: Option<u32>,
}

/// Latest shared ADB lists. Pages subscribe instead of running their own startup
/// discovery, and the shell replays this event immediately when lists already exist.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TargetListsChanged {
    pub devices: Vec<AdbDevice>,
    pub processes: Vec<AndroidApplicationProcess>,
}

/// Read-only Home projection of the latest completed device-discovery tick.
/// An error never publishes the retained title-bar list as current device facts.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HomeDeviceOverview {
    Loading,
    Unavailable(String),
    Available(Vec<AdbDevice>),
}

#[derive(Default)]
struct DiscoveryStatus {
    // None means that no device tick has completed, not a successful empty list.
    devices: Option<Result<(), String>>,
    process_error: Option<String>,
}

impl DiscoveryStatus {
    fn record_devices(&mut self, result: Result<(), String>) {
        self.devices = Some(result);
    }

    fn record_processes(&mut self, result: Result<(), String>) {
        self.process_error = result.err();
    }

    fn home_overview(&self, devices: &[AdbDevice]) -> HomeDeviceOverview {
        match &self.devices {
            None => HomeDeviceOverview::Loading,
            Some(Err(error)) => HomeDeviceOverview::Unavailable(error.clone()),
            Some(Ok(())) => HomeDeviceOverview::Available(devices.to_vec()),
        }
    }

    fn device_error(&self) -> Option<&str> {
        self.devices.as_ref()?.as_ref().err().map(String::as_str)
    }

    fn loaded_devices(&self) -> bool {
        matches!(self.devices, Some(Ok(())))
    }
}

/// Device entry shown by the title-bar device selector.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeviceOption {
    pub serial: String,
    pub label: String,
    pub online: bool,
    pub secondary: Option<SharedString>,
}

/// Process entry shown by the title-bar process selector.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProcessOption {
    pub pid: u32,
    pub name: String,
    pub package_name: String,
    pub user: String,
}

impl SearchableListItem for DeviceOption {
    type Value = String;

    fn title(&self) -> SharedString {
        self.label.clone().into()
    }

    fn value(&self) -> &String {
        &self.serial
    }

    fn render(&self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        div()
            .flex()
            .flex_col()
            .gap_0p5()
            .min_w_0()
            .child(div().child(self.label.clone()))
            .when_some(self.secondary.clone(), |this, secondary| {
                this.child(
                    div()
                        .text_size(px(10.))
                        .text_color(cx.theme().muted_foreground)
                        .child(secondary),
                )
            })
    }

    fn matches(&self, query: &str) -> bool {
        let query = query.to_lowercase();
        self.label.to_lowercase().contains(&query) || self.serial.to_lowercase().contains(&query)
    }

    fn disabled(&self) -> bool {
        !self.online
    }
}

impl SearchableListItem for ProcessOption {
    type Value = u32;

    fn title(&self) -> SharedString {
        self.name.clone().into()
    }

    fn value(&self) -> &u32 {
        &self.pid
    }

    fn render(&self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        div()
            .flex()
            .flex_col()
            .gap_0p5()
            .min_w_0()
            .child(div().child(format!("{} ({})", self.name, self.pid)))
            .child(
                div()
                    .text_size(px(10.))
                    .text_color(cx.theme().muted_foreground)
                    .child(format!("PID {} · {}", self.pid, self.user)),
            )
    }

    fn matches(&self, query: &str) -> bool {
        let query = query.to_lowercase();
        self.name.to_lowercase().contains(&query)
            || self.package_name.to_lowercase().contains(&query)
            || self.pid.to_string().contains(&query)
            || self.user.to_lowercase().contains(&query)
    }
}

/// ADB target state shared by the whole window.
pub struct TargetsWorkspace {
    language: Language,
    devices: Vec<AdbDevice>,
    processes: Vec<AndroidApplicationProcess>,
    selected_device: Option<String>,
    selected_process: Option<u32>,
    discovery: DiscoveryStatus,
    device_select: Entity<ComboboxState<SearchableVec<DeviceOption>>>,
    process_select: Entity<ComboboxState<SearchableVec<ProcessOption>>>,
    device_items_revision: u64,
    process_items_revision: u64,
    selection_revision: u64,
    synced_device_items: u64,
    synced_process_items: u64,
    synced_selection: u64,
    emitted_selection: (Option<String>, Option<u32>),
    cancel: CancellationToken,
    _subscriptions: Vec<Subscription>,
}

impl EventEmitter<TargetSelectionChanged> for TargetsWorkspace {}
impl EventEmitter<TargetListsChanged> for TargetsWorkspace {}

impl TargetsWorkspace {
    pub fn new(language: Language, window: &mut Window, cx: &mut Context<Self>) -> Self {
        // The device list stays short enough to browse; only the process target
        // needs the search field, mirroring the Compose title-bar selectors.
        let device_select: Entity<ComboboxState<SearchableVec<DeviceOption>>> =
            cx.new(|cx| ComboboxState::new(SearchableVec::new(Vec::new()), Vec::new(), window, cx));
        let process_select: Entity<ComboboxState<SearchableVec<ProcessOption>>> = cx.new(|cx| {
            ComboboxState::new(SearchableVec::new(Vec::new()), Vec::new(), window, cx)
                .searchable(true)
        });
        let mut this = Self {
            language,
            devices: Vec::new(),
            processes: Vec::new(),
            selected_device: None,
            selected_process: None,
            discovery: DiscoveryStatus::default(),
            device_select: device_select.clone(),
            process_select: process_select.clone(),
            device_items_revision: 0,
            process_items_revision: 0,
            selection_revision: 0,
            synced_device_items: 0,
            synced_process_items: 0,
            synced_selection: 0,
            emitted_selection: (None, None),
            cancel: CancellationToken::new(),
            _subscriptions: Vec::new(),
        };
        this._subscriptions
            .push(cx.observe(&device_select, |this, state, cx| {
                if let Some(serial) = state.read(cx).selected_value() {
                    this.user_selected_device(serial, cx);
                }
            }));
        this._subscriptions
            .push(cx.observe(&process_select, |this, state, cx| {
                if let Some(pid) = state.read(cx).selected_value() {
                    this.user_selected_process(pid, cx);
                }
            }));
        this.start_refresh(cx);
        this
    }

    pub fn set_language(&mut self, language: Language, cx: &mut Context<Self>) {
        if self.language == language {
            return;
        }
        self.language = language;
        self.device_items_revision = self.device_items_revision.wrapping_add(1);
        self.process_items_revision = self.process_items_revision.wrapping_add(1);
        cx.notify();
    }

    pub fn selected_device(&self) -> Option<&str> {
        self.selected_device.as_deref()
    }

    pub fn selected_process(&self) -> Option<u32> {
        self.selected_process
    }

    pub fn current_lists(&self) -> Option<TargetListsChanged> {
        self.discovery.loaded_devices().then(|| TargetListsChanged {
            devices: self.devices.clone(),
            processes: self.processes.clone(),
        })
    }

    /// Reuses the existing three-second ADB loop; this method never starts work.
    pub fn home_overview(&self) -> HomeDeviceOverview {
        self.discovery.home_overview(&self.devices)
    }

    fn device_options(&self) -> Vec<DeviceOption> {
        device_options(&self.devices, self.language.text(TextKey::ShellOffline))
    }

    fn process_options(&self) -> Vec<ProcessOption> {
        process_options(&self.processes)
    }

    /// Replaces the discovery loop; a dropped workspace cancels its adb children.
    fn start_refresh(&mut self, cx: &mut Context<Self>) {
        let cancel = self.cancel.clone();
        cx.spawn(async move |this, cx| {
            loop {
                let serial = match this.read_with(cx, |this, _| this.selected_device.clone()) {
                    Ok(serial) => serial,
                    Err(_) => break,
                };
                let tick_cancel = cancel.clone();
                let outcome = cx
                    .background_executor()
                    .spawn(async move { discover(serial, &tick_cancel) })
                    .await;
                let alive = this
                    .update(cx, |this, cx| this.apply_refresh(outcome, cx))
                    .is_ok();
                if !alive || cancel.is_cancelled() {
                    break;
                }
                cx.background_executor().timer(REFRESH_INTERVAL).await;
            }
        })
        .detach();
    }

    /// Fetches the selected device's processes immediately after a device change.
    fn refresh_processes_now(&mut self, cx: &mut Context<Self>) {
        let Some(serial) = self.selected_device.clone() else {
            return;
        };
        let cancel = self.cancel.clone();
        let task_serial = serial.clone();
        let task = cx
            .background_executor()
            .spawn(async move { load_processes(&task_serial, &cancel) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| this.apply_snapshot(serial, result, cx))
                .ok();
        })
        .detach();
    }

    fn apply_refresh(&mut self, outcome: Result<RefreshOutcome, String>, cx: &mut Context<Self>) {
        match outcome {
            Err(error) => {
                // Keep the previous lists so the title bar never flickers empty
                // while a device is briefly unreachable.
                self.discovery.record_devices(Err(error));
            }
            Ok(outcome) => {
                self.discovery.record_devices(Ok(()));
                let previous_device = self.selected_device.clone();
                let next_device =
                    next_selected_device(self.selected_device.as_deref(), &outcome.devices);
                if next_device != previous_device {
                    self.selected_device = next_device;
                    self.selected_process = None;
                    self.processes.clear();
                    self.discovery.process_error = None;
                    self.process_items_revision = self.process_items_revision.wrapping_add(1);
                    self.selection_revision = self.selection_revision.wrapping_add(1);
                }
                if outcome.devices != self.devices {
                    self.devices = outcome.devices;
                    self.device_items_revision = self.device_items_revision.wrapping_add(1);
                }
                if let Some((serial, result)) = outcome.snapshot {
                    self.apply_snapshot(serial, result, cx);
                }
                if self.selected_device != previous_device && self.selected_device.is_some() {
                    self.refresh_processes_now(cx);
                }
            }
        }
        self.emit_selection(cx);
        cx.emit(TargetListsChanged {
            devices: self.devices.clone(),
            processes: self.processes.clone(),
        });
        cx.notify();
    }

    fn apply_snapshot(
        &mut self,
        serial: String,
        result: Result<Vec<AndroidApplicationProcess>, String>,
        cx: &mut Context<Self>,
    ) {
        // A late result from the previously selected device must not replace the
        // current device's process list.
        if self.selected_device.as_deref() != Some(serial.as_str()) {
            return;
        }
        match result {
            Ok(processes) => {
                let selected = retained_process(self.selected_process, &self.processes, &processes);
                if selected != self.selected_process {
                    self.selected_process = selected;
                    self.selection_revision = self.selection_revision.wrapping_add(1);
                }
                if processes != self.processes {
                    self.processes = processes;
                    self.process_items_revision = self.process_items_revision.wrapping_add(1);
                }
                self.discovery.record_processes(Ok(()));
            }
            Err(error) => self.discovery.record_processes(Err(error)),
        }
        self.emit_selection(cx);
        cx.emit(TargetListsChanged {
            devices: self.devices.clone(),
            processes: self.processes.clone(),
        });
        cx.notify();
    }

    fn user_selected_device(&mut self, serial: String, cx: &mut Context<Self>) {
        if self.selected_device.as_deref() == Some(serial.as_str()) {
            return;
        }
        if !self
            .devices
            .iter()
            .any(|d| d.serial == serial && d.is_online())
        {
            return;
        }
        self.selected_device = Some(serial);
        self.selected_process = None;
        self.processes.clear();
        self.process_items_revision = self.process_items_revision.wrapping_add(1);
        self.selection_revision = self.selection_revision.wrapping_add(1);
        self.discovery.process_error = None;
        self.emit_selection(cx);
        self.refresh_processes_now(cx);
        cx.notify();
    }

    fn user_selected_process(&mut self, pid: u32, cx: &mut Context<Self>) {
        if self.selected_process == Some(pid) {
            return;
        }
        if !self.processes.iter().any(|process| process.pid == pid) {
            return;
        }
        self.selected_process = Some(pid);
        self.selection_revision = self.selection_revision.wrapping_add(1);
        self.emit_selection(cx);
        cx.emit(TargetListsChanged {
            devices: self.devices.clone(),
            processes: self.processes.clone(),
        });
        cx.notify();
    }

    fn emit_selection(&mut self, cx: &mut Context<Self>) {
        let next = (self.selected_device.clone(), self.selected_process);
        if next == self.emitted_selection {
            return;
        }
        self.emitted_selection = next.clone();
        cx.emit(TargetSelectionChanged {
            serial: next.0,
            pid: next.1,
        });
    }

    fn sync_selects(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        // A combobox keeps the selected values when its items change, but the
        // selection indices point at the previous list. Re-publishing the
        // selection after replacing the items keeps both title-bar targets
        // truthful without clearing a search the user is still typing.
        if self.device_items_revision != self.synced_device_items {
            self.synced_device_items = self.device_items_revision;
            let items = SearchableVec::new(self.device_options());
            self.device_select
                .update(cx, |state, cx| state.set_items(items, window, cx));
            self.selection_revision = self.selection_revision.wrapping_add(1);
        }
        if self.process_items_revision != self.synced_process_items {
            self.synced_process_items = self.process_items_revision;
            let items = SearchableVec::new(self.process_options());
            self.process_select
                .update(cx, |state, cx| state.set_items(items, window, cx));
            self.selection_revision = self.selection_revision.wrapping_add(1);
        }
        if self.selection_revision != self.synced_selection {
            self.synced_selection = self.selection_revision;
            let device_index = self.selected_device.as_deref().and_then(|serial| {
                self.device_options()
                    .iter()
                    .position(|option| option.serial == serial)
            });
            self.device_select
                .update(cx, |state, cx| match device_index {
                    Some(row) => state.set_selected_indices([IndexPath::new(row)], window, cx),
                    None => state.set_selected_indices(Vec::new(), window, cx),
                });
            let process_index = self.selected_process.and_then(|pid| {
                self.process_options()
                    .iter()
                    .position(|option| option.pid == pid)
            });
            self.process_select
                .update(cx, |state, cx| match process_index {
                    Some(row) => state.set_selected_indices([IndexPath::new(row)], window, cx),
                    None => state.set_selected_indices(Vec::new(), window, cx),
                });
        }
    }

    fn device_placeholder(&self) -> SharedString {
        let language = self.language;
        if self.devices.is_empty() && self.discovery.device_error().is_some() {
            language.text(TextKey::ShellAdbUnavailable).into()
        } else if !self.discovery.loaded_devices() {
            language.text(TextKey::ShellDiscovering).into()
        } else {
            language.text(TextKey::ShellDevice).into()
        }
    }

    fn device_empty_label(&self) -> SharedString {
        let language = self.language;
        if self.discovery.device_error().is_some() {
            language.text(TextKey::ShellAdbUnavailable).into()
        } else if self.discovery.loaded_devices() {
            language.text(TextKey::ShellNoDevicesFound).into()
        } else {
            language.text(TextKey::ShellDiscovering).into()
        }
    }

    fn process_empty_label(&self) -> SharedString {
        let language = self.language;
        if self.selected_device.is_none() {
            return language.text(TextKey::ShellSelectDeviceFirst).into();
        }
        if let Some(error) = self.discovery.device_error() {
            return error.to_owned().into();
        }
        if let Some(error) = &self.discovery.process_error {
            return error.clone().into();
        }
        language.text(TextKey::ShellNoProcessesFound).into()
    }
}

impl Drop for TargetsWorkspace {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}

/// Compose title-bar target width; both selectors keep the same footprint.
const TARGET_SELECTOR_WIDTH: f32 = 200.;

/// Fixed-size slot that keeps the combobox popover anchored under its control.
fn target_slot(id: &'static str, control: impl IntoElement) -> impl IntoElement {
    div()
        .id(id)
        .w(px(TARGET_SELECTOR_WIDTH))
        .h(px(crate::studio::metrics::COMPACT_CONTROL_HEIGHT))
        .child(control)
}

/// Compose `DropdownSelector` row: accent label and a trailing expand chevron.
/// The title-bar targets use the same surface as every other dropdown instead of
/// the component library's form-field chrome.
fn target_trigger(
    id: &'static str,
    label: SharedString,
    accessibility_label: SharedString,
    open: bool,
    disabled: bool,
    colors: &crate::studio::StudioColors,
) -> AnyElement {
    let content = if disabled {
        colors.accent.alpha(0.48)
    } else {
        colors.accent
    };
    div()
        .id(id)
        .role(Role::ComboBox)
        .aria_label(accessibility_label)
        .aria_value(label.clone())
        .aria_expanded(open)
        .w_full()
        .flex()
        .items_center()
        .gap(px(4.))
        .text_color(content)
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(crate::studio::FONT_SUPPORTING))
                .line_height(px(15.))
                .whitespace_nowrap()
                .text_ellipsis()
                .child(label),
        )
        .child(
            div()
                .flex_shrink_0()
                .text_size(px(12.))
                .child(IconName::ChevronDown),
        )
        .into_any_element()
}

/// Empty-state row shared by both target menus.
fn target_menu_empty(label: SharedString, cx: &App) -> Div {
    div()
        .px_2()
        .py_1()
        .text_size(px(12.))
        .text_color(cx.theme().muted_foreground)
        .child(label)
}

impl Render for TargetsWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        self.sync_selects(window, cx);
        let language = self.language;
        let colors = crate::studio::StudioColors::from_theme(cx);
        let device_placeholder: SharedString = self.device_placeholder();
        let process_placeholder: SharedString = language.text(TextKey::ShellProcess).into();
        let search_placeholder: SharedString = language.text(TextKey::ShellSearchProcesses).into();
        let device_empty = self.device_empty_label();
        let process_empty = self.process_empty_label();
        let device_accessibility: SharedString = language.text(TextKey::ShellDevice).into();
        let process_accessibility: SharedString = language.text(TextKey::ShellProcess).into();
        let process_disabled = self.selected_device.is_none();
        let (process_border, process_surface) = if process_disabled {
            (
                colors.accent.alpha(0.55),
                colors.section_background.alpha(0.55),
            )
        } else {
            (colors.accent, colors.section_background)
        };
        div()
            .id("titlebar-targets")
            .flex()
            .items_center()
            .gap(px(8.))
            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
            .child(target_slot(
                "titlebar-device-slot",
                Combobox::new(&self.device_select)
                    .appearance(false)
                    .w(px(TARGET_SELECTOR_WIDTH))
                    .h(px(crate::studio::metrics::COMPACT_CONTROL_HEIGHT))
                    .flex()
                    .items_center()
                    .justify_between()
                    .px(px(8.))
                    .bg(colors.section_background)
                    .border_1()
                    .border_color(colors.accent)
                    .rounded(px(crate::studio::metrics::SMALL_RADIUS))
                    .placeholder(device_placeholder)
                    .menu_width(px(280.))
                    .menu_max_h(px(320.))
                    .empty(move |_, cx| target_menu_empty(device_empty.clone(), cx))
                    .render_trigger(move |trigger, _, _| {
                        let label = trigger
                            .selection()
                            .first()
                            .map(|(_, item)| item.title())
                            .or_else(|| trigger.placeholder().cloned())
                            .unwrap_or_default();
                        target_trigger(
                            "titlebar-device-trigger",
                            label,
                            device_accessibility.clone(),
                            trigger.is_open(),
                            trigger.is_disabled(),
                            &colors,
                        )
                    }),
            ))
            .child(target_slot(
                "titlebar-process-slot",
                Combobox::new(&self.process_select)
                    .appearance(false)
                    .w(px(TARGET_SELECTOR_WIDTH))
                    .h(px(crate::studio::metrics::COMPACT_CONTROL_HEIGHT))
                    .flex()
                    .items_center()
                    .justify_between()
                    .px(px(8.))
                    .bg(process_surface)
                    .border_1()
                    .border_color(process_border)
                    .rounded(px(crate::studio::metrics::SMALL_RADIUS))
                    .placeholder(process_placeholder)
                    .search_placeholder(search_placeholder)
                    .menu_width(px(320.))
                    .menu_max_h(px(360.))
                    .disabled(process_disabled)
                    .empty(move |_, cx| target_menu_empty(process_empty.clone(), cx))
                    .render_trigger(move |trigger, _, _| {
                        let label = trigger
                            .selection()
                            .first()
                            .map(|(_, item)| item.title())
                            .or_else(|| trigger.placeholder().cloned())
                            .unwrap_or_default();
                        target_trigger(
                            "titlebar-process-trigger",
                            label,
                            process_accessibility.clone(),
                            trigger.is_open(),
                            trigger.is_disabled(),
                            &colors,
                        )
                    }),
            ))
    }
}

struct RefreshOutcome {
    devices: Vec<AdbDevice>,
    snapshot: Option<(String, Result<Vec<AndroidApplicationProcess>, String>)>,
}

/// Blocking discovery tick: devices first, then the selected device's targets.
fn discover(serial: Option<String>, cancel: &CancellationToken) -> Result<RefreshOutcome, String> {
    let location = AdbLocator::new()
        .locate(None)
        .map_err(|error| error.to_string())?;
    let client = AdbClient::new(location);
    let devices = client
        .list_devices(cancel)
        .map_err(|error| error.to_string())?;
    let snapshot = serial
        .filter(|serial| {
            devices
                .iter()
                .any(|device| device.serial == *serial && device.is_online())
        })
        .map(|serial| {
            let result = load_processes_with(&client, &serial, cancel);
            (serial, result)
        });
    Ok(RefreshOutcome { devices, snapshot })
}

fn load_processes(
    serial: &str,
    cancel: &CancellationToken,
) -> Result<Vec<AndroidApplicationProcess>, String> {
    let location = AdbLocator::new()
        .locate(None)
        .map_err(|error| error.to_string())?;
    let client = AdbClient::new(location);
    load_processes_with(&client, serial, cancel)
}

fn load_processes_with(
    client: &AdbClient,
    serial: &str,
    cancel: &CancellationToken,
) -> Result<Vec<AndroidApplicationProcess>, String> {
    client
        .target_snapshot(serial, cancel)
        .map(|snapshot| snapshot.application_processes())
        .map_err(|error| error.to_string())
}

fn device_options(devices: &[AdbDevice], offline_label: &str) -> Vec<DeviceOption> {
    let offline: SharedString = offline_label.to_owned().into();
    devices
        .iter()
        .map(|device| DeviceOption {
            serial: device.serial.clone(),
            label: device.display_name(),
            online: device.is_online(),
            secondary: (!device.is_online()).then(|| offline.clone()),
        })
        .collect()
}

fn process_options(processes: &[AndroidApplicationProcess]) -> Vec<ProcessOption> {
    processes
        .iter()
        .map(|process| ProcessOption {
            pid: process.pid,
            name: process.name.clone(),
            package_name: process.package_name.clone(),
            user: process.user.clone(),
        })
        .collect()
}

/// Keeps a selected device while it is connected, otherwise adopts a single
/// connected device. Multiple connected devices require an explicit choice.
fn next_selected_device(current: Option<&str>, devices: &[AdbDevice]) -> Option<String> {
    if let Some(serial) = current
        && devices
            .iter()
            .any(|device| device.serial == serial && device.is_online())
    {
        return Some(serial.to_owned());
    }
    let mut online = devices.iter().filter(|device| device.is_online());
    match (online.next(), online.next()) {
        (Some(device), None) => Some(device.serial.clone()),
        _ => None,
    }
}

/// PID alone is not a process-instance identity: a changed descriptor clears the
/// selection, as does a process that disappeared.
fn retained_process(
    current: Option<u32>,
    previous: &[AndroidApplicationProcess],
    next: &[AndroidApplicationProcess],
) -> Option<u32> {
    let pid = current?;
    let old = previous.iter().find(|process| process.pid == pid)?;
    next.iter().any(|process| process == old).then_some(pid)
}

#[cfg(test)]
mod tests {
    use aps_adb::{AdbDevice, AndroidApplicationProcess, DeviceState};
    use std::collections::BTreeMap;

    use gpui_kit::component::searchable_list::SearchableListItem;

    use super::{
        DiscoveryStatus, HomeDeviceOverview, device_options, next_selected_device, process_options,
        retained_process,
    };

    fn device(serial: &str, state: DeviceState) -> AdbDevice {
        AdbDevice {
            serial: serial.into(),
            state,
            raw_state: match state {
                DeviceState::Device => "device",
                DeviceState::Offline => "offline",
                _ => "unknown",
            }
            .into(),
            attributes: BTreeMap::new(),
            status_detail: None,
        }
    }

    #[test]
    fn home_distinguishes_first_loading_successful_empty_and_discovery_failure() {
        let mut status = DiscoveryStatus::default();
        assert_eq!(status.home_overview(&[]), HomeDeviceOverview::Loading);
        status.record_devices(Ok(()));
        assert_eq!(
            status.home_overview(&[]),
            HomeDeviceOverview::Available(vec![])
        );
        status.record_devices(Err("ADB unavailable".into()));
        assert_eq!(
            status.home_overview(&[]),
            HomeDeviceOverview::Unavailable("ADB unavailable".into())
        );
    }

    #[test]
    fn process_failures_do_not_invalidate_successfully_discovered_devices() {
        let mut status = DiscoveryStatus::default();
        let devices = vec![device("online", DeviceState::Device)];
        status.record_devices(Ok(()));
        status.record_processes(Err("process access denied".into()));
        assert_eq!(
            status.home_overview(&devices),
            HomeDeviceOverview::Available(devices)
        );
        assert_eq!(
            status.process_error.as_deref(),
            Some("process access denied")
        );
    }

    #[test]
    fn home_does_not_publish_retained_devices_after_discovery_failure() {
        let mut status = DiscoveryStatus::default();
        let retained = vec![device("last-good", DeviceState::Device)];
        status.record_devices(Ok(()));
        status.record_devices(Err("device discovery failed".into()));
        // A late process success cannot erase the independent device failure.
        status.record_processes(Ok(()));
        assert_eq!(
            status.home_overview(&retained),
            HomeDeviceOverview::Unavailable("device discovery failed".into())
        );
        status.record_devices(Ok(()));
        assert_eq!(
            status.home_overview(&[]),
            HomeDeviceOverview::Available(vec![])
        );
    }

    fn process(pid: u32, name: &str, user: &str) -> AndroidApplicationProcess {
        AndroidApplicationProcess {
            pid,
            parent_pid: 1,
            user: user.into(),
            name: name.into(),
            package_name: name.split(':').next().unwrap_or(name).into(),
        }
    }

    #[test]
    fn keeps_the_selected_device_while_it_is_online() {
        let devices = [
            device("a", DeviceState::Device),
            device("b", DeviceState::Device),
        ];
        assert_eq!(next_selected_device(Some("b"), &devices), Some("b".into()));
    }

    #[test]
    fn adopts_a_single_online_device() {
        let devices = [
            device("offline", DeviceState::Offline),
            device("only", DeviceState::Device),
        ];
        assert_eq!(next_selected_device(None, &devices), Some("only".into()));
        assert_eq!(
            next_selected_device(Some("gone"), &devices),
            Some("only".into())
        );
    }

    #[test]
    fn requires_a_choice_between_multiple_online_devices() {
        let devices = [
            device("a", DeviceState::Device),
            device("b", DeviceState::Device),
        ];
        assert_eq!(next_selected_device(None, &devices), None);
        assert_eq!(next_selected_device(Some("offline"), &devices), None);
    }

    #[test]
    fn retains_a_process_only_while_its_descriptor_is_unchanged() {
        let previous = [process(42, "com.example.app", "u0_a1")];
        assert_eq!(retained_process(Some(42), &previous, &previous), Some(42));
        let restarted = [process(42, "com.example.app:remote", "u0_a1")];
        assert_eq!(retained_process(Some(42), &previous, &restarted), None);
        let changed_user = [process(42, "com.example.app", "u0_a2")];
        assert_eq!(retained_process(Some(42), &previous, &changed_user), None);
        let missing = [process(43, "com.example.app", "u0_a1")];
        assert_eq!(retained_process(Some(42), &previous, &missing), None);
    }

    fn device_with_model(serial: &str, state: DeviceState, model: &str) -> AdbDevice {
        let mut device = device(serial, state);
        device.attributes.insert("model".into(), model.into());
        device
    }

    #[test]
    fn maps_devices_with_offline_secondary_labels() {
        let devices = [
            device_with_model("online", DeviceState::Device, "Pixel_8"),
            device("offline", DeviceState::Offline),
        ];
        let options = device_options(&devices, "Offline");
        assert_eq!(options.len(), 2);
        assert_eq!(options[0].label, "Pixel_8 (online)");
        assert!(!options[0].disabled());
        assert_eq!(options[1].label, "offline");
        assert!(options[1].disabled());
        assert_eq!(options[1].secondary.as_deref(), Some("Offline"));
        assert!(SearchableListItem::matches(&options[0], "pixel"));
        assert!(SearchableListItem::matches(&options[0], "online"));
        assert!(!SearchableListItem::matches(&options[0], "emulator"));
    }

    #[test]
    fn maps_application_processes_for_the_searchable_menu() {
        let processes = [
            process(1234, "com.example.app", "u0_a10"),
            process(1235, "com.example.app:remote", "u0_a10"),
        ];
        let options = process_options(&processes);
        assert_eq!(options.len(), 2);
        assert_eq!(options[0].title().to_string(), "com.example.app");
        assert_eq!(options[0].value(), &1234);
        assert_eq!(options[1].package_name, "com.example.app");
        assert!(SearchableListItem::matches(&options[0], "1234"));
        assert!(SearchableListItem::matches(&options[0], "u0_a10"));
        assert!(SearchableListItem::matches(&options[1], "remote"));
        assert!(!SearchableListItem::matches(&options[0], "surfaceflinger"));
    }
}
