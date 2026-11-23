//! Compose Perfetto capture parity for the Trace Analyzer.
//!
//! Mirrors the Compose oracle:
//! - PerfettoCaptureConfig.kt (templates, probe groups, probes, capabilities,
//!   config, state, device) and PerfettoConfigTextBuilder.kt;
//! - PerfettoCapabilityDetector.kt (build properties + data source list);
//! - PerfettoCaptureSession.kt (explicit device capture: push config, start
//!   with perfetto --txt -c - -o ... --background-wait, pid, stop, pull,
//!   device temp cleanup, automatic completion, artifact metadata).
//!
//! Capture is explicit and device-selected: nothing here runs unless the user
//! selects an online device and presses Start Capture. ADB commands are fixed
//! argument vectors executed through aps_platform; the only device-supplied
//! value is the serial, validated by aps_adb::validate_serial. Device output
//! is parsed defensively and bounded.

use aps_adb::{AdbDevice, AdbLocator, validate_serial};
use aps_platform::{
    CancellationToken, MAX_OUTPUT_LIMIT, ProcessError, ProcessOutput, ProcessRequest, ProcessRunner,
};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::component::input::{InputEvent, InputState, TextareaState};
use gpui_kit::{prelude::*, *};
// page.rs entry point (also reachable as capture_page::capture_page).
#[allow(unused_imports)]
pub(in crate::trace) use super::capture_page::capture_page;

use std::{
    collections::{BTreeMap, BTreeSet},
    ffi::OsString,
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

/// Legacy path is retained only for explicitly requested recovery tests.
#[cfg(test)]
const DEVICE_TRACE_PATH: &str = "/data/misc/perfetto-traces/aps-perfetto-trace.pftrace";
const REMOTE_STOP_POLL_ATTEMPTS: usize = 20;
const REMOTE_STOP_POLL_DELAY: Duration = Duration::from_millis(250);
const REMOTE_GRACEFUL_STOP_TIMEOUT: Duration = Duration::from_secs(60);
const ADB_QUERY_TIMEOUT: Duration = Duration::from_secs(15);
const ADB_START_TIMEOUT: Duration = Duration::from_secs(60);
const ADB_SHORT_TIMEOUT: Duration = Duration::from_secs(5);
const ADB_TRANSFER_TIMEOUT: Duration = Duration::from_secs(120);
const ADB_DEVICE_PUSH_TIMEOUT: Duration = Duration::from_secs(30);
const SHELL_OUTPUT_LIMIT: usize = 512 * 1024;
const STARTUP_UNCONFIRMED: &str = "Capture uses background launch without waiting for every data source's startup acknowledgement. Data-source readiness and complete coverage remain unverified.";
const CAPTURE_CONFIG_PATH_SEGMENT: &str = "captures";
const DEVICE_INFO_COMMAND: &str = "getprop ro.product.model; getprop ro.build.version.sdk";
const DEVICE_BUILD_COMMAND: &str = "getprop ro.build.version.sdk; getprop ro.build.type";
/// Runs on the device shell: adb itself has no `perfetto` command, so omitting
/// `shell` fails every query and reports all devices as Perfetto-less.
const PERFETTO_QUERY_ARGUMENTS: [&str; 3] = ["shell", "perfetto", "--query"];

static CAPTURE_SEQUENCE: AtomicU64 = AtomicU64::new(0);

// ---------------------------------------------------------------------------
// Capture model: templates, probe groups, probes (PerfettoCaptureConfig.kt).
// ---------------------------------------------------------------------------

/// Preset trace templates, mirroring PerfettoTraceTemplate.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(in crate::trace) enum CaptureTemplate {
    #[default]
    SystemOverview,
    AppPerformance,
    GfxPipeline,
    InputLatency,
    MemoryProfile,
    FullCapture,
    Custom,
}

impl CaptureTemplate {
    pub(in crate::trace) const ALL: [Self; 7] = [
        Self::SystemOverview,
        Self::AppPerformance,
        Self::GfxPipeline,
        Self::InputLatency,
        Self::MemoryProfile,
        Self::FullCapture,
        Self::Custom,
    ];

    pub(in crate::trace) fn label(self) -> TextKey {
        match self {
            Self::SystemOverview => TextKey::SystemOverview,
            Self::AppPerformance => TextKey::AppPerformance,
            Self::GfxPipeline => TextKey::GraphicsPipeline,
            Self::InputLatency => TextKey::InputLatency,
            Self::MemoryProfile => TextKey::MemoryProfile,
            Self::FullCapture => TextKey::FullCapture,
            Self::Custom => TextKey::Custom,
        }
    }

    pub(in crate::trace) fn description(self) -> TextKey {
        match self {
            Self::SystemOverview => TextKey::SystemOverviewDescription,
            Self::AppPerformance => TextKey::AppPerformanceDescription,
            Self::GfxPipeline => TextKey::GraphicsPipelineDescription,
            Self::InputLatency => TextKey::InputLatencyDescription,
            Self::MemoryProfile => TextKey::MemoryProfileDescription,
            Self::FullCapture => TextKey::FullCaptureDescription,
            Self::Custom => TextKey::CustomDescription,
        }
    }

    /// Broad captures expose the owned client without an all-sources barrier.
    fn uses_supervised_launch(self) -> bool {
        matches!(self, Self::FullCapture | Self::Custom)
    }

    /// Preset probes before device/target filtering; Custom starts empty.
    pub(in crate::trace) fn default_probes(self) -> BTreeSet<Probe> {
        use Probe::*;
        match self {
            Self::SystemOverview => [
                CpuUsage,
                CpuScheduling,
                CpuFrequency,
                KernelMeminfo,
                Vmstat,
                ProcessStats,
                FilesystemIo,
                PageFaults,
                ArtRuntime,
                Thermal,
                Atrace,
                FrameTimeline,
            ]
            .into_iter()
            .collect(),
            Self::AppPerformance => [
                CpuUsage,
                CpuScheduling,
                ProcessStats,
                FilesystemIo,
                PageFaults,
                ArtRuntime,
                Atrace,
                FrameTimeline,
            ]
            .into_iter()
            .collect(),
            Self::GfxPipeline => [
                CpuScheduling,
                CpuFrequency,
                GpuFrequency,
                Atrace,
                FrameTimeline,
            ]
            .into_iter()
            .collect(),
            Self::InputLatency => [CpuScheduling, Atrace].into_iter().collect(),
            Self::MemoryProfile => [
                NativeHeap,
                KernelMeminfo,
                Vmstat,
                HighFrequencyMemory,
                LowMemoryKiller,
                ProcessStats,
                PageFaults,
                ArtRuntime,
                Atrace,
            ]
            .into_iter()
            .collect(),
            Self::FullCapture => Probe::ALL.into_iter().collect(),
            Self::Custom => BTreeSet::new(),
        }
    }
}

/// Compose displays these raw group names; they are intentionally not localized.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(in crate::trace) enum ProbeGroup {
    Cpu,
    Io,
    Runtime,
    Memory,
    Power,
    Gpu,
    Android,
    Network,
    StackSampling,
    PerfettoSdk,
    Advanced,
}

impl ProbeGroup {
    pub(in crate::trace) const ALL: [Self; 11] = [
        Self::Cpu,
        Self::Io,
        Self::Runtime,
        Self::Memory,
        Self::Power,
        Self::Gpu,
        Self::Android,
        Self::Network,
        Self::StackSampling,
        Self::PerfettoSdk,
        Self::Advanced,
    ];

    pub(in crate::trace) fn label(self) -> &'static str {
        match self {
            Self::Cpu => "CPU",
            Self::Io => "I/O",
            Self::Runtime => "ART Runtime",
            Self::Memory => "Memory",
            Self::Power => "Power",
            Self::Gpu => "GPU",
            Self::Android => "Android apps & services",
            Self::Network => "Network",
            Self::StackSampling => "Stack sampling",
            Self::PerfettoSdk => "Perfetto SDK",
            Self::Advanced => "Advanced",
        }
    }
}

/// Android-relevant probes exposed by Perfetto's record page.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(in crate::trace) enum Probe {
    CpuUsage,
    CpuScheduling,
    CpuFrequency,
    Syscalls,
    FilesystemIo,
    PageFaults,
    ArtRuntime,
    NativeHeap,
    JavaHeapDump,
    KernelMeminfo,
    Vmstat,
    HighFrequencyMemory,
    LowMemoryKiller,
    ProcessStats,
    PowerRails,
    BoardVoltages,
    Thermal,
    GpuFrequency,
    GpuMemory,
    GpuWorkPeriod,
    GpuRenderStages,
    MaliFenceEvents,
    Atrace,
    AndroidLog,
    FrameTimeline,
    GameInterventions,
    NetworkPackets,
    WifiNetwork,
    CallstackSampling,
    TrackEvents,
    AdvancedFtrace,
    ProcessThreadAssociation,
}

impl Probe {
    pub(in crate::trace) const ALL: [Self; 32] = [
        Self::CpuUsage,
        Self::CpuScheduling,
        Self::CpuFrequency,
        Self::Syscalls,
        Self::FilesystemIo,
        Self::PageFaults,
        Self::ArtRuntime,
        Self::NativeHeap,
        Self::JavaHeapDump,
        Self::KernelMeminfo,
        Self::Vmstat,
        Self::HighFrequencyMemory,
        Self::LowMemoryKiller,
        Self::ProcessStats,
        Self::PowerRails,
        Self::BoardVoltages,
        Self::Thermal,
        Self::GpuFrequency,
        Self::GpuMemory,
        Self::GpuWorkPeriod,
        Self::GpuRenderStages,
        Self::MaliFenceEvents,
        Self::Atrace,
        Self::AndroidLog,
        Self::FrameTimeline,
        Self::GameInterventions,
        Self::NetworkPackets,
        Self::WifiNetwork,
        Self::CallstackSampling,
        Self::TrackEvents,
        Self::AdvancedFtrace,
        Self::ProcessThreadAssociation,
    ];

    pub(in crate::trace) fn group(self) -> ProbeGroup {
        use Probe::*;
        match self {
            CpuUsage | CpuScheduling | CpuFrequency | Syscalls => ProbeGroup::Cpu,
            FilesystemIo | PageFaults => ProbeGroup::Io,
            ArtRuntime => ProbeGroup::Runtime,
            NativeHeap | JavaHeapDump | KernelMeminfo | Vmstat | HighFrequencyMemory
            | LowMemoryKiller | ProcessStats => ProbeGroup::Memory,
            PowerRails | BoardVoltages | Thermal => ProbeGroup::Power,
            GpuFrequency | GpuMemory | GpuWorkPeriod | GpuRenderStages | MaliFenceEvents => {
                ProbeGroup::Gpu
            }
            Atrace | AndroidLog | FrameTimeline | GameInterventions | NetworkPackets => {
                ProbeGroup::Android
            }
            WifiNetwork => ProbeGroup::Network,
            CallstackSampling => ProbeGroup::StackSampling,
            TrackEvents => ProbeGroup::PerfettoSdk,
            AdvancedFtrace | ProcessThreadAssociation => ProbeGroup::Advanced,
        }
    }

    /// Raw Compose display names (PerfettoProbe.displayName), not localized.
    pub(in crate::trace) fn label(self) -> &'static str {
        use Probe::*;
        match self {
            CpuUsage => "Coarse CPU usage",
            CpuScheduling => "Scheduling details",
            CpuFrequency => "CPU frequency and idle states",
            Syscalls => "Syscalls",
            FilesystemIo => "Filesystem and block I/O",
            PageFaults => "Page faults",
            ArtRuntime => "ART GC, JIT and class loading",
            NativeHeap => "Native heap profiling",
            JavaHeapDump => "Java heap dumps",
            KernelMeminfo => "Kernel meminfo",
            Vmstat => "Virtual memory stats",
            HighFrequencyMemory => "High-frequency memory events",
            LowMemoryKiller => "Low memory killer",
            ProcessStats => "Process stats",
            PowerRails => "Battery drain & power rails",
            BoardVoltages => "Board voltages & frequencies",
            Thermal => "Thermal and throttling signals",
            GpuFrequency => "GPU frequency",
            GpuMemory => "GPU memory",
            GpuWorkPeriod => "GPU work period",
            GpuRenderStages => "GPU render stages",
            MaliFenceEvents => "Mali fence events",
            Atrace => "Atrace userspace annotations",
            AndroidLog => "Event log (logcat)",
            FrameTimeline => "Frame timeline",
            GameInterventions => "Game intervention list",
            NetworkPackets => "Network packet tracing",
            WifiNetwork => "Wi-Fi and network events",
            CallstackSampling => "Callstack sampling",
            TrackEvents => "Track events",
            AdvancedFtrace => "Kernel tracing (ftrace)",
            ProcessThreadAssociation => "Process and thread association",
        }
    }

    pub(in crate::trace) fn minimum_android_sdk(self) -> u32 {
        use Probe::*;
        match self {
            NativeHeap => 29,
            JavaHeapDump => 30,
            GpuMemory => 31,
            FrameTimeline => 31,
            GpuWorkPeriod => 34,
            GameInterventions => 33,
            NetworkPackets => 34,
            _ => 0,
        }
    }

    pub(in crate::trace) fn required_data_source(self) -> Option<&'static str> {
        use Probe::*;
        match self {
            CpuUsage | CpuFrequency | KernelMeminfo | Vmstat => Some("linux.sys_stats"),
            CpuScheduling | Syscalls | FilesystemIo | PageFaults | ArtRuntime
            | HighFrequencyMemory | LowMemoryKiller | BoardVoltages | Thermal | GpuFrequency
            | GpuWorkPeriod | MaliFenceEvents | Atrace | WifiNetwork | AdvancedFtrace => {
                Some("linux.ftrace")
            }
            NativeHeap => Some("android.heapprofd"),
            JavaHeapDump => Some("android.java_hprof"),
            ProcessStats | ProcessThreadAssociation => Some("linux.process_stats"),
            PowerRails => Some("android.power"),
            GpuMemory => Some("android.gpu.memory"),
            GpuRenderStages => Some("gpu.renderstages"),
            AndroidLog => Some("android.log"),
            FrameTimeline => Some("android.surfaceflinger.frametimeline"),
            GameInterventions => Some("android.game_interventions"),
            NetworkPackets => Some("android.network_packets"),
            CallstackSampling => Some("linux.perf"),
            TrackEvents => Some("track_event"),
        }
    }

    pub(in crate::trace) fn requires_target_package(self) -> bool {
        matches!(self, Self::NativeHeap | Self::JavaHeapDump)
    }
}

/// PerfettoDeviceCapabilities: one observed device, not a durable claim.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(in crate::trace) struct DeviceCapabilities {
    pub(in crate::trace) android_sdk: u32,
    pub(in crate::trace) build_type: String,
    pub(in crate::trace) data_source_names: BTreeSet<String>,
    pub(in crate::trace) query_error: Option<String>,
    pub(in crate::trace) atrace_categories: BTreeMap<String, String>,
    pub(in crate::trace) category_query_error: Option<String>,
}

/// Why a probe cannot be enabled for the observed device or config.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::trace) enum ProbeBlocker {
    AndroidSdk(u32),
    DebugBuild,
    DataSource(&'static str),
    TargetPackage,
}

impl ProbeBlocker {
    pub(in crate::trace) fn message(self, language: Language) -> String {
        match self {
            Self::AndroidSdk(sdk) => {
                language.format(TextKey::ProbeRequiresAndroid, &[("value1", &sdk)])
            }
            Self::DebugBuild => language.text(TextKey::ProbeRequiresDebugBuild).to_owned(),
            Self::DataSource(name) => {
                language.format(TextKey::ProbeDataSourceUnavailable, &[("value1", &name)])
            }
            Self::TargetPackage => language
                .text(TextKey::ProbeRequiresTargetPackage)
                .to_owned(),
        }
    }
}

impl DeviceCapabilities {
    /// Mirrors PerfettoDeviceCapabilities.unsupportedReason.
    pub(in crate::trace) fn probe_blocker(&self, probe: Probe) -> Option<ProbeBlocker> {
        let source = probe.required_data_source();
        let source_unavailable = self.query_error.is_none()
            && !self.data_source_names.is_empty()
            && source.is_some_and(|source| !self.data_source_names.contains(source));
        let unsupported_build = probe == Probe::Syscalls
            && !self.build_type.is_empty()
            && self.build_type != "unknown"
            && self.build_type != "userdebug"
            && self.build_type != "eng";
        if self.android_sdk > 0 && self.android_sdk < probe.minimum_android_sdk() {
            Some(ProbeBlocker::AndroidSdk(probe.minimum_android_sdk()))
        } else if unsupported_build {
            Some(ProbeBlocker::DebugBuild)
        } else if source_unavailable {
            Some(ProbeBlocker::DataSource(source.unwrap_or_default()))
        } else {
            None
        }
    }
}

/// PerfettoCaptureConfig: exactly one explicit capture request.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct CaptureConfig {
    pub(in crate::trace) template: CaptureTemplate,
    pub(in crate::trace) target_package: Option<String>,
    pub(in crate::trace) duration_seconds: u32,
    pub(in crate::trace) buffer_size_kb: u32,
    pub(in crate::trace) additional_categories: Vec<String>,
    pub(in crate::trace) custom_config_text: Option<String>,
    pub(in crate::trace) enabled_probes: Option<BTreeSet<Probe>>,
    pub(in crate::trace) atrace_category_selection: Option<BTreeSet<String>>,
}

impl CaptureConfig {
    pub(in crate::trace) fn new(template: CaptureTemplate) -> Self {
        Self {
            template,
            target_package: None,
            duration_seconds: 10,
            buffer_size_kb: 32_768,
            additional_categories: Vec::new(),
            custom_config_text: None,
            // FullCapture needs an observed capability snapshot before opting in.
            enabled_probes: (template == CaptureTemplate::FullCapture).then(BTreeSet::new),
            atrace_category_selection: (template == CaptureTemplate::FullCapture)
                .then(BTreeSet::new),
        }
    }

    /// Compose's require guards, surfaced as a recoverable error.
    pub(in crate::trace) fn validate(&self) -> Result<(), String> {
        if !(1..=600).contains(&self.duration_seconds) {
            return Err("Capture duration must be between 1 and 600 seconds".to_owned());
        }
        if !(1_024..=1_048_576).contains(&self.buffer_size_kb) {
            return Err("Capture buffer must be between 1 and 1024 MB".to_owned());
        }
        if self.template == CaptureTemplate::FullCapture
            && self
                .target_package
                .as_deref()
                .is_some_and(|target| !is_target_package(target))
        {
            return Err("Full capture requires a real target package or no target".to_owned());
        }
        if self.template == CaptureTemplate::Custom
            && self
                .custom_config_text
                .as_deref()
                .map(str::trim)
                .is_none_or(str::is_empty)
        {
            return Err("Custom capture requires a TraceConfig text protobuf".to_owned());
        }
        Ok(())
    }
}

/// One capture session's observable state (PerfettoCaptureState).
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) enum CaptureState {
    Idle,
    Preparing {
        stop_requested: bool,
    },
    Recording {
        started_unix_ms: u64,
    },
    Pulling,
    Completed {
        trace_path: PathBuf,
        size_bytes: u64,
    },
    Failed(String),
}

impl CaptureState {
    pub(in crate::trace) fn shows_stop_button(&self) -> bool {
        matches!(self, Self::Preparing { .. } | Self::Recording { .. })
    }

    pub(in crate::trace) fn stop_requested(&self) -> bool {
        matches!(
            self,
            Self::Preparing {
                stop_requested: true
            }
        )
    }

    /// Keep the launch alive until it returns its owned handle, then stop/save.
    fn request_startup_stop(&mut self) -> bool {
        if let Self::Preparing { stop_requested } = self {
            *stop_requested = true;
            true
        } else {
            false
        }
    }

    pub(in crate::trace) fn in_progress(&self) -> bool {
        matches!(
            self,
            Self::Preparing { .. } | Self::Recording { .. } | Self::Pulling
        )
    }
}

/// The bounded evidence published after one successful capture.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct CaptureOutput {
    pub(in crate::trace) session_id: String,
    pub(in crate::trace) artifact_id: String,
    pub(in crate::trace) display_name: String,
    pub(in crate::trace) trace_path: PathBuf,
    pub(in crate::trace) duration_nanos: u64,
    pub(in crate::trace) captured_unix_ms: u64,
    pub(in crate::trace) device_model: String,
    pub(in crate::trace) android_sdk: u32,
    pub(in crate::trace) trace_size_bytes: u64,
    pub(in crate::trace) completeness_label: String,
    pub(in crate::trace) limitations: Vec<String>,
    pub(in crate::trace) warnings: Vec<String>,
    pub(in crate::trace) config_summary: Option<String>,
    pub(in crate::trace) integrity: CaptureIntegritySummary,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct CaptureIntegritySummary {
    pub(in crate::trace) status: String,
    pub(in crate::trace) messages: Vec<String>,
    pub(in crate::trace) report_path: Option<PathBuf>,
}

/// One target row from adb devices -l; offline rows stay visible but not selectable.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct CaptureDevice {
    pub serial: String,
    pub display_name: String,
    pub online: bool,
}

// ---------------------------------------------------------------------------
// Config text builder (PerfettoConfigTextBuilder.kt).
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
struct DataSource {
    name: &'static str,
    config: String,
}

const SYS_STATS_PROBES: [Probe; 4] = [
    Probe::CpuUsage,
    Probe::CpuFrequency,
    Probe::KernelMeminfo,
    Probe::Vmstat,
];
const PROCESS_STATS_PROBES: [Probe; 2] = [Probe::ProcessStats, Probe::ProcessThreadAssociation];

/// Builds a text-proto TraceConfig accepted by the Android perfetto --txt CLI.
fn build_config_text(config: &CaptureConfig) -> String {
    if config.template == CaptureTemplate::Custom
        && let Some(custom) = config
            .custom_config_text
            .as_deref()
            .filter(|text| !text.trim().is_empty())
    {
        return custom.to_owned();
    }

    let probes = config
        .enabled_probes
        .clone()
        .unwrap_or_else(|| config.template.default_probes());
    let mut extra_ftrace_events = Vec::new();
    let mut extra_atrace_categories = Vec::new();
    for category in &config.additional_categories {
        if category.contains('/') {
            extra_ftrace_events.push(category.clone());
        } else {
            extra_atrace_categories.push(category.clone());
        }
    }

    let mut ftrace_events: Vec<String> = probes
        .iter()
        .flat_map(|probe| {
            ftrace_events(*probe)
                .iter()
                .map(|event| (*event).to_owned())
        })
        .collect();
    ftrace_events.extend(extra_ftrace_events);
    dedup_preserving_order(&mut ftrace_events);

    let full_capture = config.template == CaptureTemplate::FullCapture;
    let mut atrace_categories: Vec<String> = if full_capture && !probes.contains(&Probe::Atrace) {
        Vec::new()
    } else if let Some(selected) = &config.atrace_category_selection {
        selected.iter().cloned().collect()
    } else if probes.contains(&Probe::Atrace) {
        atrace_categories(config.template)
            .iter()
            .map(|category| (*category).to_owned())
            .collect()
    } else {
        Vec::new()
    };
    if !full_capture || probes.contains(&Probe::Atrace) {
        atrace_categories.extend(extra_atrace_categories);
    }
    dedup_preserving_order(&mut atrace_categories);

    let mut atrace_apps: Vec<String> = Vec::new();
    if !full_capture || probes.contains(&Probe::Atrace) {
        if let Some(target) = config
            .target_package
            .as_deref()
            .filter(|target| !target.trim().is_empty())
        {
            atrace_apps.push(target.to_owned());
        } else if full_capture {
            // Perfetto's atrace wildcard enables annotations from all apps only.
            atrace_apps.push("*".to_owned());
        }
    }
    if !full_capture
        && probes.contains(&Probe::LowMemoryKiller)
        && !atrace_apps.iter().any(|app| app == "lmkd")
    {
        atrace_apps.push("lmkd".to_owned());
    }

    let mut data_sources: Vec<DataSource> = Vec::new();
    if !ftrace_events.is_empty() || !atrace_categories.is_empty() || !atrace_apps.is_empty() {
        data_sources.push(ftrace_data_source(
            &ftrace_events,
            &atrace_categories,
            &atrace_apps,
            probes.contains(&Probe::AdvancedFtrace),
        ));
    }
    if probes.iter().any(|probe| SYS_STATS_PROBES.contains(probe)) {
        data_sources.push(system_stats_data_source(&probes));
    }
    if probes
        .iter()
        .any(|probe| PROCESS_STATS_PROBES.contains(probe))
    {
        data_sources.push(process_stats_data_source(
            probes.contains(&Probe::ProcessStats),
        ));
    }
    let target = config
        .target_package
        .as_deref()
        .filter(|target| !full_capture || is_target_package(target));
    push_direct_data_sources(&mut data_sources, &probes, target);
    data_sources.push(DataSource {
        name: "android.packages_list",
        config: String::new(),
    });

    compose_config(
        config.duration_seconds.saturating_mul(1_000),
        config.buffer_size_kb,
        &data_sources,
        Some(5_000),
    )
}

/// PerfettoConfigComposer.compose.
fn compose_config(
    duration_ms: u32,
    buffer_size_kb: u32,
    data_sources: &[DataSource],
    flush_period_ms: Option<u32>,
) -> String {
    let mut text = String::new();
    text.push_str("buffers: {\n");
    text.push_str(&format!("  size_kb: {buffer_size_kb}\n"));
    text.push_str("  fill_policy: RING_BUFFER\n");
    text.push_str("}\n");
    text.push_str(&format!("duration_ms: {duration_ms}\n"));
    if let Some(flush) = flush_period_ms {
        text.push_str(&format!("flush_period_ms: {flush}\n"));
    }
    for source in data_sources {
        text.push_str("data_sources: {\n  config {\n");
        text.push_str(&format!("    name: \"{}\"\n", source.name));
        for line in source.config.lines() {
            text.push_str("    ");
            text.push_str(line);
            text.push('\n');
        }
        text.push_str("  }\n}\n");
    }
    text
}

fn push_direct_data_sources(
    data_sources: &mut Vec<DataSource>,
    probes: &BTreeSet<Probe>,
    target_package: Option<&str>,
) {
    let target = target_package.filter(|target| !target.trim().is_empty());
    if let Some(target) = target {
        if probes.contains(&Probe::NativeHeap) {
            data_sources.push(heap_profile_data_source(target));
        }
        if probes.contains(&Probe::JavaHeapDump) {
            data_sources.push(java_heap_dump_data_source(target));
        }
    }
    fn add_if(
        data_sources: &mut Vec<DataSource>,
        probes: &BTreeSet<Probe>,
        probe: Probe,
        name: &'static str,
        config: String,
    ) {
        if probes.contains(&probe) {
            data_sources.push(DataSource { name, config });
        }
    }
    add_if(
        data_sources,
        probes,
        Probe::PowerRails,
        "android.power",
        "android_power_config {\n  battery_poll_ms: 1000\n  collect_power_rails: true\n  battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT\n  battery_counters: BATTERY_COUNTER_CHARGE\n  battery_counters: BATTERY_COUNTER_CURRENT\n}".to_owned(),
    );
    add_if(
        data_sources,
        probes,
        Probe::GpuMemory,
        "android.gpu.memory",
        String::new(),
    );
    add_if(
        data_sources,
        probes,
        Probe::GpuRenderStages,
        "gpu.renderstages",
        String::new(),
    );
    add_if(
        data_sources,
        probes,
        Probe::AndroidLog,
        "android.log",
        String::new(),
    );
    add_if(
        data_sources,
        probes,
        Probe::FrameTimeline,
        "android.surfaceflinger.frametimeline",
        String::new(),
    );
    add_if(
        data_sources,
        probes,
        Probe::GameInterventions,
        "android.game_interventions",
        String::new(),
    );
    add_if(
        data_sources,
        probes,
        Probe::NetworkPackets,
        "android.network_packets",
        "network_packet_trace_config { poll_ms: 1000 }".to_owned(),
    );
    if probes.contains(&Probe::CallstackSampling) {
        data_sources.push(perf_sampling_data_source(target_package));
    }
    add_if(
        data_sources,
        probes,
        Probe::TrackEvents,
        "track_event",
        String::new(),
    );
}

fn ftrace_data_source(
    events: &[String],
    atrace_categories: &[String],
    atrace_apps: &[String],
    advanced: bool,
) -> DataSource {
    let mut config = String::from("ftrace_config {\n");
    for event in events {
        config.push_str(&format!(
            "  ftrace_events: \"{}\"\n",
            escape_proto_string(event)
        ));
    }
    for category in atrace_categories {
        config.push_str(&format!(
            "  atrace_categories: \"{}\"\n",
            escape_proto_string(category)
        ));
    }
    for app in atrace_apps {
        config.push_str(&format!(
            "  atrace_apps: \"{}\"\n",
            escape_proto_string(app)
        ));
    }
    if advanced {
        config.push_str("  symbolize_ksyms: true\n  disable_generic_events: false\n");
    }
    config.push_str("}\n");
    DataSource {
        name: "linux.ftrace",
        config,
    }
}

fn process_stats_data_source(poll_memory: bool) -> DataSource {
    let mut config = String::from("process_stats_config {\n");
    config.push_str("  scan_all_processes_on_start: true\n");
    config.push_str("  record_thread_names: true\n");
    if poll_memory {
        config.push_str("  proc_stats_poll_ms: 1000\n");
    }
    config.push_str("}\n");
    DataSource {
        name: "linux.process_stats",
        config,
    }
}

fn system_stats_data_source(probes: &BTreeSet<Probe>) -> DataSource {
    let mut config = String::from("sys_stats_config {\n");
    if probes.contains(&Probe::CpuUsage) {
        config.push_str("  stat_period_ms: 1000\n");
    }
    if probes.contains(&Probe::CpuFrequency) {
        config.push_str("  cpufreq_period_ms: 250\n");
    }
    if probes.contains(&Probe::KernelMeminfo) {
        config.push_str("  meminfo_period_ms: 1000\n");
    }
    if probes.contains(&Probe::Vmstat) {
        config.push_str("  vmstat_period_ms: 1000\n");
    }
    config.push_str("}\n");
    DataSource {
        name: "linux.sys_stats",
        config,
    }
}

fn heap_profile_data_source(target_package: &str) -> DataSource {
    DataSource {
        name: "android.heapprofd",
        config: format!(
            "heapprofd_config {{\n  sampling_interval_bytes: 4096\n  process_cmdline: \"{}\"\n}}\n",
            escape_proto_string(target_package)
        ),
    }
}

fn java_heap_dump_data_source(target_package: &str) -> DataSource {
    DataSource {
        name: "android.java_hprof",
        config: format!(
            "java_hprof_config {{\n  process_cmdline: \"{}\"\n}}\n",
            escape_proto_string(target_package)
        ),
    }
}

fn perf_sampling_data_source(target_package: Option<&str>) -> DataSource {
    let mut config = String::from("perf_event_config {\n");
    config.push_str("  timebase { frequency: 100 timestamp_clock: PERF_CLOCK_MONOTONIC }\n");
    config.push_str("  callstack_sampling {\n");
    if let Some(target) = target_package.filter(|target| !target.trim().is_empty()) {
        config.push_str(&format!(
            "    scope {{ target_cmdline: \"{}\" }}\n",
            escape_proto_string(target)
        ));
    }
    config.push_str("  }\n}\n");
    DataSource {
        name: "linux.perf",
        config,
    }
}

/// PerfettoConfigTextBuilder.ftraceEvents.
fn ftrace_events(probe: Probe) -> &'static [&'static str] {
    use Probe::*;
    match probe {
        CpuScheduling => &[
            "sched/sched_switch",
            "power/suspend_resume",
            "sched/sched_blocked_reason",
            "sched/sched_wakeup",
            "sched/sched_wakeup_new",
            "sched/sched_waking",
            "sched/sched_process_exit",
            "sched/sched_process_free",
            "task/task_newtask",
            "task/task_rename",
        ],
        CpuFrequency => &[
            "power/cpu_frequency",
            "power/cpu_idle",
            "power/suspend_resume",
        ],
        Syscalls => &["raw_syscalls/sys_enter", "raw_syscalls/sys_exit"],
        FilesystemIo => &[
            "block/block_rq_issue",
            "block/block_rq_complete",
            "f2fs/f2fs_write_begin",
            "f2fs/f2fs_write_end",
            "ext4/ext4_sync_file_enter",
            "ext4/ext4_sync_file_exit",
        ],
        PageFaults => &[
            "exceptions/page_fault_user",
            "exceptions/page_fault_kernel",
            "filemap/mm_filemap_fault",
        ],
        ArtRuntime => &["ftrace/print"],
        HighFrequencyMemory => &[
            "mm_event/mm_event_record",
            "kmem/rss_stat",
            "ion/ion_stat",
            "dmabuf_heap/dma_heap_stat",
            "kmem/ion_heap_grow",
            "kmem/ion_heap_shrink",
        ],
        LowMemoryKiller => &["lowmemorykiller/lowmemory_kill", "oom/oom_score_adj_update"],
        Thermal => &[
            "thermal/thermal_temperature",
            "thermal/cdev_update",
            "thermal/thermal_power_cpu_limit",
            "power/cpu_frequency_limits",
        ],
        BoardVoltages => &[
            "regulator/regulator_set_voltage",
            "regulator/regulator_set_voltage_complete",
            "power/clock_enable",
            "power/clock_disable",
            "power/clock_set_rate",
            "power/suspend_resume",
        ],
        GpuFrequency => &["power/gpu_frequency"],
        GpuMemory => &["gpu_mem/gpu_mem_total"],
        GpuWorkPeriod => &["power/gpu_work_period"],
        MaliFenceEvents => &[
            "mali/mali_KCPU_FENCE_SIGNAL",
            "mali/mali_KCPU_FENCE_WAIT_END",
            "mali/mali_KCPU_FENCE_WAIT_START",
        ],
        Atrace => &["ftrace/print"],
        WifiNetwork => &[
            "cfg80211/*",
            "mac80211/*",
            "net/netif_receive_skb",
            "net/net_dev_xmit",
        ],
        ProcessThreadAssociation => &[
            "sched/sched_process_exit",
            "sched/sched_process_free",
            "task/task_newtask",
            "task/task_rename",
        ],
        _ => &[],
    }
}

/// PerfettoConfigTextBuilder.atraceCategories.
fn atrace_categories(template: CaptureTemplate) -> &'static [&'static str] {
    match template {
        CaptureTemplate::SystemOverview => &[
            "am",
            "binder_driver",
            "dalvik",
            "database",
            "gfx",
            "view",
            "wm",
            "power",
        ],
        CaptureTemplate::AppPerformance => &[
            "am",
            "binder_driver",
            "dalvik",
            "database",
            "gfx",
            "view",
            "wm",
        ],
        CaptureTemplate::GfxPipeline => &["gfx", "view", "wm"],
        CaptureTemplate::InputLatency => &["input", "view", "wm"],
        CaptureTemplate::MemoryProfile => &["am", "dalvik"],
        CaptureTemplate::FullCapture | CaptureTemplate::Custom => &[],
    }
}

fn is_target_package(target: &str) -> bool {
    target.split('.').all(|part| {
        part.as_bytes()
            .first()
            .is_some_and(|byte| byte.is_ascii_alphabetic() || *byte == b'_')
            && part
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
    })
}

fn custom_category_selection(live_editor: Option<&str>, saved_draft: &str) -> BTreeSet<String> {
    super::capture_config::preflight(live_editor.unwrap_or(saved_draft), None, None)
        .atrace_categories
        .into_iter()
        .collect()
}

fn escape_proto_string(value: &str) -> String {
    value.replace('\\', "\\\\").replace('"', "\\\"")
}

fn dedup_preserving_order(values: &mut Vec<String>) {
    let mut seen = BTreeSet::new();
    values.retain(|value| seen.insert(value.clone()));
}

/// automaticCompletionDelayMillis: custom configs follow their own duration.
fn automatic_completion_delay(config: &CaptureConfig) -> Option<Duration> {
    if config.template == CaptureTemplate::Custom {
        let text = config.custom_config_text.as_deref()?;
        super::capture_config::preflight(text, None, None)
            .duration_ms
            .map(Duration::from_millis)
    } else {
        Some(Duration::from_secs(u64::from(config.duration_seconds)))
    }
}

fn parse_perfetto_data_source_names(output: &str) -> BTreeSet<String> {
    let Some(section) = output.split("DATA SOURCES REGISTERED:").nth(1) else {
        return BTreeSet::new();
    };
    let section = section.split("TRACING SESSIONS:").next().unwrap_or(section);
    section
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with("NAME") && !line.starts_with("==="))
        .filter_map(|line| line.split_whitespace().next())
        .map(str::to_owned)
        .collect()
}

fn parse_atrace_categories(output: &str) -> BTreeMap<String, String> {
    output
        .lines()
        .filter_map(|line| {
            let (name, description) = line.trim().split_once(" - ")?;
            let name = name.trim();
            if name.is_empty()
                || name.len() > 128
                || !name
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || b"_.-".contains(&byte))
            {
                return None;
            }
            Some((name.to_owned(), description.chars().take(256).collect()))
        })
        .take(512)
        .collect()
}

/// First all-digit line of the perfetto --background-wait output.
fn parse_perfetto_pid(output: &str) -> Option<u32> {
    for line in output.lines() {
        let trimmed = line.trim();
        if !trimmed.is_empty()
            && trimmed.len() <= 10
            && trimmed.bytes().all(|byte| byte.is_ascii_digit())
            && let Ok(pid) = trimmed.parse::<u32>()
            && pid > 0
        {
            return Some(pid);
        }
    }
    None
}

fn parse_shell_values(output: &[u8]) -> Vec<String> {
    String::from_utf8_lossy(output)
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(str::to_owned)
        .collect()
}

fn parse_build_properties(output: &[u8]) -> (u32, String) {
    let values = parse_shell_values(output);
    (
        values
            .first()
            .and_then(|value| value.parse().ok())
            .unwrap_or(0),
        values
            .get(1)
            .cloned()
            .unwrap_or_else(|| "unknown".to_owned()),
    )
}

fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_millis() as u64)
}

fn unique_id(prefix: &str) -> String {
    let sequence = CAPTURE_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    format!(
        "{prefix}-{:x}-{:x}-{sequence:x}",
        unix_ms(),
        std::process::id()
    )
}

/// UTC timestamp for recording_since; no locale package service is added.
pub(in crate::trace) fn format_unix_ms(millis: u64) -> String {
    let seconds = millis / 1_000;
    let days = (seconds / 86_400) as i64;
    let seconds_of_day = seconds % 86_400;
    let (year, month, day) = civil_from_days(days);
    let hour = seconds_of_day / 3_600;
    let minute = (seconds_of_day % 3_600) / 60;
    let second = seconds_of_day % 60;
    format!("{year:04}-{month:02}-{day:02} {hour:02}:{minute:02}:{second:02} UTC")
}

/// Howard Hinnant's civil-from-days conversion.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    (
        if month <= 2 { year + 1 } else { year },
        month as u32,
        day as u32,
    )
}

/// The shared title-bar target may fill the package field only while it is
/// untouched; an operator-entered value always wins.
fn should_apply_shared_target(current: &str, applied: Option<&str>) -> bool {
    current.trim().is_empty() || applied == Some(current)
}

fn preferred_device_serial(current: Option<&str>, devices: &[CaptureDevice]) -> Option<String> {
    if let Some(current) = current
        && devices
            .iter()
            .any(|device| device.serial == current && device.online)
    {
        return Some(current.to_owned());
    }
    let online: Vec<&CaptureDevice> = devices.iter().filter(|device| device.online).collect();
    if online.len() == 1 {
        Some(online[0].serial.clone())
    } else {
        None
    }
}

fn clamp_parse_u32(value: &str, fallback: u32, minimum: u32, maximum: u32) -> u32 {
    value
        .trim()
        .parse::<u32>()
        .map(|value| value.clamp(minimum, maximum))
        .unwrap_or(fallback)
}

// ---------------------------------------------------------------------------
// Blocking ADB transport: fixed argument vectors, bounded output.
// ---------------------------------------------------------------------------

struct AdbRunner {
    executable: PathBuf,
    serial: String,
    cancel: CancellationToken,
    remote: RemoteCapturePaths,
}

#[derive(Clone, Debug)]
struct RemoteCapturePaths {
    config: String,
    trace: String,
    pid: String,
    log: String,
    exit_status: String,
}

impl RemoteCapturePaths {
    fn new() -> Self {
        let id = unique_id("aps");
        Self {
            config: format!("/data/local/tmp/{id}.pbtxt"),
            trace: format!("/data/misc/perfetto-traces/{id}.pftrace"),
            pid: format!("/data/local/tmp/{id}.pid"),
            log: format!("/data/local/tmp/{id}.log"),
            exit_status: format!("/data/local/tmp/{id}.exit"),
        }
    }

    fn start_command(&self, template: CaptureTemplate) -> String {
        if template.uses_supervised_launch() {
            // Perfetto's own daemonization discards stderr and its final status.
            // A shell supervisor retains both without an all-sources barrier.
            // $! identifies the last pipeline process: the actual Perfetto PID.
            // Pipe diagnostics to a shell-owned writer: SELinux denies direct
            // Perfetto writes to shell_data_file, even through inherited FDs.
            return format!(
                r#"umask 077
nohup sh -c '
trap "" HUP
(
cat {config} | sh -c "
attempts=0
while [ ! -f {pid}.accepted ] && [ ! -f {pid}.abort ] && [ \"\$attempts\" -lt 100 ]; do
    sleep 0.05
    attempts=\$((attempts + 1))
done
[ -f {pid}.accepted ] && [ ! -f {pid}.abort ] || exit 125
exec perfetto --txt -c - -o {trace} --no-clobber
" &
pid=$!
if ! printf "%s\n" "$pid" >{pid}; then : >{pid}.abort; fi
wait "$pid"
result=$?
printf "%s\n" "$result" >{exit_status}.tmp
) 2>&1 | tail -c 8192 >{log}
logger_status=$?
if [ "$logger_status" -eq 0 ]; then
    mv {exit_status}.tmp {exit_status}
else
    printf "logger_failed:%s\n" "$logger_status" >{exit_status}
fi
' </dev/null >>{log} 2>&1 &
attempts=0
while [ "$attempts" -lt 50 ]; do
    if [ -f {pid} ] && [ -s {pid} ]; then break; fi
    sleep 0.05
    attempts=$((attempts + 1))
done
if [ -f {pid} ] && [ -s {pid} ]; then
    : >{pid}.accepted && cat {pid}
else
    : >{pid}.abort
    exit 1
fi"#,
                config = self.config,
                trace = self.trace,
                log = self.log,
                pid = self.pid,
                exit_status = self.exit_status,
            );
        }
        format!(
            "cat {} | perfetto --txt -c - -o {} --background-wait --no-clobber",
            self.config, self.trace,
        )
    }
}

/// One started capture; enough to stop, pull and describe the trace.
#[derive(Clone, Debug)]
struct ActiveCapture {
    executable: PathBuf,
    serial: String,
    pid: u32,
    config: CaptureConfig,
    started_unix_ms: u64,
    device_model: String,
    android_sdk: u32,
    capability_query_failed: bool,
    automatic_delay: Option<Duration>,
    remote: RemoteCapturePaths,
    startup_unconfirmed: bool,
    stop_confirmed: Arc<AtomicBool>,
    stop_signal_sent: Arc<AtomicBool>,
    manual_stop_requested: Arc<AtomicBool>,
}

#[derive(Debug, PartialEq, Eq)]
struct CaptureStart {
    pid: u32,
    startup_unconfirmed: bool,
}

fn capture_start_result(
    output: &ProcessOutput,
    template: CaptureTemplate,
) -> Result<CaptureStart, String> {
    let pid = parse_perfetto_pid(&String::from_utf8_lossy(&output.stdout));
    if output.status.success() {
        return pid
            .map(|pid| CaptureStart {
                pid,
                startup_unconfirmed: template.uses_supervised_launch(),
            })
            .ok_or_else(|| {
                "Perfetto started without returning a background process id".to_owned()
            });
    }
    let error = perfetto_start_error(output);
    Err(match pid {
        Some(pid) => {
            format!("{error}. Startup returned background PID {pid}; readiness was not accepted")
        }
        None => error,
    })
}

impl AdbRunner {
    fn new(executable: PathBuf, serial: String, cancel: CancellationToken) -> Self {
        Self {
            executable,
            serial,
            cancel,
            remote: RemoteCapturePaths::new(),
        }
    }

    fn location(serial: String, cancel: CancellationToken) -> Result<Self, String> {
        let location = AdbLocator::new()
            .locate(None)
            .map_err(|error| error.to_string())?;
        Ok(Self::new(location.executable, serial, cancel))
    }

    fn base_arguments(&self) -> Vec<OsString> {
        vec!["-s".into(), self.serial.clone().into()]
    }

    fn with_arguments(&self, arguments: &[&str]) -> Vec<OsString> {
        let mut all = self.base_arguments();
        all.extend(arguments.iter().map(OsString::from));
        all
    }

    fn run(
        &self,
        arguments: Vec<OsString>,
        timeout: Duration,
        stdout_limit: usize,
    ) -> Result<ProcessOutput, String> {
        validate_serial(&self.serial).map_err(|error| error.to_string())?;
        let mut request = ProcessRequest::new(&self.executable);
        request.arguments = arguments;
        request.timeout = timeout;
        request.stdout_limit = stdout_limit.min(MAX_OUTPUT_LIMIT);
        request.stderr_limit = SHELL_OUTPUT_LIMIT;
        ProcessRunner
            .run(&request, &self.cancel)
            .map_err(sanitized_process_error)
    }

    fn shell(&self, command: &str, timeout: Duration) -> Result<ProcessOutput, String> {
        self.run(self.shell_arguments(command), timeout, SHELL_OUTPUT_LIMIT)
    }

    /// adb joins everything after `shell` into one device-shell command line, so
    /// `sh -c` with a separate command argument would run only its first token.
    fn shell_arguments(&self, command: &str) -> Vec<OsString> {
        vec![
            "-s".into(),
            self.serial.clone().into(),
            "shell".into(),
            command.into(),
        ]
    }

    fn push_config(&self, local: &Path) -> Result<(), String> {
        let mut arguments = self.base_arguments();
        arguments.push("push".into());
        arguments.push(local.as_os_str().to_owned());
        arguments.push(self.remote.config.clone().into());
        let output = self.run(arguments, ADB_DEVICE_PUSH_TIMEOUT, SHELL_OUTPUT_LIMIT)?;
        if !output.status.success() {
            return Err("Unable to push the capture config to the device".to_owned());
        }
        Ok(())
    }

    fn start_capture(&self, template: CaptureTemplate) -> Result<CaptureStart, String> {
        let result = self
            .shell(&self.remote.start_command(template), ADB_START_TIMEOUT)
            .and_then(|output| capture_start_result(&output, template));
        // Retain an accepted launch's PID before any further device probes.
        // Broad-capture source readiness is not inferred from a launch result.
        result.map_err(|error| {
            if template.uses_supervised_launch() {
                self.capture_failure(&error, template)
            } else {
                error
            }
        })
    }

    fn device_info(&self) -> (String, u32) {
        match self.shell(DEVICE_INFO_COMMAND, ADB_SHORT_TIMEOUT) {
            Ok(output) if output.status.success() => {
                let values = parse_shell_values(&output.stdout);
                (
                    values
                        .first()
                        .cloned()
                        .filter(|model| !model.is_empty())
                        .unwrap_or_else(|| "unknown".to_owned()),
                    values
                        .get(1)
                        .and_then(|value| value.parse().ok())
                        .unwrap_or(0),
                )
            }
            _ => ("unknown".to_owned(), 0),
        }
    }

    fn kill(&self, signal: &str, pid: u32) -> Result<(), String> {
        let command = format!("if [ ! -d /proc/{pid} ]; then exit 0; fi; kill {signal} {pid}");
        let output = self.shell(&command, ADB_SHORT_TIMEOUT)?;
        if output.status.success() {
            Ok(())
        } else {
            Err(format!(
                "Unable to deliver {signal} to the owned Perfetto process {pid}"
            ))
        }
    }

    fn remote_process_stopped(&self, pid: u32) -> Result<bool, String> {
        let command = format!(
            "if [ -d /proc/{pid} ]; then echo running; elif [ -d /proc/self ]; then echo stopped; else exit 1; fi"
        );
        let output = self
            .shell(&command, ADB_SHORT_TIMEOUT)
            .map_err(|error| format!("Unable to confirm Perfetto stopped: {error}"))?;
        confirmed_remote_stop(&output)
    }

    /// Only explicit absence confirms stop. ADB errors never authorize transfer.
    fn wait_for_remote_stop(&self, pid: u32) -> Result<bool, String> {
        for _ in 0..REMOTE_STOP_POLL_ATTEMPTS {
            if self.cancel.is_cancelled() {
                return Err("Capture cancelled".to_owned());
            }
            match self.remote_process_stopped(pid)? {
                true => return Ok(true),
                false => {}
            }
            std::thread::sleep(REMOTE_STOP_POLL_DELAY);
        }
        Ok(false)
    }

    fn wait_for_graceful_stop(&self, pid: u32, timeout: Duration) -> Result<bool, String> {
        let started = std::time::Instant::now();
        loop {
            if self.cancel.is_cancelled() {
                return Err("Capture cancelled".to_owned());
            }
            if self.remote_process_stopped(pid)? {
                return Ok(true);
            }
            let remaining = timeout.saturating_sub(started.elapsed());
            if remaining.is_zero() {
                return Ok(false);
            }
            std::thread::sleep(REMOTE_STOP_POLL_DELAY.min(remaining));
        }
    }

    fn transfer_trace(&self, destination: &Path) -> Result<u64, String> {
        let expected = self.remote_trace_size()?;
        let mut arguments = self.base_arguments();
        arguments.push("pull".into());
        arguments.push(self.remote.trace.clone().into());
        arguments.push(destination.as_os_str().to_owned());
        // Only pull diagnostics count against ProcessOutput's allocation limit;
        // the trace itself is streamed to disk by ADB.
        receive_trace_file(destination, expected, &self.cancel, || {
            let output = self
                .run(arguments, ADB_TRANSFER_TIMEOUT, SHELL_OUTPUT_LIMIT)
                .map_err(|error| format!("Trace file transfer failed: {error}"))?;
            if !output.status.success() {
                return Err("Unable to pull the captured trace from the device".to_owned());
            }
            let after = self.remote_trace_size()?;
            if after != expected {
                let local = std::fs::symlink_metadata(destination)
                    .map(|metadata| metadata.len().to_string())
                    .unwrap_or_else(|_| "unavailable".to_owned());
                return Err(format!(
                    "Device trace changed during transfer: before={expected} bytes, after={after} bytes, local={local} bytes; no capture was accepted"
                ));
            }
            Ok(())
        })
    }

    fn remote_trace_size(&self) -> Result<u64, String> {
        let remote = self.run(
            self.with_arguments(&["shell", "stat", "-c", "%s", &self.remote.trace]),
            ADB_SHORT_TIMEOUT,
            SHELL_OUTPUT_LIMIT,
        )?;
        if !remote.status.success() {
            return Err("Unable to inspect the captured trace on the device".to_owned());
        }
        parse_trace_transfer_size(&remote.stdout)
    }

    fn confirm_supervised_exit(&self) -> Result<(), String> {
        let command = format!(
            "if [ -f {} ]; then cat {}; else echo pending; fi",
            self.remote.exit_status, self.remote.exit_status,
        );
        // /proc disappears just before the supervisor's wait returns. Allow
        // that short handoff, but never infer success from a missing status.
        for _ in 0..REMOTE_STOP_POLL_ATTEMPTS {
            let output = self.shell(&command, ADB_SHORT_TIMEOUT)?;
            if !output.status.success() {
                return Err("Unable to read the Perfetto exit status".to_owned());
            }
            match parse_custom_exit_status(&output.stdout)? {
                Some(0) => return Ok(()),
                Some(code) => {
                    return Err(format!("Perfetto capture failed (exit code {code})"));
                }
                None => std::thread::sleep(REMOTE_STOP_POLL_DELAY),
            }
        }
        Err("The Perfetto process exited without a confirmed exit status".to_owned())
    }

    fn capture_failure(&self, error: &str, template: CaptureTemplate) -> String {
        let mut message = error.to_owned();
        if template.uses_supervised_launch() {
            let command = format!("tail -c 8192 {}", self.remote.log);
            match self.shell(&command, ADB_SHORT_TIMEOUT) {
                Ok(output) if output.status.success() => {
                    let detail = diagnostic_text(&output.stdout, 2048);
                    if !detail.is_empty() {
                        message.push_str(&format!(". Perfetto diagnostics: {detail}"));
                    } else {
                        message.push_str(". Perfetto produced no diagnostic output");
                    }
                }
                _ => message.push_str(". Perfetto diagnostics could not be read"),
            }
            message.push_str(&format!(
                ". Retained device config {}, PID {}, log {}, exit status {}",
                self.remote.config, self.remote.pid, self.remote.log, self.remote.exit_status,
            ));
        }
        message.push_str(&format!(
            ". Device cleanup was skipped; the recovery path is {} on device {}",
            self.remote.trace, self.serial,
        ));
        message
    }

    fn cleanup_device_files(&self) {
        let abort_path = format!("{}.abort", self.remote.pid);
        let accepted_path = format!("{}.accepted", self.remote.pid);
        let pending_exit_path = format!("{}.tmp", self.remote.exit_status);
        let _ = self.run(
            self.with_arguments(&[
                "shell",
                "rm",
                "-f",
                &self.remote.config,
                &self.remote.trace,
                &self.remote.pid,
                &self.remote.log,
                &self.remote.exit_status,
                &abort_path,
                &accepted_path,
                &pending_exit_path,
            ]),
            ADB_SHORT_TIMEOUT,
            SHELL_OUTPUT_LIMIT,
        );
    }
}

fn parse_custom_exit_status(output: &[u8]) -> Result<Option<u8>, String> {
    let text = std::str::from_utf8(output)
        .map_err(|_| "The Perfetto exit status is invalid".to_owned())?
        .trim();
    if text == "pending" || text.is_empty() {
        return Ok(None);
    }
    if let Some(code) = text.strip_prefix("logger_failed:")
        && code.parse::<u8>().is_ok_and(|code| code != 0)
    {
        return Err(format!(
            "The Perfetto diagnostic logger failed (exit code {code}); the client exit status is retained in the .exit.tmp artifact"
        ));
    }
    if text.bytes().all(|byte| byte.is_ascii_digit())
        && let Ok(code) = text.parse()
    {
        return Ok(Some(code));
    }
    Err("The Perfetto exit status is invalid".to_owned())
}

fn diagnostic_text(output: &[u8], limit: usize) -> String {
    let text = String::from_utf8_lossy(output)
        .lines()
        .map(|line| {
            line.chars()
                .filter(|character| !character.is_control())
                .collect::<String>()
        })
        .filter(|line| !line.trim().is_empty())
        .collect::<Vec<_>>()
        .join(" | ");
    let skip = text.chars().count().saturating_sub(limit);
    if skip > 0 {
        format!(
            "[truncated] {}",
            text.chars().skip(skip).collect::<String>()
        )
    } else {
        text
    }
}

fn confirmed_remote_stop(output: &ProcessOutput) -> Result<bool, String> {
    if output.status.success() {
        match output.stdout.as_slice() {
            b"stopped\n" | b"stopped\r\n" => return Ok(true),
            b"running\n" | b"running\r\n" => return Ok(false),
            _ => {}
        }
    }
    let stdout = String::from_utf8_lossy(&output.stdout)
        .chars()
        .take(160)
        .collect::<String>();
    let stderr = String::from_utf8_lossy(&output.stderr)
        .chars()
        .take(160)
        .collect::<String>();
    Err(format!(
        "Unable to confirm Perfetto stopped: exit={:?}, state={stdout:?}, diagnostic={stderr:?}",
        output.status.code(),
    ))
}

fn parse_trace_transfer_size(output: &[u8]) -> Result<u64, String> {
    let text = std::str::from_utf8(output)
        .map_err(|_| "The device returned an invalid trace file size".to_owned())?
        .trim();
    let size = text
        .bytes()
        .all(|byte| byte.is_ascii_digit())
        .then(|| text.parse::<u64>().ok())
        .flatten()
        .ok_or_else(|| "The device returned an invalid trace file size".to_owned())?;
    validate_trace_transfer_size(size)?;
    Ok(size)
}

fn validate_trace_transfer_size(size: u64) -> Result<(), String> {
    if size == 0 {
        Err("The captured trace is empty".to_owned())
    } else if size > aps_perfetto::MAX_TRACE_BYTES {
        Err("The captured trace exceeds the supported 8 GiB trace file limit".to_owned())
    } else {
        Ok(())
    }
}

fn receive_trace_file(
    destination: &Path,
    expected: u64,
    cancel: &CancellationToken,
    pull: impl FnOnce() -> Result<(), String>,
) -> Result<u64, String> {
    validate_trace_transfer_size(expected)?;
    if cancel.is_cancelled() {
        return Err("Capture cancelled".to_owned());
    }
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    // Reserve a new private destination, but do not hold its handle across ADB
    // pull: ADB may replace the placeholder inode, including on Windows.
    drop(options.open(destination).map_err(|_| {
        "Cannot reserve a new local trace file; check disk space and directory permissions"
            .to_owned()
    })?);
    let result = (|| {
        pull()?;
        if cancel.is_cancelled() {
            return Err("Capture cancelled".to_owned());
        }
        let metadata = std::fs::symlink_metadata(destination)
            .map_err(|_| "Unable to inspect the pulled trace file".to_owned())?;
        if metadata.is_symlink() || !metadata.is_file() || metadata.len() != expected {
            return Err(format!(
                "The trace transfer is incomplete or the local file changed: expected={expected} bytes, actual={} bytes, regular={}, symlink={}",
                metadata.len(),
                metadata.is_file(),
                metadata.is_symlink(),
            ));
        }
        Ok(metadata.len())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(destination);
    }
    result
}

fn perfetto_start_error(output: &ProcessOutput) -> String {
    let mut diagnostics = String::from_utf8_lossy(&output.stderr)
        .lines()
        .chain(String::from_utf8_lossy(&output.stdout).lines())
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .filter(|line| !line.chars().all(|character| character.is_ascii_digit()))
        .map(|line| {
            line.chars()
                .filter(|character| !character.is_control())
                .collect::<String>()
        })
        .collect::<Vec<_>>();
    diagnostics.dedup();
    let detail = diagnostics.join(" | ");
    let detail = if detail.is_empty() {
        "The device returned no diagnostic output".to_owned()
    } else {
        detail.chars().take(512).collect()
    };
    match output.status.code() {
        Some(code) => format!("Perfetto could not start the capture (exit code {code}): {detail}"),
        None => format!("Perfetto could not start the capture: {detail}"),
    }
}

fn sanitized_process_error(error: ProcessError) -> String {
    match error {
        ProcessError::Cancelled => "Capture cancelled".to_owned(),
        ProcessError::Timeout => "The ADB command timed out".to_owned(),
        ProcessError::OutputLimit(_) => {
            "ADB command diagnostic output exceeded its limit".to_owned()
        }
        ProcessError::OutputNotClosed => {
            "The ADB command exited but did not close its output".to_owned()
        }
        _ => "The ADB command could not be executed".to_owned(),
    }
}

fn capture_start_blocking(
    serial: String,
    config: CaptureConfig,
    data_dir: PathBuf,
    cancel: CancellationToken,
    capability_query_failed: bool,
) -> Result<ActiveCapture, String> {
    if let Err(error) = config.validate() {
        return Err(error);
    }
    let adb = AdbRunner::location(serial.clone(), cancel.clone())?;
    let config_text = build_config_text(&config);
    let captures_dir = data_dir.join(CAPTURE_CONFIG_PATH_SEGMENT);
    std::fs::create_dir_all(&captures_dir)
        .map_err(|_| "The capture artifact directory is unavailable".to_owned())?;
    let host_config = captures_dir.join(format!("{}.pbtxt", unique_id("config")));
    std::fs::write(&host_config, config_text)
        .map_err(|_| "Unable to stage the capture config locally".to_owned())?;
    let result: Result<ActiveCapture, String> = (|| {
        adb.push_config(&host_config)?;
        let started_unix_ms = unix_ms();
        let start_requested = std::time::Instant::now();
        let started = adb.start_capture(config.template)?;
        let automatic_delay = automatic_completion_delay(&config).map(|delay| {
            if started.startup_unconfirmed {
                delay.saturating_sub(start_requested.elapsed())
            } else {
                delay
            }
        });
        let (device_model, android_sdk) = adb.device_info();
        Ok(ActiveCapture {
            executable: adb.executable.clone(),
            serial,
            pid: started.pid,
            config: config.clone(),
            started_unix_ms,
            device_model,
            android_sdk,
            capability_query_failed,
            automatic_delay,
            remote: adb.remote.clone(),
            startup_unconfirmed: started.startup_unconfirmed,
            stop_confirmed: Arc::new(AtomicBool::new(false)),
            stop_signal_sent: Arc::new(AtomicBool::new(false)),
            manual_stop_requested: Arc::new(AtomicBool::new(false)),
        })
    })();
    match result {
        Ok(active) => {
            let _ = std::fs::remove_file(&host_config);
            Ok(active)
        }
        Err(error) => Err(format!(
            "{error}. Startup artifacts were retained: device trace {}, device config {} on {}; local config {}",
            adb.remote.trace,
            adb.remote.config,
            adb.serial,
            host_config.display(),
        )),
    }
}

/// Send at most one graceful stop for this owned handle, including retries.
/// Perfetto resets its SIGTERM handler after the first signal (SA_RESETHAND);
/// sending it again can kill the client during final flush/readback.
fn confirm_capture_stopped(
    adb: &AdbRunner,
    active: &ActiveCapture,
    request_remote_stop: bool,
    timeout: Duration,
) -> Result<(), String> {
    if request_remote_stop {
        active.manual_stop_requested.store(true, Ordering::Release);
    }
    if active.stop_confirmed.load(Ordering::Acquire) {
        return Ok(());
    }
    if !request_remote_stop {
        if adb.wait_for_remote_stop(active.pid)? {
            active.stop_confirmed.store(true, Ordering::Release);
            return Ok(());
        }
        if active.config.template.uses_supervised_launch() {
            return Err(
                "Capture is still running; automatic completion did not send a stop signal"
                    .to_owned(),
            );
        }
    }
    // Mark before invoking ADB. On transport failure delivery is unknown, so
    // retries must observe this same session rather than risk a second signal.
    if !active.stop_signal_sent.swap(true, Ordering::AcqRel) {
        adb.kill("-TERM", active.pid)?;
    }
    if !adb.wait_for_graceful_stop(active.pid, timeout)? {
        return Err(format!(
            "Perfetto is still finalizing after {} seconds; no repeated stop signal or force kill was sent. The owned session remains tracked",
            timeout.as_secs()
        ));
    }
    active.stop_confirmed.store(true, Ordering::Release);
    Ok(())
}

fn complete_capture_blocking(
    active: ActiveCapture,
    request_remote_stop: bool,
    data_dir: PathBuf,
    cancel: CancellationToken,
    language: Language,
) -> Result<CaptureOutput, String> {
    let mut adb = AdbRunner::new(active.executable.clone(), active.serial.clone(), cancel);
    adb.remote = active.remote.clone();
    let stopped = confirm_capture_stopped(
        &adb,
        &active,
        request_remote_stop,
        REMOTE_GRACEFUL_STOP_TIMEOUT,
    );
    stopped.map_err(|error: String| adb.capture_failure(&error, active.config.template))?;
    if active.config.template.uses_supervised_launch() {
        adb.confirm_supervised_exit()
            .map_err(|error| adb.capture_failure(&error, active.config.template))?;
    }

    let captures_dir = data_dir.join(CAPTURE_CONFIG_PATH_SEGMENT);
    std::fs::create_dir_all(&captures_dir).map_err(|_| {
        adb.capture_failure(
            "The capture artifact directory is unavailable",
            active.config.template,
        )
    })?;
    let artifact_id = unique_id("artifact");
    let trace_path = captures_dir.join(format!("{artifact_id}.pftrace"));
    let trace_size_bytes = adb
        .transfer_trace(&trace_path)
        .map_err(|error| adb.capture_failure(&error, active.config.template))?;
    adb.cleanup_device_files();
    let finished_unix_ms = unix_ms();
    let duration_nanos = finished_unix_ms
        .saturating_sub(active.started_unix_ms)
        .saturating_mul(1_000_000);

    let manually_stopped = active.manual_stop_requested.load(Ordering::Acquire);
    let inspection =
        super::capture_config::preflight(&build_config_text(&active.config), None, None);
    let mut report = super::capture_report::inspect(
        &trace_path,
        &inspection.data_sources,
        if manually_stopped {
            None
        } else {
            inspection.duration_ms
        },
        &adb.cancel,
    );
    mark_startup_readiness(&mut report, active.startup_unconfirmed);
    if manually_stopped {
        report.messages.push(
            "Capture was stopped manually; configured duration was not a completeness requirement"
                .to_owned(),
        );
    }
    match super::capture_report::write_report(&trace_path, &report) {
        Ok(path) => report.report_path = Some(path),
        Err(error) => {
            report.status = super::capture_report::IntegrityStatus::Unverified;
            report
                .messages
                .push(format!("Unable to persist integrity report: {error}"));
        }
    }
    let status_key = match report.status {
        super::capture_report::IntegrityStatus::Verified => TextKey::TraceCaptureIntegrityPassed,
        super::capture_report::IntegrityStatus::Issues => TextKey::TraceCaptureIntegrityIssues,
        super::capture_report::IntegrityStatus::Unverified => {
            TextKey::TraceCaptureIntegrityUnverified
        }
    };
    let integrity = CaptureIntegritySummary {
        status: language.text(status_key).to_owned(),
        messages: report.messages.clone(),
        report_path: report.report_path.clone(),
    };

    let mut warnings = Vec::new();
    let mut limitations = Vec::new();
    limitations.extend(report.messages.iter().take(24).cloned());
    if let Some(path) = &report.report_path {
        limitations.push(format!("Integrity report: {}", path.display()));
    }
    if active.device_model.trim().is_empty() || active.device_model == "unknown" {
        warnings.push("The target did not report a device model".to_owned());
    }
    if active.android_sdk == 0 {
        warnings.push("The target did not report an Android SDK level".to_owned());
    }
    if active.capability_query_failed {
        limitations.push(
            "Perfetto data-source availability was not verified; the trace uses the requested config"
                .to_owned(),
        );
    }
    if trace_size_bytes == 0 {
        limitations.push("The captured trace is empty".to_owned());
    }

    let display_name = if active.config.template == CaptureTemplate::Custom {
        language.text(active.config.template.label()).to_owned()
    } else {
        active
            .config
            .target_package
            .clone()
            .filter(|target| !target.trim().is_empty())
            .unwrap_or_else(|| language.text(active.config.template.label()).to_owned())
    };
    let mut summary = if active.config.template == CaptureTemplate::Custom {
        format!(
            "{} · {} ms · {} KiB · {} sources",
            language.text(active.config.template.label()),
            inspection
                .duration_ms
                .map(|duration| duration.to_string())
                .unwrap_or_else(|| "manual".to_owned()),
            inspection.total_buffer_kb,
            inspection.data_sources.len()
        )
    } else {
        format!(
            "{} · {} s · {} MB",
            language.text(active.config.template.label()),
            active.config.duration_seconds,
            active.config.buffer_size_kb / 1_024
        )
    };
    if let Some(target) = active.config.target_package.as_deref().filter(|target| {
        !target.trim().is_empty() && active.config.template != CaptureTemplate::Custom
    }) {
        summary.push_str(" · ");
        summary.push_str(target);
    }
    Ok(CaptureOutput {
        session_id: unique_id("session"),
        artifact_id,
        display_name,
        trace_path,
        duration_nanos,
        captured_unix_ms: active.started_unix_ms,
        device_model: active.device_model,
        android_sdk: active.android_sdk,
        trace_size_bytes,
        completeness_label: integrity.status.clone(),
        limitations,
        warnings,
        config_summary: Some(summary),
        integrity,
    })
}

fn mark_startup_readiness(report: &mut super::capture_report::IntegrityReport, unconfirmed: bool) {
    if unconfirmed {
        report.status = super::capture_report::IntegrityStatus::Unverified;
        report
            .messages
            .retain(|message| message != "Available checks passed.");
        report.messages.push(STARTUP_UNCONFIRMED.to_owned());
    }
}

fn detect_capabilities_blocking(serial: String, cancel: CancellationToken) -> DeviceCapabilities {
    let Ok(adb) = AdbRunner::location(serial, cancel) else {
        return DeviceCapabilities {
            query_error: Some("Android SDK platform-tools ADB was not found".to_owned()),
            ..DeviceCapabilities::default()
        };
    };
    let mut android_sdk = 0;
    let mut build_type = "unknown".to_owned();
    match adb.shell(DEVICE_BUILD_COMMAND, ADB_SHORT_TIMEOUT) {
        Ok(output) if output.status.success() => {
            (android_sdk, build_type) = parse_build_properties(&output.stdout);
        }
        _ => {
            return DeviceCapabilities {
                android_sdk,
                build_type,
                query_error: Some("Unable to read Android build properties".to_owned()),
                ..DeviceCapabilities::default()
            };
        }
    }
    let query = adb.run(
        adb.with_arguments(&PERFETTO_QUERY_ARGUMENTS),
        ADB_QUERY_TIMEOUT,
        SHELL_OUTPUT_LIMIT,
    );
    let mut capabilities = match query {
        Ok(output) if output.status.success() => DeviceCapabilities {
            android_sdk,
            build_type,
            data_source_names: parse_perfetto_data_source_names(&String::from_utf8_lossy(
                &output.stdout,
            )),
            query_error: None,
            ..DeviceCapabilities::default()
        },
        Ok(_) => DeviceCapabilities {
            android_sdk,
            build_type,
            query_error: Some("Perfetto is unavailable on this device".to_owned()),
            ..DeviceCapabilities::default()
        },
        Err(error) => DeviceCapabilities {
            android_sdk,
            build_type,
            query_error: Some(error),
            ..DeviceCapabilities::default()
        },
    };
    match adb.run(
        adb.with_arguments(&["shell", "atrace", "--list_categories"]),
        ADB_QUERY_TIMEOUT,
        SHELL_OUTPUT_LIMIT,
    ) {
        Ok(output) if output.status.success() => {
            capabilities.atrace_categories =
                parse_atrace_categories(&String::from_utf8_lossy(&output.stdout));
            if capabilities.atrace_categories.is_empty() {
                capabilities.category_query_error =
                    Some("The device returned no atrace categories".to_owned());
            }
        }
        Ok(_) => {
            capabilities.category_query_error = Some("Unable to query atrace categories".to_owned())
        }
        Err(error) => capabilities.category_query_error = Some(error),
    }
    capabilities
}

// ---------------------------------------------------------------------------
// Capture runtime state owned by TraceWorkspace.
// ---------------------------------------------------------------------------

/// Config UI entities are created lazily because Capture::new has no Window.
pub(in crate::trace) struct CaptureInputs {
    pub(in crate::trace) target_package: Entity<InputState>,
    pub(in crate::trace) duration: Entity<InputState>,
    pub(in crate::trace) buffer_mb: Entity<InputState>,
    pub(in crate::trace) additional_categories: Entity<InputState>,
    pub(in crate::trace) custom_config: Entity<TextareaState>,
    _subscriptions: Vec<Subscription>,
}

pub(in crate::trace) struct Capture {
    data_dir: Option<PathBuf>,
    language: Language,
    devices: Vec<CaptureDevice>,
    devices_loaded: bool,
    selected_serial: Option<String>,
    refresh_in_progress: bool,
    refresh_error: Option<String>,
    device_generation: u64,
    capabilities: Option<DeviceCapabilities>,
    capabilities_in_progress: bool,
    capability_generation: u64,
    state: CaptureState,
    active: Option<ActiveCapture>,
    generation: u64,
    cancel: CancellationToken,
    selected_template: CaptureTemplate,
    enabled_probes: BTreeSet<Probe>,
    inputs: Option<CaptureInputs>,
    shared_target_package: Option<String>,
    applied_target_package: Option<String>,
    selected_categories: BTreeSet<String>,
    config_draft: String,
    pending_editor_text: Option<String>,
    config_generation: u64,
    config_picking: bool,
    config_notice: Option<String>,
    runtime_notice: Option<String>,
    preflight: Option<super::capture_config::PreflightReport>,
    preflight_dirty: bool,
    integrity: Option<CaptureIntegritySummary>,
}

impl Capture {
    pub(in crate::trace) fn new(data_dir: Option<PathBuf>) -> Self {
        let (config_draft, config_notice) = match data_dir.as_deref() {
            Some(directory) => match super::capture_config::load_draft(directory) {
                Ok(Some(text)) => (text, None),
                Ok(None) => (super::capture_config::default_text().to_owned(), None),
                Err(error) => (
                    super::capture_config::default_text().to_owned(),
                    Some(error),
                ),
            },
            None => (super::capture_config::default_text().to_owned(), None),
        };
        Self {
            data_dir,
            language: Language::English,
            devices: Vec::new(),
            devices_loaded: false,
            selected_serial: None,
            refresh_in_progress: false,
            refresh_error: None,
            device_generation: 0,
            capabilities: None,
            capabilities_in_progress: false,
            capability_generation: 0,
            state: CaptureState::Idle,
            active: None,
            generation: 0,
            cancel: CancellationToken::new(),
            selected_template: CaptureTemplate::SystemOverview,
            enabled_probes: CaptureTemplate::SystemOverview.default_probes(),
            inputs: None,
            shared_target_package: None,
            applied_target_package: None,
            selected_categories: atrace_categories(CaptureTemplate::SystemOverview)
                .iter()
                .map(|category| (*category).to_owned())
                .collect(),
            config_draft,
            pending_editor_text: None,
            config_generation: 0,
            config_picking: false,
            config_notice,
            runtime_notice: None,
            preflight: None,
            preflight_dirty: true,
            integrity: None,
        }
    }

    // -- device discovery -------------------------------------------------

    pub(in crate::trace) fn devices(&self) -> &[CaptureDevice] {
        &self.devices
    }

    pub(in crate::trace) fn devices_loaded(&self) -> bool {
        self.devices_loaded
    }

    pub(in crate::trace) fn selected_device(&self) -> Option<&CaptureDevice> {
        let serial = self.selected_serial.as_deref()?;
        self.devices.iter().find(|device| device.serial == serial)
    }

    pub(in crate::trace) fn device_ready(&self) -> bool {
        self.selected_device().is_some_and(|device| device.online)
    }

    pub(in crate::trace) fn refresh_in_progress(&self) -> bool {
        self.refresh_in_progress
    }

    pub(in crate::trace) fn refresh_error(&self) -> Option<&str> {
        self.refresh_error.as_deref()
    }

    pub(in crate::trace) fn publish_devices(&mut self, devices: &[AdbDevice]) {
        self.devices = devices
            .iter()
            .map(|device| CaptureDevice {
                serial: device.serial.clone(),
                display_name: device.display_name(),
                online: device.is_online(),
            })
            .collect();
        self.devices_loaded = true;
        // A newer shared snapshot supersedes a pending page-local refresh.
        self.device_generation = self.device_generation.wrapping_add(1);
        self.refresh_in_progress = false;
        self.refresh_error = None;
        let preferred = preferred_device_serial(self.selected_serial.as_deref(), &self.devices);
        self.set_selected_serial(preferred);
    }

    fn set_selected_serial(&mut self, serial: Option<String>) {
        if self.selected_serial == serial {
            return;
        }
        self.selected_serial = serial;
        self.capabilities = None;
        self.capabilities_in_progress = false;
        self.capability_generation = self.capability_generation.wrapping_add(1);
        if self.selected_template == CaptureTemplate::FullCapture {
            self.selected_categories.clear();
            self.invalidate_preflight();
        }
    }

    pub(in crate::trace) fn apply_devices(
        &mut self,
        devices: &[AdbDevice],
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        self.publish_devices(devices);
        self.refresh_missing_capabilities(cx);
        cx.notify();
    }

    /// Explicit refresh only: adb devices -l through the read-only adapter.
    pub(in crate::trace) fn refresh_devices(
        &mut self,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if self.refresh_in_progress {
            return;
        }
        self.refresh_in_progress = true;
        self.refresh_error = None;
        self.device_generation = self.device_generation.wrapping_add(1);
        let generation = self.device_generation;
        let cancel = CancellationToken::new();
        let task = cx.background_executor().spawn(async move {
            let location = AdbLocator::new()
                .locate(None)
                .map_err(|error| error.to_string())?;
            let client = aps_adb::AdbClient::new(location);
            client
                .list_devices(&cancel)
                .map_err(|error| error.to_string())
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.capture.device_generation != generation {
                    return;
                }
                this.capture.refresh_in_progress = false;
                match result {
                    Ok(devices) => {
                        this.capture.apply_devices(&devices, cx);
                    }
                    Err(error) => this.capture.refresh_error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    pub(in crate::trace) fn select_device(
        &mut self,
        serial: Option<String>,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        self.set_selected_serial(serial);
        self.refresh_missing_capabilities(cx);
        cx.notify();
    }

    // -- capability detection --------------------------------------------

    pub(in crate::trace) fn capabilities(&self) -> Option<&DeviceCapabilities> {
        self.capabilities.as_ref()
    }

    #[allow(dead_code)]
    pub(in crate::trace) fn capabilities_in_progress(&self) -> bool {
        self.capabilities_in_progress
    }

    fn refresh_missing_capabilities(&mut self, cx: &mut Context<crate::trace::TraceWorkspace>) {
        if self.device_ready() && self.capabilities.is_none() && !self.capabilities_in_progress {
            self.refresh_capabilities(cx);
        }
    }

    fn refresh_capabilities(&mut self, cx: &mut Context<crate::trace::TraceWorkspace>) {
        let Some(serial) = self.selected_serial.clone() else {
            self.capabilities = None;
            self.capabilities_in_progress = false;
            if self.selected_template == CaptureTemplate::FullCapture {
                self.selected_categories.clear();
                self.invalidate_preflight();
            }
            return;
        };
        self.capability_generation = self.capability_generation.wrapping_add(1);
        let generation = self.capability_generation;
        self.capabilities = None;
        self.capabilities_in_progress = true;
        if self.selected_template == CaptureTemplate::FullCapture {
            self.selected_categories.clear();
            self.invalidate_preflight();
        }
        let cancel = CancellationToken::new();
        let task = cx
            .background_executor()
            .spawn(async move { detect_capabilities_blocking(serial, cancel) });
        cx.spawn(async move |this, cx| {
            let capabilities = task.await;
            this.update(cx, |this, cx| {
                if this.capture.apply_capabilities(generation, capabilities) {
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    fn apply_capabilities(&mut self, generation: u64, capabilities: DeviceCapabilities) -> bool {
        if self.capability_generation != generation {
            return false;
        }
        self.capabilities = Some(capabilities);
        self.capabilities_in_progress = false;
        self.preflight = None;
        self.preflight_dirty = true;
        if self.selected_template == CaptureTemplate::FullCapture {
            self.select_all_categories(true);
        }
        true
    }

    // -- config UI state --------------------------------------------------

    fn invalidate_preflight(&mut self) {
        self.preflight = None;
        self.preflight_dirty = true;
        self.config_generation = self.config_generation.wrapping_add(1);
    }

    pub(in crate::trace) fn selected_categories(&self) -> &BTreeSet<String> {
        &self.selected_categories
    }

    pub(in crate::trace) fn toggle_category(&mut self, name: &str, selected: bool) {
        if self.is_active()
            || !self.capabilities.as_ref().is_some_and(|capabilities| {
                capabilities.atrace_categories.contains_key(name)
                    && (self.selected_template != CaptureTemplate::FullCapture
                        || capabilities.category_query_error.is_none())
            })
        {
            return;
        }
        if selected {
            self.selected_categories.insert(name.to_owned());
        } else {
            self.selected_categories.remove(name);
        }
        self.invalidate_preflight();
    }

    pub(in crate::trace) fn select_all_categories(&mut self, selected: bool) {
        if self.is_active() {
            return;
        }
        self.selected_categories = if selected {
            self.capabilities
                .as_ref()
                .filter(|capabilities| {
                    self.selected_template != CaptureTemplate::FullCapture
                        || capabilities.category_query_error.is_none()
                })
                .map(|capabilities| capabilities.atrace_categories.keys().cloned().collect())
                .unwrap_or_default()
        } else {
            BTreeSet::new()
        };
        self.invalidate_preflight();
    }

    pub(in crate::trace) fn preflight_report(
        &self,
    ) -> Option<&super::capture_config::PreflightReport> {
        self.preflight.as_ref()
    }

    pub(in crate::trace) fn config_notice(&self) -> Option<&str> {
        self.config_notice.as_deref()
    }

    pub(in crate::trace) fn runtime_notice(&self) -> Option<&str> {
        self.runtime_notice.as_deref()
    }

    pub(in crate::trace) fn integrity(&self) -> Option<&CaptureIntegritySummary> {
        self.integrity.as_ref()
    }

    pub(in crate::trace) fn reveal_integrity_report(
        &self,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if let Some(path) = self
            .integrity
            .as_ref()
            .and_then(|report| report.report_path.as_ref())
        {
            cx.reveal_path(path);
        }
    }

    fn inspect_config(&self, config: &CaptureConfig) -> super::capture_config::PreflightReport {
        let capabilities = self.capabilities.as_ref();
        let sources = capabilities
            .filter(|capabilities| {
                capabilities.query_error.is_none() && !capabilities.data_source_names.is_empty()
            })
            .map(|capabilities| &capabilities.data_source_names);
        let categories: Option<BTreeSet<String>> = capabilities
            .filter(|capabilities| {
                capabilities.category_query_error.is_none()
                    && !capabilities.atrace_categories.is_empty()
            })
            .map(|capabilities| capabilities.atrace_categories.keys().cloned().collect());
        let mut report = super::capture_config::preflight(
            &build_config_text(config),
            sources,
            categories.as_ref(),
        );
        if config.template == CaptureTemplate::Custom {
            report.warnings.push(STARTUP_UNCONFIRMED.to_owned());
        }
        if config.template == CaptureTemplate::FullCapture {
            if capabilities.is_none_or(|capabilities| {
                capabilities.query_error.is_some() || capabilities.data_source_names.is_empty()
            }) {
                report.errors.push(
                    "Full capture requires a successful device capability query before recording"
                        .to_owned(),
                );
            } else if config
                .enabled_probes
                .as_ref()
                .is_some_and(|probes| probes.contains(&Probe::Atrace))
                && categories.is_none()
            {
                report.errors.push(
                    "Full capture with Atrace requires a successful device category query before recording"
                        .to_owned(),
                );
            }
        }
        report
    }

    pub(in crate::trace) fn run_preflight(
        &mut self,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        self.preflight = Some(self.inspect_config(&self.config(cx)));
        self.preflight_dirty = false;
        cx.notify();
    }

    pub(in crate::trace) fn save_config(&mut self, cx: &mut Context<crate::trace::TraceWorkspace>) {
        if self.is_active() {
            return;
        }
        let text = self
            .inputs
            .as_ref()
            .map(|inputs| inputs.custom_config.read(cx).value().to_string())
            .unwrap_or_else(|| self.config_draft.clone());
        self.config_notice = Some(match self.data_dir.as_deref() {
            Some(directory) => match super::capture_config::save_draft(directory, &text) {
                Ok(()) => {
                    self.config_draft = text;
                    self.language
                        .text(TextKey::TraceCaptureConfigSaved)
                        .to_owned()
                }
                Err(error) => error,
            },
            None => "The application data directory is unavailable".to_owned(),
        });
        cx.notify();
    }

    fn set_custom_text(
        &mut self,
        text: String,
        window: &mut Window,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        self.config_draft = text.clone();
        self.selected_template = CaptureTemplate::Custom;
        let inspection = super::capture_config::preflight(&text, None, None);
        self.selected_categories = inspection.atrace_categories.into_iter().collect();
        if let Some(inputs) = &self.inputs {
            inputs
                .custom_config
                .update(cx, |state, cx| state.set_value(text, window, cx));
        } else {
            self.pending_editor_text = Some(text);
        }
        self.invalidate_preflight();
    }

    pub(in crate::trace) fn reset_config(
        &mut self,
        window: &mut Window,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if self.is_active() {
            return;
        }
        self.set_custom_text(super::capture_config::default_text().to_owned(), window, cx);
        self.save_config(cx);
    }

    pub(in crate::trace) fn import_config(
        &mut self,
        _: &mut Window,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if self.is_active() || self.config_picking {
            return;
        }
        self.invalidate_preflight();
        let generation = self.config_generation;
        self.config_picking = true;
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some("TraceConfig (.pbtx, .pbtxt, .textproto)".into()),
        });
        cx.spawn(async move |this, cx| {
            let selected = picker.await;
            let path = match selected {
                Ok(Ok(Some(paths))) if paths.len() == 1 => paths[0].clone(),
                _ => {
                    this.update(cx, |this, cx| {
                        this.capture.config_picking = false;
                        cx.notify();
                    })
                    .ok();
                    return;
                }
            };
            let task = cx
                .background_executor()
                .spawn(async move { super::capture_config::read_config(&path) });
            let result = task.await;
            this.update(cx, |this, cx| {
                this.capture.config_picking = false;
                if this.capture.config_generation != generation || this.capture.is_active() {
                    return;
                }
                match result {
                    Ok(text) => {
                        let inspection = super::capture_config::preflight(&text, None, None);
                        this.capture.selected_categories =
                            inspection.atrace_categories.into_iter().collect();
                        this.capture.config_draft = text.clone();
                        this.capture.pending_editor_text = Some(text);
                        this.capture.selected_template = CaptureTemplate::Custom;
                        this.capture.config_notice = None;
                        this.capture.preflight = None;
                        this.capture.preflight_dirty = true;
                    }
                    Err(error) => this.capture.config_notice = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    pub(in crate::trace) fn selected_template(&self) -> CaptureTemplate {
        self.selected_template
    }

    pub(in crate::trace) fn enabled_probes(&self) -> &BTreeSet<Probe> {
        &self.enabled_probes
    }

    pub(in crate::trace) fn select_template(&mut self, template: CaptureTemplate, cx: &App) {
        if self.is_active() || self.selected_template == template {
            return;
        }
        let custom_categories = if template == CaptureTemplate::Custom {
            let live = self
                .inputs
                .as_ref()
                .map(|inputs| inputs.custom_config.read(cx).value());
            custom_category_selection(live.as_deref(), &self.config_draft)
        } else {
            BTreeSet::new()
        };
        self.apply_template(template, custom_categories);
    }

    fn apply_template(&mut self, template: CaptureTemplate, custom_categories: BTreeSet<String>) {
        if self.is_active() || self.selected_template == template {
            return;
        }
        self.selected_template = template;
        self.enabled_probes = template.default_probes();
        self.selected_categories = if template == CaptureTemplate::Custom {
            custom_categories
        } else {
            atrace_categories(template)
                .iter()
                .map(|category| (*category).to_owned())
                .collect()
        };
        if template == CaptureTemplate::FullCapture {
            self.select_all_categories(true);
        }
        self.invalidate_preflight();
    }

    pub(in crate::trace) fn toggle_probe(&mut self, probe: Probe, enabled: bool) {
        if self.is_active() {
            return;
        }
        if enabled {
            self.enabled_probes.insert(probe);
        } else {
            self.enabled_probes.remove(&probe);
            if probe == Probe::Atrace {
                self.selected_categories.clear();
            }
        }
        self.invalidate_preflight();
    }

    pub(in crate::trace) fn inputs(&self) -> Option<&CaptureInputs> {
        self.inputs.as_ref()
    }

    /// The title-bar process selection seeds the target package so heap probes
    /// are usable without retyping it; an operator-entered value always wins.
    pub(in crate::trace) fn set_shared_target_package(
        &mut self,
        package: Option<String>,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if self.shared_target_package == package {
            return;
        }
        self.shared_target_package = package;
        cx.notify();
    }

    pub(in crate::trace) fn sync_shared_target_package(
        &mut self,
        window: &mut Window,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        let Some(package) = self.shared_target_package.clone() else {
            return;
        };
        let Some(inputs) = &self.inputs else {
            return;
        };
        if self.applied_target_package.as_deref() == Some(package.as_str()) {
            return;
        }
        let current = inputs.target_package.read(cx).value().trim().to_owned();
        if should_apply_shared_target(&current, self.applied_target_package.as_deref()) {
            inputs
                .target_package
                .update(cx, |state, cx| state.set_value(package.clone(), window, cx));
        }
        self.applied_target_package = Some(package);
    }

    /// Lazily creates the configuration fields and applies the current language.
    pub(in crate::trace) fn ensure_inputs(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if self.inputs.is_some() {
            if let Some(text) = self.pending_editor_text.take() {
                if let Some(inputs) = &self.inputs {
                    inputs
                        .custom_config
                        .update(cx, |state, cx| state.set_value(text, window, cx));
                }
            }
            self.set_language(language, window, cx);
            if self.preflight_dirty {
                self.preflight = Some(self.inspect_config(&self.config(cx)));
                self.preflight_dirty = false;
            }
            return;
        }
        let target_package = cx.new(|cx| InputState::new(window, cx));
        let duration = cx.new(|cx| InputState::new(window, cx).default_value("10"));
        let buffer_mb = cx.new(|cx| InputState::new(window, cx).default_value("32"));
        let additional_categories = cx.new(|cx| InputState::new(window, cx));
        let draft = self
            .pending_editor_text
            .take()
            .unwrap_or_else(|| self.config_draft.clone());
        let custom_config = cx.new(|cx| TextareaState::new(window, cx).default_value(draft));
        let subscriptions = vec![
            cx.subscribe(&target_package, |this, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    this.capture.invalidate_preflight();
                    cx.notify();
                }
            }),
            cx.subscribe(&duration, |this, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    this.capture.invalidate_preflight();
                    cx.notify();
                }
            }),
            cx.subscribe(&buffer_mb, |this, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    this.capture.invalidate_preflight();
                    cx.notify();
                }
            }),
            cx.subscribe(&additional_categories, |this, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    this.capture.invalidate_preflight();
                    cx.notify();
                }
            }),
            cx.subscribe(&custom_config, |this, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    this.capture.invalidate_preflight();
                    if this.capture.inputs.as_ref().is_some_and(|inputs| {
                        inputs.custom_config.read(cx).value().as_ref()
                            != this.capture.config_draft.as_str()
                    }) {
                        this.capture.config_notice = None;
                    }
                    cx.notify();
                }
            }),
        ];
        self.inputs = Some(CaptureInputs {
            target_package,
            duration,
            buffer_mb,
            additional_categories,
            custom_config,
            _subscriptions: subscriptions,
        });
        self.language = language;
        self.apply_placeholders(language, window, cx);
        self.preflight = Some(self.inspect_config(&self.config(cx)));
        self.preflight_dirty = false;
    }

    pub(in crate::trace) fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if self.language == language {
            return;
        }
        self.language = language;
        self.apply_placeholders(language, window, cx);
    }

    fn apply_placeholders(
        &self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        let Some(inputs) = &self.inputs else {
            return;
        };
        inputs.target_package.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::SystemWideForExampleComExampleApp),
                window,
                cx,
            );
        });
        inputs.additional_categories.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::CommaSeparatedAtraceCategoriesOrFtraceEvents),
                window,
                cx,
            );
        });
        inputs.custom_config.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::PasteAValidPerfettoTraceconfig),
                window,
                cx,
            );
        });
    }

    /// The config implied by the current fields, filtered to supported probes.
    pub(in crate::trace) fn config(&self, cx: &App) -> CaptureConfig {
        let mut config = CaptureConfig::new(self.selected_template);
        let mut target_package = None;
        let mut duration_seconds = 10;
        let mut buffer_size_kb = 32_768;
        let mut additional_categories = Vec::new();
        let mut custom_config_text = None;
        if let Some(inputs) = &self.inputs {
            let target = inputs.target_package.read(cx).value().trim().to_owned();
            target_package = (!target.is_empty()).then_some(target);
            duration_seconds =
                clamp_parse_u32(inputs.duration.read(cx).value().as_ref(), 10, 1, 600);
            buffer_size_kb =
                clamp_parse_u32(inputs.buffer_mb.read(cx).value().as_ref(), 32, 1, 1_024) * 1_024;
            additional_categories = inputs
                .additional_categories
                .read(cx)
                .value()
                .split(|character: char| character == ',' || character.is_whitespace())
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(str::to_owned)
                .collect();
            let custom = inputs.custom_config.read(cx).value().to_string();
            if self.selected_template == CaptureTemplate::Custom && !custom.trim().is_empty() {
                custom_config_text = Some(custom);
            }
        }
        config.target_package = target_package;
        config.duration_seconds = duration_seconds;
        config.buffer_size_kb = buffer_size_kb;
        dedup_preserving_order(&mut additional_categories);
        config.additional_categories = additional_categories;
        config.custom_config_text = custom_config_text;
        self.apply_selection(&mut config);
        config
    }

    fn apply_selection(&self, config: &mut CaptureConfig) {
        let full_capture = config.template == CaptureTemplate::FullCapture;
        let target_present = config
            .target_package
            .as_deref()
            .is_some_and(|target| !full_capture || is_target_package(target));
        config.enabled_probes = Some(
            self.enabled_probes
                .iter()
                .copied()
                .filter(|probe| {
                    self.capabilities
                        .as_ref()
                        .and_then(|capabilities| capabilities.probe_blocker(*probe))
                        .is_none()
                        && (!probe.requires_target_package() || target_present)
                        && (!full_capture
                            || self.capabilities.as_ref().is_some_and(|capabilities| {
                                capabilities.query_error.is_none()
                                    && probe.required_data_source().is_some_and(|source| {
                                        capabilities.data_source_names.contains(source)
                                    })
                            }))
                })
                .collect(),
        );
        let categories = if full_capture {
            self.capabilities.as_ref().filter(|capabilities| {
                capabilities.category_query_error.is_none()
                    && config
                        .enabled_probes
                        .as_ref()
                        .is_some_and(|probes| probes.contains(&Probe::Atrace))
            })
        } else {
            None
        };
        config.atrace_category_selection = Some(
            self.selected_categories
                .iter()
                .filter(|category| {
                    !full_capture
                        || categories.is_some_and(|capabilities| {
                            capabilities.atrace_categories.contains_key(*category)
                        })
                })
                .cloned()
                .collect(),
        );
        if full_capture {
            config.additional_categories.retain(|category| {
                if category.contains('/') {
                    self.capabilities.as_ref().is_some_and(|capabilities| {
                        capabilities.query_error.is_none()
                            && capabilities.data_source_names.contains("linux.ftrace")
                    })
                } else {
                    categories.is_some_and(|capabilities| {
                        capabilities.atrace_categories.contains_key(category)
                    })
                }
            });
        }
    }

    // -- capture lifecycle ------------------------------------------------

    pub(in crate::trace) fn state(&self) -> &CaptureState {
        &self.state
    }

    pub(in crate::trace) fn is_active(&self) -> bool {
        self.state.in_progress()
    }

    pub(in crate::trace) fn startup_unconfirmed(&self) -> bool {
        self.active
            .as_ref()
            .is_some_and(|active| active.startup_unconfirmed)
    }

    /// Starts one explicit capture on the selected online device.
    pub(in crate::trace) fn start(
        &mut self,
        config: CaptureConfig,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if self.is_active() {
            return;
        }
        if let Err(error) = config.validate() {
            self.state = CaptureState::Failed(error);
            cx.notify();
            return;
        }
        let preflight = self.inspect_config(&config);
        let errors = preflight.errors.join("\n");
        self.preflight = Some(preflight);
        if !errors.is_empty() {
            self.state = CaptureState::Failed(errors);
            cx.notify();
            return;
        }
        let Some(data_dir) = self.data_dir.clone() else {
            self.state =
                CaptureState::Failed("The capture artifact directory is unavailable".to_owned());
            cx.notify();
            return;
        };
        let Some(serial) = self
            .selected_device()
            .filter(|device| device.online)
            .map(|device| device.serial.clone())
        else {
            self.state = CaptureState::Failed(
                "Select an online Android device before starting a capture".to_owned(),
            );
            cx.notify();
            return;
        };
        let capability_query_failed = self
            .capabilities
            .as_ref()
            .is_none_or(|capabilities| capabilities.query_error.is_some());
        if config.template == CaptureTemplate::Custom
            && let Some(text) = config.custom_config_text.as_deref()
        {
            if let Err(error) = super::capture_config::save_draft(&data_dir, text) {
                self.config_notice = Some(error);
                cx.notify();
                return;
            }
            self.config_draft = text.to_owned();
        }
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        let cancel = self.cancel.clone();
        self.active = None;
        self.integrity = None;
        self.runtime_notice = None;
        self.state = CaptureState::Preparing {
            stop_requested: false,
        };
        let task = cx.background_executor().spawn(async move {
            capture_start_blocking(serial, config, data_dir, cancel, capability_query_failed)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.capture.generation != generation {
                    return;
                }
                match result {
                    Ok(active) => {
                        let stop_requested = this.capture.accept_started_capture(active);
                        if stop_requested {
                            this.capture.begin_completion(true, cx);
                        } else {
                            this.capture.schedule_automatic_completion(generation, cx);
                        }
                    }
                    Err(error) => {
                        this.capture.active = None;
                        this.capture.state = CaptureState::Failed(error.clone());
                        this.on_capture_failed(error, cx);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Accept a late launch without losing a stop requested while preparing.
    fn accept_started_capture(&mut self, active: ActiveCapture) -> bool {
        let stop_requested = self.state.stop_requested();
        self.state = CaptureState::Recording {
            started_unix_ms: active.started_unix_ms,
        };
        self.active = Some(active);
        stop_requested
    }

    /// Queue a preparing capture's stop, or stop/save an owned recording.
    pub(in crate::trace) fn stop(&mut self, cx: &mut Context<crate::trace::TraceWorkspace>) {
        if self.state.request_startup_stop() {
            cx.notify();
            return;
        }
        if !matches!(self.state, CaptureState::Recording { .. }) {
            return;
        }
        self.begin_completion(true, cx);
    }

    /// Cancels any in-flight capture; the next generation ignores late results.
    #[allow(dead_code)]
    pub(in crate::trace) fn cancel(&mut self) {
        self.cancel.cancel();
        self.generation = self.generation.wrapping_add(1);
        self.active = None;
        if self.state.in_progress() {
            self.state = CaptureState::Idle;
        }
    }

    fn schedule_automatic_completion(
        &mut self,
        generation: u64,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if self
            .active
            .as_ref()
            .is_some_and(|active| active.config.template.uses_supervised_launch())
        {
            self.observe_supervised_completion(generation, cx);
            return;
        }
        let Some(delay) = self
            .active
            .as_ref()
            .and_then(|active| active.automatic_delay)
        else {
            return;
        };
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            executor.timer(delay).await;
            this.update(cx, |this, cx| {
                if this.capture.generation != generation {
                    return;
                }
                if matches!(this.capture.state, CaptureState::Recording { .. }) {
                    this.capture.begin_completion(false, cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn observe_supervised_completion(
        &self,
        generation: u64,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        let Some(active) = self.active.as_ref() else {
            return;
        };
        let delay = if active.stop_signal_sent.load(Ordering::Acquire) {
            Duration::from_secs(1)
        } else {
            active.automatic_delay.unwrap_or(Duration::from_secs(1))
        };
        let executable = active.executable.clone();
        let serial = active.serial.clone();
        let remote = active.remote.clone();
        let pid = active.pid;
        let cancel = self.cancel.clone();
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            executor.timer(delay).await;
            loop {
                let observing = this.update(cx, |this, _| {
                    this.capture.generation == generation
                        && matches!(this.capture.state, CaptureState::Recording { .. })
                }).unwrap_or(false);
                if !observing { return; }
                let mut adb = AdbRunner::new(executable.clone(), serial.clone(), cancel.clone());
                adb.remote = remote.clone();
                let stopped = executor.spawn(async move { adb.remote_process_stopped(pid) }).await;
                let again = this.update(cx, |this, cx| {
                    if this.capture.generation != generation
                        || !matches!(this.capture.state, CaptureState::Recording { .. }) {
                        return false;
                    }
                    match stopped {
                        Ok(true) => {
                            this.capture.runtime_notice = None;
                            if let Some(active) = this.capture.active.as_mut() {
                                // Keep the observed exit: never signal this PID
                                // again if it is reused before artifact transfer.
                                active.stop_confirmed.store(true, Ordering::Release);
                            }
                            this.capture.begin_completion(false, cx);
                            false
                        }
                        Ok(false) => {
                            if this.capture.clear_recovered_observation_warning() {
                                cx.notify();
                            }
                            true
                        }
                        Err(error) => {
                            this.capture.runtime_notice = Some(format!(
                                "{error}. The owned run remains tracked; automatic stop was not requested."
                            ));
                            cx.notify();
                            true
                        }
                    }
                }).unwrap_or(false);
                if !again { return; }
                executor.timer(Duration::from_secs(1)).await;
            }
        }).detach();
    }

    fn begin_completion(
        &mut self,
        request_remote_stop: bool,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if !matches!(self.state, CaptureState::Recording { .. }) {
            return;
        }
        let Some(active) = self.active.clone() else {
            self.state = CaptureState::Failed("Capture session is unavailable".to_owned());
            cx.notify();
            return;
        };
        let Some(data_dir) = self.data_dir.clone() else {
            self.state =
                CaptureState::Failed("The capture artifact directory is unavailable".to_owned());
            cx.notify();
            return;
        };
        let language = self.language;
        let cancel = self.cancel.clone();
        let generation = self.generation;
        self.state = CaptureState::Pulling;
        let task = cx.background_executor().spawn(async move {
            complete_capture_blocking(active, request_remote_stop, data_dir, cancel, language)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.capture.generation != generation {
                    return;
                }
                match result {
                    Ok(output) => {
                        this.capture.active = None;
                        this.capture.integrity = Some(output.integrity.clone());
                        this.capture.state = CaptureState::Completed {
                            trace_path: output.trace_path.clone(),
                            size_bytes: output.trace_size_bytes,
                        };
                        this.on_capture_completed(output, cx);
                    }
                    Err(error) => {
                        this.capture.apply_completion_failure(error.clone());
                        if matches!(this.capture.state, CaptureState::Recording { .. }) {
                            this.capture.observe_supervised_completion(generation, cx);
                        }
                        this.on_capture_failed(error, cx);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn clear_recovered_observation_warning(&mut self) -> bool {
        if self
            .active
            .as_ref()
            .is_some_and(|active| active.stop_signal_sent.load(Ordering::Acquire))
        {
            // A successful running probe is not confirmation of graceful stop.
            return false;
        }
        self.runtime_notice.take().is_some()
    }

    fn apply_completion_failure(&mut self, error: String) {
        if let Some(active) = self.active.as_ref()
            && !active.stop_confirmed.load(Ordering::Acquire)
        {
            self.state = CaptureState::Recording {
                started_unix_ms: active.started_unix_ms,
            };
            self.runtime_notice = Some(error);
        } else {
            self.active = None;
            self.runtime_notice = None;
            self.state = CaptureState::Failed(error);
        }
    }
}

impl Drop for Capture {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}

// ---------------------------------------------------------------------------
// Tests: pure model/parser/config behavior; no device commands are executed.
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn preparing_exposes_stop_and_keeps_the_launch_tracked_until_it_returns() {
        for stop_requested in [false, true] {
            let mut capture = Capture::new(None);
            capture.state = CaptureState::Preparing {
                stop_requested: false,
            };
            assert!(capture.state.in_progress());
            assert!(capture.state.shows_stop_button());
            assert!(!capture.state.stop_requested());
            if stop_requested {
                assert!(capture.state.request_startup_stop());
                assert!(capture.state.request_startup_stop());
                assert!(capture.state.stop_requested());
            }
            assert!(!capture.cancel.is_cancelled());
            let remote = RemoteCapturePaths::new();
            let expected_path = remote.trace.clone();
            let requested = capture.accept_started_capture(ActiveCapture {
                executable: PathBuf::from("adb"),
                serial: "serial".to_owned(),
                pid: 1234,
                config: CaptureConfig::new(CaptureTemplate::FullCapture),
                started_unix_ms: 42,
                device_model: "fixture".to_owned(),
                android_sdk: 37,
                capability_query_failed: false,
                automatic_delay: None,
                remote,
                startup_unconfirmed: true,
                stop_confirmed: Arc::new(AtomicBool::new(false)),
                stop_signal_sent: Arc::new(AtomicBool::new(false)),
                manual_stop_requested: Arc::new(AtomicBool::new(false)),
            });
            assert_eq!(requested, stop_requested);
            assert_eq!(capture.active.as_ref().unwrap().pid, 1234);
            assert_eq!(capture.active.as_ref().unwrap().remote.trace, expected_path);
            assert_eq!(
                capture.state,
                CaptureState::Recording {
                    started_unix_ms: 42
                }
            );
            assert!(!capture.cancel.is_cancelled());
        }
        for mut state in [
            CaptureState::Idle,
            CaptureState::Pulling,
            CaptureState::Completed {
                trace_path: PathBuf::from("trace"),
                size_bytes: 1,
            },
            CaptureState::Failed("failure".to_owned()),
        ] {
            assert!(!state.request_startup_stop());
            assert!(!state.shows_stop_button());
            assert!(!state.stop_requested());
        }
        let mut recording = CaptureState::Recording {
            started_unix_ms: 42,
        };
        assert!(recording.shows_stop_button());
        assert!(!recording.request_startup_stop());
    }

    #[::core::prelude::v1::test]
    fn queued_startup_stop_is_completed_before_scheduling_any_auto_stop() {
        let source = include_str!("capture.rs");
        let implementation = source.split("#[cfg(test)]\nmod tests").next().unwrap();
        let accepted = implementation
            .split("let stop_requested = this.capture.accept_started_capture(active);")
            .nth(1)
            .unwrap();
        assert!(accepted.starts_with("\n                        if stop_requested {\n                            this.capture.begin_completion(true, cx);\n                        } else {\n                            this.capture.schedule_automatic_completion(generation, cx);"));
        let stop = implementation
            .split("pub(in crate::trace) fn stop(")
            .nth(1)
            .unwrap()
            .split("/// Cancels")
            .next()
            .unwrap();
        assert!(stop.contains("self.state.request_startup_stop()"));
        assert!(!stop.contains("cancel.cancel()"));
        assert!(!stop.contains("active = None"));
    }

    fn owned_stop_fixture(executable: PathBuf) -> ActiveCapture {
        ActiveCapture {
            executable,
            serial: "serial".to_owned(),
            pid: 1234,
            config: custom(
                "buffers { size_kb: 1024 } duration_ms: 1000 data_sources { config { name: \"linux.ftrace\" } }",
            ),
            started_unix_ms: 42,
            device_model: "fixture".to_owned(),
            android_sdk: 37,
            capability_query_failed: false,
            automatic_delay: None,
            remote: RemoteCapturePaths::new(),
            startup_unconfirmed: true,
            stop_confirmed: Arc::new(AtomicBool::new(false)),
            stop_signal_sent: Arc::new(AtomicBool::new(false)),
            manual_stop_requested: Arc::new(AtomicBool::new(false)),
        }
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn manual_stop_allows_flush_longer_than_five_seconds_without_a_second_signal() {
        use std::os::unix::fs::PermissionsExt;
        let root = TransferTestDirectory::new();
        let executable = root.0.join("fake adb");
        std::fs::write(
            &executable,
            r#"#!/bin/sh
set -eu
root=$(dirname "$0")
printf '%s\n' "$*" >> "$root/calls.log"
if [ "$3" = shell ] && [ "$#" -eq 4 ]; then
  case "$4" in
    *'kill -TERM 1234'*) printf 'TERM\n' >> "$root/signals.log" ;;
    *'/proc/1234'*)
      count=0
      if [ -f "$root/polls" ]; then count=$(cat "$root/polls"); fi
      count=$((count + 1)); printf '%s\n' "$count" > "$root/polls"
      if [ "$count" -le 23 ]; then printf 'running\n'; else printf 'stopped\n'; fi ;;
    *'echo pending'*) printf '0\n' ;;
    *) exit 90 ;;
  esac
elif [ "$3" = shell ] && [ "$4" = stat ]; then printf '4\n'
elif [ "$3" = pull ]; then printf data > "$5"
elif [ "$3" = shell ] && [ "$4" = rm ]; then exit 0
else exit 91
fi
"#,
        )
        .unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        let active = owned_stop_fixture(executable);
        let retained = active.clone();
        let started = std::time::Instant::now();
        let output = complete_capture_blocking(
            active,
            true,
            root.0.clone(),
            CancellationToken::new(),
            Language::English,
        )
        .unwrap();
        assert!(started.elapsed() > Duration::from_secs(5));
        assert_eq!(
            std::fs::read_to_string(root.0.join("signals.log")).unwrap(),
            "TERM\n"
        );
        assert!(
            !std::fs::read_to_string(root.0.join("calls.log"))
                .unwrap()
                .contains("-KILL")
        );
        assert!(retained.stop_confirmed.load(Ordering::Acquire));
        assert!(retained.manual_stop_requested.load(Ordering::Acquire));
        assert_eq!(output.trace_size_bytes, 4);
        assert!(
            output
                .integrity
                .messages
                .iter()
                .any(|message| message.contains("stopped manually"))
        );
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn uncertain_stops_keep_the_handle_and_retries_never_send_another_signal() {
        use std::os::unix::fs::PermissionsExt;
        for failure in ["timeout", "probe", "delivery"] {
            let root = TransferTestDirectory::new();
            let executable = root.0.join("fake adb");
            std::fs::write(
                &executable,
                format!(
                    r#"#!/bin/sh
set -eu
root=$(dirname "$0")
printf '%s\n' "$*" >> "$root/calls.log"
case "$4" in
  *'kill -TERM 1234'*)
    printf 'TERM\n' >> "$root/signals.log"
    if [ '{failure}' = delivery ]; then exit 1; fi ;;
  *'/proc/1234'*)
    if [ '{failure}' = probe ]; then exit 1; fi
    printf 'running\n' ;;
  *) exit 90 ;;
esac
"#
                ),
            )
            .unwrap();
            std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
            let mut capture = Capture::new(None);
            let active = owned_stop_fixture(executable.clone());
            let cloned = active.clone();
            let mut adb = AdbRunner::new(executable, "serial".to_owned(), CancellationToken::new());
            adb.remote = active.remote.clone();
            let error = confirm_capture_stopped(&adb, &active, true, Duration::ZERO).unwrap_err();
            capture.accept_started_capture(active);
            capture.apply_completion_failure(error.clone());
            assert!(capture.active.is_some());
            assert!(matches!(capture.state, CaptureState::Recording { .. }));
            assert!(capture.runtime_notice.as_ref().unwrap().contains(&error));
            assert!(!capture.clear_recovered_observation_warning());
            assert!(capture.runtime_notice.as_ref().unwrap().contains(&error));
            assert!(cloned.stop_signal_sent.load(Ordering::Acquire));
            assert!(!cloned.stop_confirmed.load(Ordering::Acquire));
            assert!(confirm_capture_stopped(&adb, &cloned, true, Duration::ZERO).is_err());
            assert_eq!(
                std::fs::read_to_string(root.0.join("signals.log")).unwrap(),
                "TERM\n"
            );
            let calls = std::fs::read_to_string(root.0.join("calls.log")).unwrap();
            assert!(!calls.contains("-KILL"));
            assert!(!calls.contains(" pull "));
            assert!(!calls.contains(" rm "));
            // Once exit is confirmed, a real capture error remains fatal and
            // cannot be turned into a retry or successful capture.
            cloned.stop_confirmed.store(true, Ordering::Release);
            capture.apply_completion_failure("exit code 143".to_owned());
            assert!(capture.active.is_none());
            assert_eq!(
                capture.state,
                CaptureState::Failed("exit code 143".to_owned())
            );
        }
    }

    #[::core::prelude::v1::test]
    fn recovered_observation_only_clears_transient_warnings_without_a_pending_stop() {
        let mut capture = Capture::new(None);
        capture.accept_started_capture(owned_stop_fixture(PathBuf::from("adb")));
        capture.runtime_notice = Some("transient ADB probe failure".to_owned());
        assert!(capture.clear_recovered_observation_warning());
        assert!(capture.runtime_notice.is_none());
    }

    fn startup_output(code: i32, stdout: &str, stderr: &str) -> ProcessOutput {
        #[cfg(unix)]
        let status = {
            use std::os::unix::process::ExitStatusExt;
            std::process::ExitStatus::from_raw(code << 8)
        };
        #[cfg(windows)]
        let status = {
            use std::os::windows::process::ExitStatusExt;
            std::process::ExitStatus::from_raw(code as u32)
        };
        ProcessOutput {
            status,
            stdout: stdout.as_bytes().to_vec(),
            stderr: stderr.as_bytes().to_vec(),
        }
    }

    #[::core::prelude::v1::test]
    fn custom_background_launch_does_not_claim_readiness_and_all_nonzero_exits_fail() {
        let output = startup_output(
            2,
            "1234\n",
            "Timeout waiting for all data sources to start\n",
        );
        for template in CaptureTemplate::ALL {
            assert!(capture_start_result(&output, template).is_err());
            assert_eq!(
                capture_start_result(&startup_output(0, "1234\n", ""), template).unwrap(),
                CaptureStart {
                    pid: 1234,
                    startup_unconfirmed: matches!(
                        template,
                        CaptureTemplate::Custom | CaptureTemplate::FullCapture
                    ),
                }
            );
        }
        for output in [
            startup_output(2, "", "Timeout waiting for all data sources to start"),
            startup_output(2, "0\n", "Timeout waiting for all data sources to start"),
            startup_output(1, "1234\n", "Timeout waiting for all data sources to start"),
            startup_output(2, "1234\n", "Failed to open the output file"),
            startup_output(1, "1234\n", "invalid config"),
        ] {
            assert!(capture_start_result(&output, CaptureTemplate::Custom).is_err());
        }
    }

    #[::core::prelude::v1::test]
    fn unconfirmed_startup_cannot_be_reported_as_integrity_passed() {
        let root = TransferTestDirectory::new();
        let mut report = super::super::capture_report::inspect(
            &root.0.join("missing.pftrace"),
            &[],
            None,
            &CancellationToken::new(),
        );
        report.status = super::super::capture_report::IntegrityStatus::Verified;
        report.messages = vec!["Available checks passed.".to_owned()];
        mark_startup_readiness(&mut report, false);
        assert_eq!(
            report.status,
            super::super::capture_report::IntegrityStatus::Verified
        );
        mark_startup_readiness(&mut report, true);
        assert_eq!(
            report.status,
            super::super::capture_report::IntegrityStatus::Unverified
        );
        assert_eq!(report.messages, vec![STARTUP_UNCONFIRMED.to_owned()]);
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn broad_supervised_launch_retains_owned_trace_and_marks_report_unverified() {
        use std::os::unix::fs::PermissionsExt;
        for template in [CaptureTemplate::FullCapture, CaptureTemplate::Custom] {
            let root = TransferTestDirectory::new();
            let executable = root.0.join("fake adb");
            std::fs::write(
                &executable,
                r#"#!/bin/sh
set -eu
if [ "$3" = 'shell' ] && [ "$#" -eq 4 ]; then
  case "$4" in
    umask*)
      case "$4" in *'--background'*) exit 92 ;; esac
      printf '1234\n' ;;
    *'echo pending'*) printf '0\n' ;;
    *'/proc/1234'*) exit 93 ;;
    *) exit 90 ;;
  esac
elif [ "$3" = 'shell' ] && [ "$4" = 'stat' ]; then
  printf '4\n'
elif [ "$3" = 'pull' ]; then
  printf 'data' > "$5"
elif [ "$3" = 'shell' ] && [ "$4" = 'rm' ]; then
  exit 0
else
  exit 91
fi
"#,
            )
            .unwrap();
            std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
            let adb = AdbRunner::new(
                executable.clone(),
                "serial".to_owned(),
                CancellationToken::new(),
            );
            let started = adb.start_capture(template).unwrap();
            assert!(started.startup_unconfirmed);
            let mut config = CaptureConfig::new(template);
            config.duration_seconds = 1;
            config.enabled_probes = Some([Probe::CpuScheduling].into_iter().collect());
            config.template = template;
            let output = complete_capture_blocking(
                ActiveCapture {
                    executable,
                    serial: "serial".to_owned(),
                    pid: started.pid,
                    config,
                    started_unix_ms: 0,
                    device_model: "fixture".to_owned(),
                    android_sdk: 34,
                    capability_query_failed: false,
                    automatic_delay: Some(Duration::ZERO),
                    remote: adb.remote.clone(),
                    startup_unconfirmed: started.startup_unconfirmed,
                    stop_confirmed: Arc::new(AtomicBool::new(true)),
                    stop_signal_sent: Arc::new(AtomicBool::new(false)),
                    manual_stop_requested: Arc::new(AtomicBool::new(false)),
                },
                true,
                root.0.clone(),
                CancellationToken::new(),
                Language::English,
            )
            .unwrap();
            assert_eq!(output.trace_size_bytes, 4);
            assert_eq!(
                output.integrity.status,
                Language::English.text(TextKey::TraceCaptureIntegrityUnverified)
            );
            assert!(
                output
                    .integrity
                    .messages
                    .iter()
                    .any(|message| message == STARTUP_UNCONFIRMED)
            );
        }
    }

    #[::core::prelude::v1::test]
    fn custom_exit_status_requires_an_explicit_bounded_code() {
        assert_eq!(parse_custom_exit_status(b"0\n"), Ok(Some(0)));
        assert_eq!(parse_custom_exit_status(b"137\r\n"), Ok(Some(137)));
        assert_eq!(parse_custom_exit_status(b"255\n"), Ok(Some(255)));
        assert_eq!(parse_custom_exit_status(b"pending\n"), Ok(None));
        assert_eq!(parse_custom_exit_status(b""), Ok(None));
        assert!(
            parse_custom_exit_status(b"logger_failed:1\n")
                .unwrap_err()
                .contains("diagnostic logger failed")
        );
        for invalid in [b"-1".as_slice(), b"256", b"0\n1", b"abc", b"\xff"] {
            assert!(parse_custom_exit_status(invalid).is_err());
        }
        assert_eq!(
            diagnostic_text(b"failed\r\nreason\0\n", 100),
            "failed | reason"
        );
        let verbose = format!("{}\nterminal error", "warning\n".repeat(500));
        assert!(diagnostic_text(verbose.as_bytes(), 2048).ends_with("terminal error"));
        assert!(diagnostic_text(verbose.as_bytes(), 2048).starts_with("[truncated] "));
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn custom_failure_keeps_diagnostics_and_never_accepts_nonzero_output() {
        use std::os::unix::fs::PermissionsExt;
        for (status, size, expected) in [
            ("7", "4", "exit code 7"),
            ("143", "4", "exit code 143"),
            ("0", "0", "captured trace is empty"),
            ("invalid", "4", "exit status is invalid"),
        ] {
            let root = TransferTestDirectory::new();
            let executable = root.0.join("fake adb");
            std::fs::write(
                &executable,
                format!(
                    r#"#!/bin/sh
set -eu
printf '%s\n' "$*" >> "$(dirname "$0")/calls.log"
if [ "$3" = shell ] && [ "$#" -eq 4 ]; then
  case "$4" in
    *'echo pending'*) printf '%s\n' '{status}' ;;
    'tail -c 8192 '*) printf 'Disconnected from the traced service\n' ;;
    *) exit 90 ;;
  esac
elif [ "$3" = shell ] && [ "$4" = stat ]; then
  printf '%s\n' '{size}'
else
  exit 91
fi
"#
                ),
            )
            .unwrap();
            std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
            let remote = RemoteCapturePaths::new();
            let expected_log = remote.log.clone();
            let expected_trace = remote.trace.clone();
            let result = complete_capture_blocking(
                ActiveCapture {
                    executable,
                    serial: "serial".to_owned(),
                    pid: 1234,
                    config: custom(
                        "buffers { size_kb: 1024 } duration_ms: 1000 data_sources { config { name: \"linux.ftrace\" } }",
                    ),
                    started_unix_ms: 0,
                    device_model: "fixture".to_owned(),
                    android_sdk: 34,
                    capability_query_failed: false,
                    automatic_delay: None,
                    remote,
                    startup_unconfirmed: true,
                    stop_confirmed: Arc::new(AtomicBool::new(true)),
                    stop_signal_sent: Arc::new(AtomicBool::new(false)),
                    manual_stop_requested: Arc::new(AtomicBool::new(false)),
                },
                false,
                root.0.clone(),
                CancellationToken::new(),
                Language::English,
            );
            let error = result.unwrap_err();
            assert!(error.contains(expected), "{error}");
            assert!(
                error.contains("Disconnected from the traced service"),
                "{error}"
            );
            assert!(error.contains(&expected_log));
            assert!(error.contains(&expected_trace));
            assert!(error.contains("Device cleanup was skipped"));
            let calls = std::fs::read_to_string(root.0.join("calls.log")).unwrap();
            assert!(!calls.contains(" pull "));
            assert!(!calls.contains(" rm "));
        }
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn custom_supervisor_survives_launcher_exit_and_records_the_actual_child() {
        use std::os::unix::fs::PermissionsExt;
        for logger_fails in [false, true] {
            let root = TransferTestDirectory::new();
            if logger_fails {
                let tail = root.0.join("tail");
                std::fs::write(&tail, "#!/bin/sh\ncat >/dev/null\nexit 9\n").unwrap();
                std::fs::set_permissions(&tail, std::fs::Permissions::from_mode(0o700)).unwrap();
            }
            let executable = root.0.join("perfetto");
            std::fs::write(
                &executable,
                r#"#!/bin/sh
printf '%s\n' "$$" > "$APS_TEST_CHILD_PID"
IFS= read -r config
[ "$config" = 'config from stdin' ] || exit 91
sleep 0.3
printf 'child failed after the launcher exited\n' >&2
exit 7
"#,
            )
            .unwrap();
            std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
            let remote = RemoteCapturePaths {
                config: root.0.join("config").to_string_lossy().into_owned(),
                trace: root.0.join("trace").to_string_lossy().into_owned(),
                pid: root.0.join("pid").to_string_lossy().into_owned(),
                log: root.0.join("log").to_string_lossy().into_owned(),
                exit_status: root.0.join("exit").to_string_lossy().into_owned(),
            };
            std::fs::write(&remote.config, "config from stdin\n").unwrap();
            let child_pid_path = root.0.join("actual-pid");
            let mut request = ProcessRequest::new("/bin/sh");
            request.arguments = vec![
                "-c".into(),
                remote.start_command(CaptureTemplate::Custom).into(),
            ];
            request.environment.insert(
                "PATH".into(),
                format!("{}:/usr/bin:/bin", root.0.display()).into(),
            );
            request.environment.insert(
                "APS_TEST_CHILD_PID".into(),
                child_pid_path.as_os_str().to_owned(),
            );
            let output = ProcessRunner
                .run(&request, &CancellationToken::new())
                .unwrap();
            let started = capture_start_result(&output, CaptureTemplate::Custom).unwrap();
            for _ in 0..100 {
                if std::fs::read(&remote.exit_status).is_ok_and(|text| !text.is_empty()) {
                    break;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            if logger_fails {
                assert_eq!(
                    std::fs::read(&remote.exit_status).unwrap(),
                    b"logger_failed:9\n"
                );
                assert_eq!(
                    std::fs::read(format!("{}.tmp", remote.exit_status)).unwrap(),
                    b"7\n"
                );
            } else {
                assert_eq!(std::fs::read(&remote.exit_status).unwrap(), b"7\n");
                assert_eq!(
                    std::fs::read_to_string(&remote.log).unwrap(),
                    "child failed after the launcher exited\n"
                );
            }
            assert_eq!(
                std::fs::read_to_string(child_pid_path)
                    .unwrap()
                    .trim()
                    .parse::<u32>()
                    .unwrap(),
                started.pid
            );
            assert!(started.startup_unconfirmed);
        }
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn custom_supervisor_reaps_child_when_pid_publication_fails_or_is_late() {
        use std::os::unix::fs::PermissionsExt;
        for delayed in [false, true] {
            let root = TransferTestDirectory::new();
            let executable = root.0.join("perfetto");
            std::fs::write(&executable, "#!/bin/sh\nsleep 0.4\nexit 0\n").unwrap();
            std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
            if delayed {
                let nohup = root.0.join("nohup");
                std::fs::write(&nohup, "#!/bin/sh\nsleep 0.2\nexec \"$@\"\n").unwrap();
                std::fs::set_permissions(&nohup, std::fs::Permissions::from_mode(0o700)).unwrap();
            }
            let remote = RemoteCapturePaths {
                config: root.0.join("config").to_string_lossy().into_owned(),
                trace: root.0.join("trace").to_string_lossy().into_owned(),
                pid: root.0.join("pid").to_string_lossy().into_owned(),
                log: root.0.join("log").to_string_lossy().into_owned(),
                exit_status: root.0.join("exit").to_string_lossy().into_owned(),
            };
            std::fs::write(&remote.config, "test\n").unwrap();
            if !delayed {
                std::fs::create_dir(&remote.pid).unwrap();
            }
            let mut request = ProcessRequest::new("/bin/sh");
            request.arguments = vec![
                "-c".into(),
                remote
                    .start_command(CaptureTemplate::Custom)
                    .replace("sleep 0.05", "sleep 0.001")
                    .into(),
            ];
            request.environment.insert(
                "PATH".into(),
                format!("{}:/usr/bin:/bin", root.0.display()).into(),
            );
            let output = ProcessRunner
                .run(&request, &CancellationToken::new())
                .unwrap();
            assert!(capture_start_result(&output, CaptureTemplate::Custom).is_err());
            for _ in 0..100 {
                if std::fs::read(&remote.exit_status).is_ok_and(|text| {
                    parse_custom_exit_status(&text).is_ok_and(|code| code.is_some())
                }) {
                    break;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            let code = parse_custom_exit_status(&std::fs::read(&remote.exit_status).unwrap())
                .unwrap()
                .unwrap();
            assert_eq!(
                code, 125,
                "an unaccepted launch must exit before executing Perfetto"
            );
        }
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn custom_background_launch_without_a_pid_stays_failed() {
        use std::os::unix::fs::PermissionsExt;
        let root = TransferTestDirectory::new();
        let executable = root.0.join("fake adb");
        std::fs::write(
            &executable,
            r#"#!/bin/sh
set -eu
if [ "$3" = 'shell' ] && [ "$#" -eq 4 ]; then
  case "$4" in
    umask*) exit 0 ;;
    *'/proc/1234'*) printf 'stopped\n' ;;
    *) exit 90 ;;
  esac
else
  exit 1
fi
"#,
        )
        .unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        let adb = AdbRunner::new(executable, "serial".to_owned(), CancellationToken::new());
        assert!(
            adb.start_capture(CaptureTemplate::Custom)
                .unwrap_err()
                .contains("without returning a background process id")
        );
    }

    #[::core::prelude::v1::test]
    fn custom_auto_completion_observes_exit_and_keeps_the_handle_on_probe_failure() {
        let source = include_str!("capture.rs");
        let implementation = source.split("#[cfg(test)]\nmod tests").next().unwrap();
        let schedule = implementation
            .split("fn schedule_automatic_completion(")
            .nth(1)
            .unwrap()
            .split("fn observe_supervised_completion(")
            .next()
            .unwrap();
        assert!(schedule.contains("uses_supervised_launch()"));
        assert!(schedule.contains("self.observe_supervised_completion(generation, cx)"));
        let observe = implementation
            .split("fn observe_supervised_completion(")
            .nth(1)
            .unwrap()
            .split("fn begin_completion(")
            .next()
            .unwrap();
        assert!(observe.contains("active.stop_confirmed.store(true, Ordering::Release)"));
        assert!(observe.contains("this.capture.begin_completion(false, cx)"));
        assert!(observe.contains("this.capture.runtime_notice"));
        assert!(!observe.contains(".kill("));
        assert!(!observe.contains("active = None"));
        assert!(!observe.contains("CaptureState::Failed"));
    }

    struct TransferTestDirectory(PathBuf);

    impl TransferTestDirectory {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-trace-transfer-{}-{}",
                std::process::id(),
                unique_id("test"),
            ));
            std::fs::create_dir(&root).unwrap();
            Self(root)
        }
    }

    impl Drop for TransferTestDirectory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[::core::prelude::v1::test]
    fn trace_file_sizes_use_the_trace_budget_not_the_process_output_budget() {
        for size in [
            1,
            MAX_OUTPUT_LIMIT as u64 + 1,
            aps_perfetto::MAX_TRACE_BYTES,
        ] {
            assert_eq!(
                parse_trace_transfer_size(format!("{size}\n").as_bytes()),
                Ok(size)
            );
        }
        assert!(
            parse_trace_transfer_size(b"0\n")
                .unwrap_err()
                .contains("empty")
        );
        assert!(
            parse_trace_transfer_size(format!("{}", aps_perfetto::MAX_TRACE_BYTES + 1).as_bytes())
                .unwrap_err()
                .contains("8 GiB"),
        );
        for text in ["", "-1", "+1", "12 bytes", "1\n2", "18446744073709551616"] {
            assert!(
                parse_trace_transfer_size(text.as_bytes()).is_err(),
                "{text:?}"
            );
        }
        assert!(parse_trace_transfer_size(&[0xff]).is_err());
    }

    #[::core::prelude::v1::test]
    fn trace_transfer_accepts_a_file_larger_than_64_mib_without_buffering_it() {
        let root = TransferTestDirectory::new();
        let path = root.0.join("large trace.pftrace");
        let expected = MAX_OUTPUT_LIMIT as u64 + 1024;
        let bytes = receive_trace_file(&path, expected, &CancellationToken::new(), || {
            std::fs::OpenOptions::new()
                .write(true)
                .open(&path)
                .unwrap()
                .set_len(expected)
                .unwrap();
            Ok(())
        })
        .unwrap();
        assert_eq!(bytes, expected);
        assert_eq!(std::fs::metadata(path).unwrap().len(), expected);
    }

    #[::core::prelude::v1::test]
    fn trace_transfer_failures_remove_only_the_owned_partial_file() {
        let root = TransferTestDirectory::new();
        let path = root.0.join("partial.pftrace");
        for error in [
            "The ADB command timed out",
            "Capture cancelled",
            "transport disconnected",
        ] {
            let result = receive_trace_file(&path, 4, &CancellationToken::new(), || {
                std::fs::write(&path, b"xx").unwrap();
                Err(error.to_owned())
            });
            assert_eq!(result.unwrap_err(), error);
            assert!(!path.exists());
        }
        let cancel = CancellationToken::new();
        let result = receive_trace_file(&path, 4, &cancel, || {
            std::fs::write(&path, b"data").unwrap();
            cancel.cancel();
            Ok(())
        });
        assert_eq!(result.unwrap_err(), "Capture cancelled");
        assert!(!path.exists());
    }

    #[::core::prelude::v1::test]
    fn trace_transfer_rejects_empty_truncated_and_oversized_local_files() {
        let root = TransferTestDirectory::new();
        let path = root.0.join("incomplete.pftrace");
        for bytes in [b"".as_slice(), b"abc".as_slice(), b"abcde".as_slice()] {
            let result = receive_trace_file(&path, 4, &CancellationToken::new(), || {
                std::fs::write(&path, bytes).unwrap();
                Ok(())
            });
            let error = result.unwrap_err();
            assert!(error.contains("incomplete"));
            assert!(error.contains("expected=4 bytes"));
            assert!(error.contains(&format!("actual={} bytes", bytes.len())));
            assert!(!path.exists());
        }
    }

    #[::core::prelude::v1::test]
    fn trace_transfer_preserves_an_existing_destination_and_checks_cancel_before_io() {
        let root = TransferTestDirectory::new();
        let path = root.0.join("existing.pftrace");
        std::fs::write(&path, b"keep").unwrap();
        assert!(
            receive_trace_file(&path, 4, &CancellationToken::new(), || {
                panic!("must not pull over an existing destination");
            })
            .is_err(),
        );
        assert_eq!(std::fs::read(&path).unwrap(), b"keep");
        let cancelled_path = root.0.join("cancelled.pftrace");
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert_eq!(
            receive_trace_file(&cancelled_path, 4, &cancel, || panic!("must not pull"))
                .unwrap_err(),
            "Capture cancelled",
        );
        assert!(!cancelled_path.exists());
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn adb_pull_transfers_large_files_instead_of_returning_their_bytes_as_stdout() {
        use std::io::{Read, Seek, SeekFrom, Write};
        use std::os::unix::fs::PermissionsExt;

        let root = TransferTestDirectory::new();
        let source = root.0.join("source trace.pftrace");
        let size = MAX_OUTPUT_LIMIT as u64 + 1024;
        let mut file = std::fs::File::create(&source).unwrap();
        file.set_len(size).unwrap();
        file.write_all(b"start").unwrap();
        file.seek(SeekFrom::End(-3)).unwrap();
        file.write_all(b"end").unwrap();
        drop(file);
        let remote = RemoteCapturePaths::new();
        let remote_path = &remote.trace;
        let executable = root.0.join("fake adb");
        let script = format!(
            r#"#!/bin/sh
set -eu
[ "$1" = '-s' ] && [ "$2" = 'serial' ] || exit 90
if [ "$3" = 'shell' ]; then
  [ "$#" -eq 7 ] && [ "$4" = 'stat' ] && [ "$5" = '-c' ] && [ "$6" = '%s' ] && [ "$7" = '{remote_path}' ] || exit 91
  printf '%s\n' '{size}'
elif [ "$3" = 'pull' ]; then
  [ "$#" -eq 5 ] && [ "$4" = '{remote_path}' ] || exit 92
  cp "$(dirname "$0")/source trace.pftrace" "$5"
  printf '1 file pulled\n'
else
  exit 93
fi
"#,
        );
        std::fs::write(&executable, script).unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        let mut adb = AdbRunner::new(executable, "serial".to_owned(), CancellationToken::new());
        adb.remote = remote;
        let destination = root.0.join("pulled trace.pftrace");
        assert_eq!(adb.transfer_trace(&destination).unwrap(), size);
        let mut pulled = std::fs::File::open(destination).unwrap();
        let mut prefix = [0; 5];
        pulled.read_exact(&mut prefix).unwrap();
        assert_eq!(&prefix, b"start");
        pulled.seek(SeekFrom::End(-3)).unwrap();
        let mut suffix = [0; 3];
        pulled.read_exact(&mut suffix).unwrap();
        assert_eq!(&suffix, b"end");
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn failed_trace_pull_keeps_the_device_trace_for_recovery() {
        use std::os::unix::fs::PermissionsExt;

        let root = TransferTestDirectory::new();
        let executable = root.0.join("fake adb");
        std::fs::write(
            &executable,
            r#"#!/bin/sh
set -eu
printf '%s\n' "$*" >> "$(dirname "$0")/calls.log"
if [ "$3" = 'shell' ] && [ "$#" -eq 4 ]; then
  printf 'stopped\n'
elif [ "$3" = 'shell' ] && [ "$4" = 'stat' ]; then
  printf '4\n'
elif [ "$3" = 'pull' ]; then
  printf 'xx' > "$5"
  exit 1
else
  exit 93
fi
"#,
        )
        .unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        let result = complete_capture_blocking(
            ActiveCapture {
                executable,
                serial: "serial".to_owned(),
                pid: 1234,
                config: CaptureConfig::new(CaptureTemplate::SystemOverview),
                started_unix_ms: 0,
                device_model: "fixture".to_owned(),
                android_sdk: 34,
                capability_query_failed: false,
                automatic_delay: None,
                remote: RemoteCapturePaths::new(),
                startup_unconfirmed: false,
                stop_confirmed: Arc::new(AtomicBool::new(false)),
                stop_signal_sent: Arc::new(AtomicBool::new(false)),
                manual_stop_requested: Arc::new(AtomicBool::new(false)),
            },
            false,
            root.0.clone(),
            CancellationToken::new(),
            Language::English,
        );
        let error = result.unwrap_err();
        assert!(error.contains("Unable to pull"));
        assert!(error.contains("Device cleanup was skipped"));
        assert!(error.contains("/data/misc/perfetto-traces/aps-"));
        let calls = std::fs::read_to_string(root.0.join("calls.log")).unwrap();
        assert!(calls.contains(" pull "));
        assert!(!calls.contains(" rm "));
        assert_eq!(
            std::fs::read_dir(root.0.join(CAPTURE_CONFIG_PATH_SEGMENT))
                .unwrap()
                .count(),
            0,
        );
    }

    #[::core::prelude::v1::test]
    #[ignore = "requires an explicitly supplied device, ADB and recovery directory; never records or deletes the device trace"]
    fn recover_existing_device_trace_through_the_production_transfer() {
        let executable = PathBuf::from(std::env::var_os("APS_TEST_ADB").expect("APS_TEST_ADB"));
        let serial = std::env::var("APS_TEST_TRACE_SERIAL").expect("APS_TEST_TRACE_SERIAL");
        let directory = PathBuf::from(
            std::env::var_os("APS_TEST_TRACE_RECOVERY_DIR").expect("APS_TEST_TRACE_RECOVERY_DIR"),
        );
        assert!(executable.is_absolute() && directory.is_absolute() && directory.is_dir());
        let destination = directory.join(format!("{}.pftrace", unique_id("recovered")));
        let mut adb = AdbRunner::new(executable, serial, CancellationToken::new());
        adb.remote.trace = DEVICE_TRACE_PATH.to_owned();
        let bytes = adb.transfer_trace(&destination).unwrap();
        println!(
            "Production transfer recovered {bytes} bytes to {}",
            destination.display()
        );
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn local_storage_failure_keeps_the_unique_remote_recovery_path() {
        use std::os::unix::fs::PermissionsExt;
        let root = TransferTestDirectory::new();
        let executable = root.0.join("fake adb");
        std::fs::write(&executable, "#!/bin/sh\nprintf 'stopped\\n'\n").unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        std::fs::write(root.0.join(CAPTURE_CONFIG_PATH_SEGMENT), b"existing file").unwrap();
        let remote = RemoteCapturePaths::new();
        let expected_path = remote.trace.clone();
        let result = complete_capture_blocking(
            ActiveCapture {
                executable,
                serial: "serial".to_owned(),
                pid: 1234,
                config: CaptureConfig::new(CaptureTemplate::SystemOverview),
                started_unix_ms: 0,
                device_model: "fixture".to_owned(),
                android_sdk: 34,
                capability_query_failed: false,
                automatic_delay: None,
                remote,
                startup_unconfirmed: false,
                stop_confirmed: Arc::new(AtomicBool::new(false)),
                stop_signal_sent: Arc::new(AtomicBool::new(false)),
                manual_stop_requested: Arc::new(AtomicBool::new(false)),
            },
            false,
            root.0.clone(),
            CancellationToken::new(),
            Language::English,
        );
        let error = result.unwrap_err();
        assert!(error.contains("artifact directory is unavailable"));
        assert!(error.contains(&expected_path));
        assert!(error.contains("device serial"));
        assert_eq!(
            std::fs::read(root.0.join(CAPTURE_CONFIG_PATH_SEGMENT)).unwrap(),
            b"existing file"
        );
    }

    #[::core::prelude::v1::test]
    #[ignore = "requires explicit APS_TEST_CAPTURE_SMOKE=1 and device/ADB/directory; records only a one-second scheduling trace at unique paths"]
    fn device_capture_completes_through_the_production_stop_and_transfer() {
        assert_eq!(std::env::var("APS_TEST_CAPTURE_SMOKE").as_deref(), Ok("1"));
        let serial = std::env::var("APS_TEST_TRACE_SERIAL").expect("APS_TEST_TRACE_SERIAL");
        let directory = PathBuf::from(
            std::env::var_os("APS_TEST_TRACE_RECOVERY_DIR").expect("APS_TEST_TRACE_RECOVERY_DIR"),
        );
        assert!(directory.is_absolute() && directory.is_dir());
        let mut config = CaptureConfig::new(CaptureTemplate::SystemOverview);
        config.duration_seconds = 1;
        config.buffer_size_kb = 1024;
        config.enabled_probes = Some([Probe::CpuScheduling].into_iter().collect());
        config.atrace_category_selection = Some(BTreeSet::new());
        let custom_capture = std::env::var("APS_TEST_CAPTURE_CUSTOM").as_deref() == Ok("1");
        let full_capture = std::env::var("APS_TEST_CAPTURE_FULL").as_deref() == Ok("1");
        assert!(!(custom_capture && full_capture));
        if full_capture {
            // Exercise FullCapture launch policy with bounded scheduling only,
            // not every supported/high-overhead producer on a user's device.
            config.template = CaptureTemplate::FullCapture;
        }
        let delayed_start_ms: u32 = std::env::var("APS_TEST_CAPTURE_DELAY_MS")
            .ok()
            .map(|value| value.parse().expect("numeric smoke start delay"))
            .unwrap_or(0);
        assert!(delayed_start_ms <= 7_000);
        if custom_capture {
            let mut text = "buffers { size_kb: 1024 } duration_ms: 1000 data_sources { config { name: \"linux.ftrace\" ftrace_config { ftrace_events: \"sched/sched_switch\" } } }".to_owned();
            if delayed_start_ms > 0 {
                text.push_str(&format!(" cmd_trace_start_delay {{ min_delay_ms: {delayed_start_ms} max_delay_ms: {delayed_start_ms} }}"));
            }
            config = custom(&text);
        }
        let cancel = CancellationToken::new();
        let launched_at = std::time::Instant::now();
        let mut active =
            capture_start_blocking(serial, config, directory.clone(), cancel.clone(), false)
                .unwrap();
        let startup_elapsed = launched_at.elapsed();
        println!(
            "Owned smoke process={}, trace={}, startup_ms={}",
            active.pid,
            active.remote.trace,
            startup_elapsed.as_millis()
        );
        let supervised = custom_capture || full_capture;
        assert_eq!(active.startup_unconfirmed, supervised);
        let manual_stop = std::env::var("APS_TEST_CAPTURE_MANUAL").as_deref() == Ok("1");
        if supervised && !manual_stop {
            let mut adb = AdbRunner::new(
                active.executable.clone(),
                active.serial.clone(),
                cancel.clone(),
            );
            adb.remote = active.remote.clone();
            for _ in 0..60 {
                if adb.remote_process_stopped(active.pid).unwrap() {
                    active.stop_confirmed.store(true, Ordering::Release);
                    break;
                }
                std::thread::sleep(Duration::from_millis(250));
            }
            assert!(
                active.stop_confirmed.load(Ordering::Acquire),
                "bounded smoke trace must finish naturally"
            );
        }
        let output =
            complete_capture_blocking(active, manual_stop, directory, cancel, Language::English)
                .unwrap();
        assert!(output.trace_size_bytes > 0);
        if supervised {
            assert!(launched_at.elapsed() >= Duration::from_millis(u64::from(delayed_start_ms)));
            assert_eq!(
                output.integrity.status,
                Language::English.text(TextKey::TraceCaptureIntegrityUnverified)
            );
            assert!(
                output
                    .integrity
                    .messages
                    .iter()
                    .any(|message| message == STARTUP_UNCONFIRMED)
            );
        }
        assert_eq!(
            std::fs::metadata(&output.trace_path).unwrap().len(),
            output.trace_size_bytes
        );
        println!(
            "Production capture recovered {} bytes to {}",
            output.trace_size_bytes,
            output.trace_path.display()
        );
    }

    #[::core::prelude::v1::test]
    #[ignore = "requires explicit APS_TEST_CAPTURE_SMOKE=1 and device/directory; submits an unknown schema field, never starts recording, retains diagnostics"]
    fn device_custom_parse_failure_retains_the_actual_error_and_artifacts() {
        assert_eq!(std::env::var("APS_TEST_CAPTURE_SMOKE").as_deref(), Ok("1"));
        let serial = std::env::var("APS_TEST_TRACE_SERIAL").expect("APS_TEST_TRACE_SERIAL");
        let directory = PathBuf::from(
            std::env::var_os("APS_TEST_TRACE_RECOVERY_DIR").expect("APS_TEST_TRACE_RECOVERY_DIR"),
        );
        assert!(directory.is_absolute() && directory.is_dir());
        let config = custom(
            "buffers { size_kb: 1024 } duration_ms: 1000 data_sources { config { name: \"linux.ftrace\" ftrace_config { aps_invalid_field_for_diagnostic_smoke: true } } }",
        );
        let cancel = CancellationToken::new();
        let mut active =
            capture_start_blocking(serial, config, directory.clone(), cancel.clone(), false)
                .unwrap();
        let mut adb = AdbRunner::new(
            active.executable.clone(),
            active.serial.clone(),
            cancel.clone(),
        );
        adb.remote = active.remote.clone();
        assert!(adb.wait_for_remote_stop(active.pid).unwrap());
        active.stop_confirmed.store(true, Ordering::Release);
        let error = complete_capture_blocking(active, false, directory, cancel, Language::English)
            .unwrap_err();
        assert!(error.contains("exit code 1"), "{error}");
        assert!(
            error.contains("aps_invalid_field_for_diagnostic_smoke"),
            "{error}"
        );
        assert!(error.contains(&adb.remote.log), "{error}");
        assert!(error.contains("Device cleanup was skipped"));
        let retained = adb
            .shell(
                &format!(
                    "test -s {} && test -s {} && test -s {}",
                    adb.remote.config, adb.remote.log, adb.remote.exit_status
                ),
                ADB_SHORT_TIMEOUT,
            )
            .unwrap();
        assert!(retained.status.success());
        println!("Production failure retained diagnostics: {error}");
    }

    #[::core::prelude::v1::test]
    fn stop_probe_requires_explicit_successful_absence_not_just_an_adb_exit() {
        #[cfg(unix)]
        fn exit_status(code: i32) -> std::process::ExitStatus {
            use std::os::unix::process::ExitStatusExt;
            std::process::ExitStatus::from_raw(code << 8)
        }
        #[cfg(windows)]
        fn exit_status(code: i32) -> std::process::ExitStatus {
            use std::os::windows::process::ExitStatusExt;
            std::process::ExitStatus::from_raw(code as u32)
        }
        for (text, code, expected) in [
            ("stopped\n", 0, Some(true)),
            ("running\n", 0, Some(false)),
            ("stopped\r\n", 0, Some(true)),
            ("stopped\n", 1, None),
            ("", 1, None),
            ("unknown\n", 0, None),
        ] {
            let output = ProcessOutput {
                status: exit_status(code),
                stdout: text.as_bytes().to_vec(),
                stderr: Vec::new(),
            };
            assert_eq!(confirmed_remote_stop(&output).ok(), expected);
        }
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn stop_probe_transport_failure_never_authorizes_a_pull() {
        use std::os::unix::fs::PermissionsExt;
        let root = TransferTestDirectory::new();
        let executable = root.0.join("fake adb");
        std::fs::write(
            &executable,
            "#!/bin/sh\nprintf 'device offline' >&2\nexit 1\n",
        )
        .unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        let adb = AdbRunner::new(executable, "serial".to_owned(), CancellationToken::new());
        assert!(
            adb.wait_for_remote_stop(1234)
                .unwrap_err()
                .contains("Unable to confirm")
        );
        assert!(adb.kill("-TERM", 1234).is_err());
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn stop_probe_does_not_require_the_devices_signal_zero_permission() {
        use std::os::unix::fs::PermissionsExt;
        let root = TransferTestDirectory::new();
        let executable = root.0.join("fake adb");
        std::fs::write(
            &executable,
            r#"#!/bin/sh
set -eu
printf '%s\n' "$*" >> "$(dirname "$0")/calls.log"
case "$4" in
  *'kill -0'*) printf 'Permission denied\n' >&2; exit 1 ;;
  *'/proc/1234'*)
    if [ -e "$(dirname "$0")/polled" ]; then printf 'stopped\n'; else
      touch "$(dirname "$0")/polled"
      printf 'running\n'
    fi ;;
  *) exit 1 ;;
esac
"#,
        )
        .unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        let adb = AdbRunner::new(executable, "serial".to_owned(), CancellationToken::new());
        assert!(adb.wait_for_remote_stop(1234).unwrap());
        let calls = std::fs::read_to_string(root.0.join("calls.log")).unwrap();
        assert_eq!(calls.lines().count(), 2);
        assert!(!calls.contains("kill -0"));
    }

    #[cfg(unix)]
    #[::core::prelude::v1::test]
    fn remote_trace_change_is_reported_with_sizes_and_rejected() {
        use std::os::unix::fs::PermissionsExt;
        let root = TransferTestDirectory::new();
        let executable = root.0.join("fake adb");
        std::fs::write(
            &executable,
            r#"#!/bin/sh
set -eu
if [ "$3" = 'shell' ]; then
  if [ -e "$(dirname "$0")/pulled" ]; then printf '5\n'; else printf '4\n'; fi
elif [ "$3" = 'pull' ]; then
  printf '12345' > "$5"
  touch "$(dirname "$0")/pulled"
else
  exit 1
fi
"#,
        )
        .unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        let adb = AdbRunner::new(executable, "serial".to_owned(), CancellationToken::new());
        let destination = root.0.join("changed.pftrace");
        let error = adb.transfer_trace(&destination).unwrap_err();
        assert!(error.contains("before=4 bytes, after=5 bytes"));
        assert!(!destination.exists());
    }

    fn full_capabilities(categories: &[&str]) -> DeviceCapabilities {
        DeviceCapabilities {
            android_sdk: 34,
            build_type: "userdebug".to_owned(),
            data_source_names: Probe::ALL
                .into_iter()
                .filter_map(Probe::required_data_source)
                .chain(["android.packages_list", "vendor.unknown_producer"])
                .map(str::to_owned)
                .collect(),
            atrace_categories: categories
                .iter()
                .map(|name| ((*name).to_owned(), format!("Device {name}")))
                .collect(),
            ..DeviceCapabilities::default()
        }
    }

    fn selected_config(capture: &Capture, target: Option<&str>) -> CaptureConfig {
        let mut config = CaptureConfig::new(capture.selected_template);
        config.target_package = target.map(str::to_owned);
        capture.apply_selection(&mut config);
        config
    }

    #[::core::prelude::v1::test]
    fn full_capture_has_localized_labels_and_warning_before_custom() {
        assert_eq!(CaptureTemplate::ALL[5], CaptureTemplate::FullCapture);
        assert_eq!(CaptureTemplate::ALL[6], CaptureTemplate::Custom);
        for language in [Language::English, Language::SimplifiedChinese] {
            assert!(
                !language
                    .text(CaptureTemplate::FullCapture.label())
                    .is_empty()
            );
            assert!(
                !language
                    .text(CaptureTemplate::FullCapture.description())
                    .is_empty()
            );
            assert!(!language.text(TextKey::TraceCaptureFullWarning).is_empty());
        }
    }

    #[::core::prelude::v1::test]
    fn full_capture_covers_only_supported_probes_and_reported_categories() {
        let mut capture = Capture::new(None);
        capture.apply_template(CaptureTemplate::FullCapture, BTreeSet::new());
        let all = Probe::ALL.into_iter().collect::<BTreeSet<_>>();
        assert_eq!(CaptureTemplate::FullCapture.default_probes(), all);
        assert_eq!(capture.enabled_probes, all);
        assert!(capture.selected_categories.is_empty());
        assert!(
            selected_config(&capture, None)
                .enabled_probes
                .unwrap()
                .is_empty()
        );
        let categories = ["gfx", "input", "vendor_category"];
        assert!(capture.apply_capabilities(0, full_capabilities(&categories)));
        let config = selected_config(&capture, Some("com.example.app"));
        assert_eq!(config.enabled_probes.as_ref().unwrap(), &all);
        assert_eq!(
            capture.selected_categories,
            categories.into_iter().map(str::to_owned).collect()
        );
        let text = build_config_text(&config);
        for probe in Probe::ALL {
            assert!(
                text.contains(&format!(
                    "name: \"{}\"",
                    probe.required_data_source().unwrap()
                )),
                "{probe:?}"
            );
            for event in ftrace_events(probe) {
                assert!(
                    text.contains(&format!("ftrace_events: \"{event}\"")),
                    "{probe:?}: {event}"
                );
            }
        }
        for category in categories {
            assert!(text.contains(&format!("atrace_categories: \"{category}\"")));
        }
        assert!(!text.contains("vendor.unknown_producer"));
        assert!(matches!(capture.state, CaptureState::Idle));
        assert!(capture.active.is_none());
    }

    #[::core::prelude::v1::test]
    fn full_capture_selects_categories_on_selection_and_each_new_snapshot() {
        let mut capture = Capture::new(None);
        assert!(capture.apply_capabilities(0, full_capabilities(&["gfx", "view"])));
        let original = capture.selected_categories.clone();
        assert!(original.contains("am"));
        capture.apply_template(CaptureTemplate::FullCapture, BTreeSet::new());
        assert_eq!(capture.selected_categories.len(), 2);
        assert!(!capture.selected_categories.contains("am"));
        capture.toggle_category("view", false);
        capture.toggle_probe(Probe::AndroidLog, false);
        capture.apply_template(CaptureTemplate::FullCapture, BTreeSet::new());
        let config = selected_config(&capture, None);
        assert!(!config.atrace_category_selection.unwrap().contains("view"));
        assert!(!config.enabled_probes.unwrap().contains(&Probe::AndroidLog));
        assert!(capture.apply_capabilities(0, full_capabilities(&["view", "input"])));
        assert_eq!(
            capture.selected_categories,
            ["view".to_owned(), "input".to_owned()]
                .into_iter()
                .collect()
        );
        assert!(!capture.enabled_probes.contains(&Probe::AndroidLog));
    }

    #[::core::prelude::v1::test]
    fn full_capture_device_switch_clears_categories_and_rejects_stale_snapshots() {
        let mut capture = Capture::new(None);
        capture.set_selected_serial(Some("first".to_owned()));
        let generation = capture.capability_generation;
        capture.apply_template(CaptureTemplate::FullCapture, BTreeSet::new());
        assert!(capture.apply_capabilities(generation, full_capabilities(&["first_only"])));
        capture.set_selected_serial(Some("second".to_owned()));
        assert!(capture.selected_categories.is_empty());
        assert!(capture.capabilities.is_none());
        let config = selected_config(&capture, None);
        assert!(config.enabled_probes.unwrap().is_empty());
        assert!(config.atrace_category_selection.unwrap().is_empty());
        assert!(!capture.apply_capabilities(generation, full_capabilities(&["stale"])));
        assert!(capture.apply_capabilities(
            capture.capability_generation,
            full_capabilities(&["second_only"])
        ));
        let text = build_config_text(&selected_config(&capture, None));
        assert!(text.contains("atrace_categories: \"second_only\""));
        assert!(!text.contains("first_only"));
        assert!(!text.contains("stale"));
    }

    #[::core::prelude::v1::test]
    fn full_capture_filters_unsupported_and_target_dependent_probes() {
        let mut capture = Capture::new(None);
        capture.apply_template(CaptureTemplate::FullCapture, BTreeSet::new());
        let mut capabilities = full_capabilities(&["gfx"]);
        capabilities.android_sdk = 28;
        capabilities.build_type = "user".to_owned();
        capabilities.data_source_names.remove("gpu.renderstages");
        assert!(capture.apply_capabilities(0, capabilities));
        let config = selected_config(&capture, Some("com.example.app"));
        let probes = config.enabled_probes.as_ref().unwrap();
        for probe in [
            Probe::NativeHeap,
            Probe::JavaHeapDump,
            Probe::Syscalls,
            Probe::FrameTimeline,
            Probe::GpuMemory,
            Probe::GpuWorkPeriod,
            Probe::NetworkPackets,
            Probe::GameInterventions,
            Probe::GpuRenderStages,
        ] {
            assert!(!probes.contains(&probe), "{probe:?}");
        }
        assert!(probes.contains(&Probe::CpuScheduling));
        assert!(capture.apply_capabilities(0, full_capabilities(&["gfx"])));
        let config = selected_config(&capture, None);
        let probes = config.enabled_probes.as_ref().unwrap();
        assert!(!probes.contains(&Probe::NativeHeap));
        assert!(!probes.contains(&Probe::JavaHeapDump));
        let text = build_config_text(&config);
        assert!(!text.contains("heapprofd_config"));
        assert!(!text.contains("java_hprof_config"));
        for target in ["*", "com.example.*", " ", "com..example"] {
            let config = selected_config(&capture, Some(target));
            assert!(config.validate().is_err(), "{target}");
            assert!(
                !config
                    .enabled_probes
                    .as_ref()
                    .unwrap()
                    .contains(&Probe::NativeHeap)
            );
            assert!(!build_config_text(&config).contains("process_cmdline:"));
        }
    }

    #[::core::prelude::v1::test]
    fn full_capture_query_failure_and_missing_capabilities_fail_closed() {
        let mut capture = Capture::new(None);
        capture.apply_template(CaptureTemplate::FullCapture, BTreeSet::new());
        let mut capabilities = full_capabilities(&["stale_category"]);
        capabilities.category_query_error = Some("permission denied".to_owned());
        assert!(capture.apply_capabilities(0, capabilities.clone()));
        capture.select_all_categories(true);
        capture.toggle_category("stale_category", true);
        assert!(capture.selected_categories.is_empty());
        let mut config = selected_config(&capture, None);
        config.additional_categories = vec!["stale_category".to_owned(), "unknown".to_owned()];
        capture.apply_selection(&mut config);
        assert!(!build_config_text(&config).contains("atrace_categories:"));
        capabilities.query_error = Some("query failed".to_owned());
        assert!(capture.apply_capabilities(0, capabilities));
        assert!(
            selected_config(&capture, None)
                .enabled_probes
                .unwrap()
                .is_empty()
        );
        assert!(capture.apply_capabilities(0, DeviceCapabilities::default()));
        assert!(
            selected_config(&capture, None)
                .enabled_probes
                .unwrap()
                .is_empty()
        );
        let mut config = CaptureConfig::new(CaptureTemplate::FullCapture);
        config.target_package = Some("com.example.app".to_owned());
        assert!(!build_config_text(&config).contains("atrace_apps:"));
    }

    #[::core::prelude::v1::test]
    fn full_capture_wildcard_is_atrace_only_and_explicit_targets_stay_scoped() {
        for template in CaptureTemplate::ALL {
            if template != CaptureTemplate::FullCapture {
                assert!(
                    !build_config_text(&CaptureConfig::new(template))
                        .contains("atrace_apps: \"*\"")
                );
            }
        }
        let mut capture = Capture::new(None);
        capture.apply_template(CaptureTemplate::FullCapture, BTreeSet::new());
        assert!(capture.apply_capabilities(0, full_capabilities(&["gfx"])));
        let text = build_config_text(&selected_config(&capture, None));
        assert!(text.contains("atrace_apps: \"*\""));
        assert!(!text.contains("process_cmdline:"));
        let text = build_config_text(&selected_config(&capture, Some("com.example.app")));
        assert!(text.contains("atrace_apps: \"com.example.app\""));
        assert!(!text.contains("atrace_apps: \"*\""));
        assert!(!text.contains("atrace_apps: \"lmkd\""));
        assert_eq!(
            text.matches("process_cmdline: \"com.example.app\"").count(),
            2
        );
        capture.toggle_probe(Probe::Atrace, false);
        let text = build_config_text(&selected_config(&capture, None));
        assert!(!text.contains("atrace_apps:"));
        assert!(!text.contains("atrace_categories:"));
        assert!(text.contains("ftrace_events: \"sched/sched_switch\""));
        capture.toggle_probe(Probe::Atrace, true);
        capture.select_all_categories(false);
        let text = build_config_text(&selected_config(&capture, None));
        assert!(text.contains("atrace_apps: \"*\""));
        assert!(!text.contains("atrace_categories:"));
    }

    #[::core::prelude::v1::test]
    fn full_capture_switching_to_and_from_custom_preserves_the_draft() {
        let mut capture = Capture::new(None);
        let draft = "buffers { size_kb: 1024 } # unsaved draft\nduration_ms: 1234";
        capture.config_draft = draft.to_owned();
        capture.pending_editor_text = Some(draft.to_owned());
        for template in [
            CaptureTemplate::FullCapture,
            CaptureTemplate::Custom,
            CaptureTemplate::AppPerformance,
            CaptureTemplate::Custom,
        ] {
            capture.apply_template(template, custom_category_selection(None, draft));
            assert_eq!(capture.config_draft, draft);
            assert_eq!(capture.pending_editor_text.as_deref(), Some(draft));
        }
        assert_eq!(build_config_text(&custom(&capture.config_draft)), draft);
    }

    #[::core::prelude::v1::test]
    fn capability_arrival_preserves_non_full_config_generation() {
        for template in CaptureTemplate::ALL {
            if template == CaptureTemplate::FullCapture {
                continue;
            }
            let mut capture = Capture::new(None);
            capture.apply_template(template, BTreeSet::new());
            let generation = capture.config_generation;
            let categories = capture.selected_categories.clone();
            assert!(capture.apply_capabilities(0, full_capabilities(&["gfx"])));
            assert_eq!(capture.config_generation, generation, "{template:?}");
            assert_eq!(capture.selected_categories, categories, "{template:?}");
            assert!(capture.preflight_dirty);
        }
    }

    #[::core::prelude::v1::test]
    fn full_capture_preflight_blocks_unestablished_coverage_only_in_full_mode() {
        let mut source_failure = full_capabilities(&["gfx"]);
        source_failure.query_error = Some("permission denied".to_owned());
        let mut category_failure = full_capabilities(&["gfx"]);
        category_failure.category_query_error = Some("permission denied".to_owned());
        for capabilities in [
            None,
            Some(DeviceCapabilities::default()),
            Some(source_failure),
            Some(category_failure),
        ] {
            let mut capture = Capture::new(None);
            capture.apply_template(CaptureTemplate::FullCapture, BTreeSet::new());
            if let Some(capabilities) = capabilities {
                assert!(capture.apply_capabilities(0, capabilities));
            }
            let config = selected_config(&capture, None);
            let report = capture.inspect_config(&config);
            assert!(!report.can_start());
            assert!(
                report
                    .errors
                    .iter()
                    .any(|error| error.starts_with("Full capture"))
            );
            for template in [CaptureTemplate::AppPerformance, CaptureTemplate::Custom] {
                let config = if template == CaptureTemplate::Custom {
                    custom("buffers { size_kb: 1024 } duration_ms: 1000")
                } else {
                    CaptureConfig::new(template)
                };
                assert!(
                    !capture
                        .inspect_config(&config)
                        .errors
                        .iter()
                        .any(|error| error.starts_with("Full capture"))
                );
            }
        }
        let mut capture = Capture::new(None);
        capture.apply_template(CaptureTemplate::FullCapture, BTreeSet::new());
        assert!(capture.apply_capabilities(0, full_capabilities(&["gfx"])));
        assert!(
            capture
                .inspect_config(&selected_config(&capture, None))
                .can_start()
        );
        let mut category_failure = full_capabilities(&["gfx"]);
        category_failure.category_query_error = Some("permission denied".to_owned());
        assert!(capture.apply_capabilities(0, category_failure));
        capture.toggle_probe(Probe::Atrace, false);
        assert!(
            capture
                .inspect_config(&selected_config(&capture, None))
                .can_start()
        );
    }

    #[::core::prelude::v1::test]
    fn original_template_defaults_and_category_configs_remain_unchanged() {
        use Probe::*;
        let cases: &[(CaptureTemplate, &[Probe], &[&str])] = &[
            (
                CaptureTemplate::SystemOverview,
                &[
                    CpuUsage,
                    CpuScheduling,
                    CpuFrequency,
                    KernelMeminfo,
                    Vmstat,
                    ProcessStats,
                    FilesystemIo,
                    PageFaults,
                    ArtRuntime,
                    Thermal,
                    Atrace,
                    FrameTimeline,
                ],
                &[
                    "am",
                    "binder_driver",
                    "dalvik",
                    "database",
                    "gfx",
                    "view",
                    "wm",
                    "power",
                ],
            ),
            (
                CaptureTemplate::AppPerformance,
                &[
                    CpuUsage,
                    CpuScheduling,
                    ProcessStats,
                    FilesystemIo,
                    PageFaults,
                    ArtRuntime,
                    Atrace,
                    FrameTimeline,
                ],
                &[
                    "am",
                    "binder_driver",
                    "dalvik",
                    "database",
                    "gfx",
                    "view",
                    "wm",
                ],
            ),
            (
                CaptureTemplate::GfxPipeline,
                &[
                    CpuScheduling,
                    CpuFrequency,
                    GpuFrequency,
                    Atrace,
                    FrameTimeline,
                ],
                &["gfx", "view", "wm"],
            ),
            (
                CaptureTemplate::InputLatency,
                &[CpuScheduling, Atrace],
                &["input", "view", "wm"],
            ),
            (
                CaptureTemplate::MemoryProfile,
                &[
                    NativeHeap,
                    KernelMeminfo,
                    Vmstat,
                    HighFrequencyMemory,
                    LowMemoryKiller,
                    ProcessStats,
                    PageFaults,
                    ArtRuntime,
                    Atrace,
                ],
                &["am", "dalvik"],
            ),
            (CaptureTemplate::Custom, &[], &[]),
        ];
        for &(template, probes, categories) in cases {
            assert_eq!(
                template.default_probes(),
                probes.iter().copied().collect(),
                "{template:?}"
            );
            assert_eq!(atrace_categories(template), categories, "{template:?}");
            let config = CaptureConfig::new(template);
            assert!(config.enabled_probes.is_none());
            assert!(config.atrace_category_selection.is_none());
            let text = build_config_text(&config);
            let report = super::super::capture_config::preflight(&text, None, None);
            assert_eq!(
                report
                    .atrace_categories
                    .into_iter()
                    .collect::<BTreeSet<_>>(),
                categories
                    .iter()
                    .map(|category| (*category).to_owned())
                    .collect()
            );
            assert!(!text.contains("atrace_apps: \"*\""));
            assert_eq!(
                text.contains("atrace_apps: \"lmkd\""),
                template == CaptureTemplate::MemoryProfile
            );
        }
    }

    #[::core::prelude::v1::test]
    fn shared_devices_populate_the_selected_target_without_opening_capture() {
        let devices = aps_adb::parse_devices("serial device model:Pixel_8\n").unwrap();
        let mut capture = Capture::new(None);
        capture.set_selected_serial(Some("serial".into()));
        capture.refresh_in_progress = true;
        let refresh_generation = capture.device_generation;
        capture.publish_devices(&devices);
        assert!(capture.devices_loaded());
        assert!(capture.device_ready());
        assert_eq!(capture.selected_serial.as_deref(), Some("serial"));
        assert_eq!(capture.devices()[0].display_name, devices[0].display_name());
        assert_ne!(capture.device_generation, refresh_generation);
        assert!(!capture.refresh_in_progress());
        assert!(matches!(capture.state, CaptureState::Idle));
    }

    #[::core::prelude::v1::test]
    fn unchanged_shared_devices_preserve_cached_and_pending_capabilities() {
        let devices = aps_adb::parse_devices("serial device model:Pixel_8\n").unwrap();
        let mut capture = Capture::new(None);
        capture.publish_devices(&devices);
        capture.capabilities = Some(DeviceCapabilities::default());
        let generation = capture.capability_generation;
        capture.publish_devices(&devices);
        assert!(capture.capabilities().is_some());
        assert_eq!(capture.capability_generation, generation);
        capture.capabilities = None;
        capture.capabilities_in_progress = true;
        capture.publish_devices(&devices);
        assert!(capture.capabilities_in_progress());
        assert_eq!(capture.capability_generation, generation);
    }

    #[::core::prelude::v1::test]
    fn disconnected_and_cleared_targets_invalidate_old_capability_results() {
        let devices = aps_adb::parse_devices("serial device model:Pixel_8\n").unwrap();
        let offline = aps_adb::parse_devices("serial offline model:Pixel_8\n").unwrap();
        let mut capture = Capture::new(None);
        capture.publish_devices(&devices);
        capture.capabilities_in_progress = true;
        let generation = capture.capability_generation;
        capture.publish_devices(&offline);
        assert!(!capture.device_ready());
        assert_eq!(capture.selected_serial.as_deref(), None);
        assert!(!capture.capabilities_in_progress());
        assert_ne!(capture.capability_generation, generation);
        capture.publish_devices(&devices);
        assert!(capture.device_ready());
        capture.capabilities = Some(DeviceCapabilities::default());
        let generation = capture.capability_generation;
        capture.set_selected_serial(None);
        assert_eq!(capture.selected_serial.as_deref(), None);
        assert!(capture.capabilities().is_none());
        assert_ne!(capture.capability_generation, generation);
        capture.publish_devices(&[]);
        assert!(capture.devices().is_empty());
        assert!(!capture.device_ready());
    }

    fn custom(text: &str) -> CaptureConfig {
        let mut config = CaptureConfig::new(CaptureTemplate::Custom);
        config.custom_config_text = Some(text.to_owned());
        config
    }

    #[::core::prelude::v1::test]
    fn app_template_emits_ftrace_atrace_and_frame_timeline_sources() {
        let mut config = CaptureConfig::new(CaptureTemplate::AppPerformance);
        config.target_package = Some("com.example.\"debug\\app".to_owned());
        config.duration_seconds = 15;
        config.buffer_size_kb = 8_192;
        config.additional_categories =
            vec!["dalvik".to_owned(), "sched/sched_process_exit".to_owned()];
        let text = build_config_text(&config);
        assert!(text.contains("duration_ms: 15000"));
        assert!(text.contains("size_kb: 8192"));
        assert!(text.contains("name: \"linux.ftrace\""));
        assert!(text.contains("atrace_categories: \"dalvik\""));
        assert!(text.contains("ftrace_events: \"sched/sched_process_exit\""));
        assert!(text.contains("atrace_apps: \"com.example.\\\"debug\\\\app\""));
        assert!(text.contains("name: \"android.surfaceflinger.frametimeline\""));
        assert!(text.contains("name: \"linux.process_stats\""));
        assert!(!text.contains("android.atrace"));
        assert!(!text.contains("android_atrace_config"));
    }

    #[::core::prelude::v1::test]
    fn memory_template_adds_polled_memory_and_targeted_native_heap_sources() {
        let mut config = CaptureConfig::new(CaptureTemplate::MemoryProfile);
        config.target_package = Some("com.example.app".to_owned());
        let text = build_config_text(&config);
        assert!(text.contains("proc_stats_poll_ms: 1000"));
        assert!(text.contains("name: \"linux.sys_stats\""));
        assert!(text.contains("meminfo_period_ms: 1000"));
        assert!(text.contains("name: \"android.heapprofd\""));
        assert!(text.contains("process_cmdline: \"com.example.app\""));
    }

    #[::core::prelude::v1::test]
    fn input_latency_template_uses_ftrace_atrace_categories() {
        let text = build_config_text(&CaptureConfig::new(CaptureTemplate::InputLatency));
        assert!(text.contains("atrace_categories: \"input\""));
        assert!(text.contains("atrace_categories: \"view\""));
        assert!(text.contains("atrace_categories: \"wm\""));
        assert!(!text.contains("android.input.inputevent"));
        assert!(!text.contains("android_input_event_config"));
    }

    #[::core::prelude::v1::test]
    fn custom_template_is_passed_through_unchanged() {
        let text = "buffers { size_kb: 1024 }\nduration_ms: 1000";
        assert_eq!(build_config_text(&custom(text)), text);
    }

    #[::core::prelude::v1::test]
    fn custom_completion_follows_the_config_duration() {
        let config = custom("buffers { size_kb: 1024 }\nduration_ms: 60000");
        assert_eq!(
            automatic_completion_delay(&config),
            Some(Duration::from_secs(60))
        );
    }

    #[::core::prelude::v1::test]
    fn custom_capture_without_duration_is_stopped_by_the_user() {
        let config = custom("buffers { size_kb: 1024 }");
        assert_eq!(automatic_completion_delay(&config), None);
    }

    #[::core::prelude::v1::test]
    fn configured_template_duration_drives_automatic_completion() {
        let mut config = CaptureConfig::new(CaptureTemplate::SystemOverview);
        config.duration_seconds = 12;
        assert_eq!(
            automatic_completion_delay(&config),
            Some(Duration::from_secs(12))
        );
    }

    #[::core::prelude::v1::test]
    fn selected_record_page_probes_compose_into_one_trace_config() {
        let mut config = CaptureConfig::new(CaptureTemplate::SystemOverview);
        config.target_package = Some("com.example.app".to_owned());
        config.enabled_probes = Some(
            [
                Probe::Syscalls,
                Probe::FilesystemIo,
                Probe::PageFaults,
                Probe::ArtRuntime,
                Probe::Thermal,
                Probe::JavaHeapDump,
                Probe::PowerRails,
                Probe::GpuMemory,
                Probe::AndroidLog,
                Probe::NetworkPackets,
                Probe::CallstackSampling,
                Probe::TrackEvents,
            ]
            .into_iter()
            .collect(),
        );
        let text = build_config_text(&config);
        assert!(text.contains("raw_syscalls/sys_enter"));
        assert!(text.contains("block/block_rq_issue"));
        assert!(text.contains("exceptions/page_fault_user"));
        assert!(text.contains("thermal/thermal_temperature"));
        assert!(text.contains("name: \"android.java_hprof\""));
        assert!(text.contains("name: \"android.power\""));
        assert!(text.contains("name: \"android.gpu.memory\""));
        assert!(text.contains("name: \"android.log\""));
        assert!(text.contains("name: \"android.network_packets\""));
        assert!(text.contains("name: \"linux.perf\""));
        assert!(text.contains("name: \"track_event\""));
        assert_eq!(text.matches("name: \"linux.ftrace\"").count(), 1);
    }

    #[::core::prelude::v1::test]
    fn composer_writes_buffers_duration_flush_and_data_sources() {
        let data_sources = vec![DataSource {
            name: "track_event",
            config: String::new(),
        }];
        let text = compose_config(1_500, 4_096, &data_sources, Some(5_000));
        assert_eq!(
            text,
            "buffers: {\n  size_kb: 4096\n  fill_policy: RING_BUFFER\n}\nduration_ms: 1500\nflush_period_ms: 5000\ndata_sources: {\n  config {\n    name: \"track_event\"\n  }\n}\n"
        );
    }

    #[::core::prelude::v1::test]
    fn proto_strings_escape_backslashes_before_quotes() {
        assert_eq!(escape_proto_string("a\"b\\c"), "a\\\"b\\\\c");
    }

    #[::core::prelude::v1::test]
    fn capability_query_output_yields_advertised_data_sources() {
        let output = "Service: 57.2\n\nDATA SOURCES REGISTERED:\n\nNAME                                     PRODUCER                     DETAILS\n===                                      ========                     ========\nlinux.ftrace                             traced_probes (1)             am,gfx\nandroid.heapprofd                        traced_probes (1)\ntrack_event                              app (2)                       gfx\n\nTRACING SESSIONS:\n";
        assert_eq!(
            parse_perfetto_data_source_names(output),
            ["linux.ftrace", "android.heapprofd", "track_event"]
                .into_iter()
                .map(str::to_owned)
                .collect::<BTreeSet<_>>()
        );
    }

    #[::core::prelude::v1::test]
    fn missing_data_source_section_yields_no_names() {
        assert!(parse_perfetto_data_source_names("Service: 57.2\n").is_empty());
    }

    #[::core::prelude::v1::test]
    fn device_categories_are_parsed_without_interpreting_shell_flags_or_diagnostics() {
        let categories = parse_atrace_categories(
            " gfx - Graphics\n sched - Scheduling\nerror: permission denied\ninvalid/category - ignored\n",
        );
        assert_eq!(categories.len(), 2);
        assert_eq!(categories.get("gfx").map(String::as_str), Some("Graphics"));
        assert_eq!(
            categories.get("sched").map(String::as_str),
            Some("Scheduling")
        );
    }

    #[::core::prelude::v1::test]
    fn category_select_all_is_device_scoped_and_does_not_start_capture() {
        let mut capture = Capture::new(None);
        capture.capabilities = Some(DeviceCapabilities {
            atrace_categories: [
                ("gfx".to_owned(), "Graphics".to_owned()),
                ("sched".to_owned(), "Scheduling".to_owned()),
            ]
            .into_iter()
            .collect(),
            ..DeviceCapabilities::default()
        });
        capture.select_all_categories(true);
        assert_eq!(
            capture.selected_categories,
            ["gfx".to_owned(), "sched".to_owned()].into_iter().collect()
        );
        capture.toggle_category("cpu-not-advertised", true);
        assert!(!capture.selected_categories.contains("cpu-not-advertised"));
        assert!(matches!(capture.state(), CaptureState::Idle));
        assert!(capture.active.is_none());
        capture.select_all_categories(false);
        assert!(capture.selected_categories.is_empty());
        capture.state = CaptureState::Recording { started_unix_ms: 1 };
        capture.select_all_categories(true);
        assert!(capture.selected_categories.is_empty());
    }

    #[::core::prelude::v1::test]
    fn explicit_category_selection_never_restores_deselected_template_defaults() {
        let mut config = CaptureConfig::new(CaptureTemplate::SystemOverview);
        config.atrace_category_selection = Some(BTreeSet::new());
        let text = build_config_text(&config);
        assert!(!text.contains("atrace_categories:"));
        assert!(text.contains("sched/sched_switch"));
        config.atrace_category_selection = Some(["view".to_owned()].into_iter().collect());
        let text = build_config_text(&config);
        assert!(text.contains("atrace_categories: \"view\""));
        assert!(!text.contains("atrace_categories: \"gfx\""));
        assert!(!text.contains("atrace_categories: \"am\""));
        assert_eq!(
            super::super::capture_config::preflight(&text, None, None).atrace_categories,
            vec!["view"]
        );
    }

    #[::core::prelude::v1::test]
    fn custom_category_selection_prefers_unsaved_editor_text() {
        let saved = "buffers { size_kb: 1024 } data_sources { config { name: \"linux.ftrace\" ftrace_config { atrace_categories: \"gfx\" } } } duration_ms: 1000";
        let edited = saved.replace("\"gfx\"", "\"view\"");
        assert_eq!(
            custom_category_selection(Some(&edited), saved),
            ["view".to_owned()].into_iter().collect()
        );
        assert_eq!(
            custom_category_selection(None, saved),
            ["gfx".to_owned()].into_iter().collect()
        );
        let source = include_str!("capture.rs");
        let selection = source
            .split("pub(in crate::trace) fn select_template(")
            .nth(1)
            .unwrap()
            .split("pub(in crate::trace) fn toggle_probe(")
            .next()
            .unwrap();
        assert!(selection.contains("inputs.custom_config.read(cx).value()"));
        assert!(
            selection.contains("custom_category_selection(live.as_deref(), &self.config_draft)")
        );
    }

    #[::core::prelude::v1::test]
    fn custom_timer_reads_inline_textproto_without_rewriting_the_config() {
        let config = custom("buffers { size_kb: 1024 } duration_ms: 2500 # stop after 2.5s");
        assert_eq!(
            automatic_completion_delay(&config),
            Some(Duration::from_millis(2500))
        );
        assert_eq!(
            build_config_text(&config),
            config.custom_config_text.unwrap()
        );
    }

    #[::core::prelude::v1::test]
    fn capability_reasons_cover_sdk_build_and_source_availability() {
        let capabilities = DeviceCapabilities {
            android_sdk: 28,
            build_type: "user".to_owned(),
            data_source_names: ["linux.ftrace".to_owned()].into_iter().collect(),
            query_error: None,
            ..DeviceCapabilities::default()
        };
        assert_eq!(
            capabilities.probe_blocker(Probe::NativeHeap),
            Some(ProbeBlocker::AndroidSdk(29))
        );
        assert_eq!(
            capabilities.probe_blocker(Probe::Syscalls),
            Some(ProbeBlocker::DebugBuild)
        );
        // SDK gating takes precedence over source availability (Compose order).
        assert_eq!(
            capabilities.probe_blocker(Probe::FrameTimeline),
            Some(ProbeBlocker::AndroidSdk(31))
        );
        assert_eq!(
            capabilities.probe_blocker(Probe::GpuRenderStages),
            Some(ProbeBlocker::DataSource("gpu.renderstages"))
        );
        assert_eq!(capabilities.probe_blocker(Probe::CpuScheduling), None);
    }

    #[::core::prelude::v1::test]
    fn probe_blockers_render_in_the_selected_language() {
        for language in [Language::English, Language::SimplifiedChinese] {
            for blocker in [
                ProbeBlocker::AndroidSdk(29),
                ProbeBlocker::DebugBuild,
                ProbeBlocker::DataSource("gpu.renderstages"),
                ProbeBlocker::TargetPackage,
            ] {
                assert!(!blocker.message(language).is_empty());
            }
        }
        assert_eq!(
            ProbeBlocker::TargetPackage.message(Language::SimplifiedChinese),
            "需要先填写目标软件包"
        );
        assert!(
            ProbeBlocker::DataSource("gpu.renderstages")
                .message(Language::SimplifiedChinese)
                .contains("gpu.renderstages")
        );
    }

    #[::core::prelude::v1::test]
    fn shared_target_package_fills_only_an_untouched_field() {
        assert!(should_apply_shared_target("", None));
        assert!(should_apply_shared_target(
            "com.example.app",
            Some("com.example.app")
        ));
        assert!(should_apply_shared_target("", Some("com.example.app")));
        assert!(!should_apply_shared_target(
            "com.manual.app",
            Some("com.example.app")
        ));
    }

    #[::core::prelude::v1::test]
    fn query_failure_disables_only_source_checks() {
        let capabilities = DeviceCapabilities {
            android_sdk: 34,
            build_type: "user".to_owned(),
            data_source_names: BTreeSet::new(),
            query_error: Some("Perfetto is unavailable on this device".to_owned()),
            ..DeviceCapabilities::default()
        };
        assert_eq!(capabilities.probe_blocker(Probe::GpuMemory), None);
    }

    #[::core::prelude::v1::test]
    fn pid_parser_reads_only_the_background_process_line() {
        assert_eq!(parse_perfetto_pid("12345\n"), Some(12_345));
        assert_eq!(
            parse_perfetto_pid("Perfetto background process started\n 4242 \n"),
            Some(4_242)
        );
        assert_eq!(parse_perfetto_pid("no pid here\n"), None);
        assert_eq!(parse_perfetto_pid("0\n"), None);
        assert_eq!(parse_perfetto_pid("pid 42\n"), None);
    }

    #[::core::prelude::v1::test]
    fn build_properties_parse_sdk_and_build_type_defensively() {
        assert_eq!(
            parse_build_properties(b"34\nuserdebug\n"),
            (34, "userdebug".to_owned())
        );
        assert_eq!(
            parse_build_properties(b"garbage\n"),
            (0, "unknown".to_owned())
        );
    }

    #[::core::prelude::v1::test]
    fn every_probe_group_has_a_probe_and_each_probe_appears_once() {
        let mut counts = std::collections::BTreeMap::new();
        for probe in Probe::ALL {
            *counts.entry(probe.group()).or_insert(0) += 1;
        }
        for group in ProbeGroup::ALL {
            assert!(counts.get(&group).copied().unwrap_or(0) > 0, "{group:?}");
        }
        assert_eq!(counts.values().sum::<usize>(), Probe::ALL.len());
    }

    #[::core::prelude::v1::test]
    fn template_defaults_match_compose() {
        assert_eq!(
            CaptureTemplate::InputLatency.default_probes(),
            [Probe::CpuScheduling, Probe::Atrace].into_iter().collect()
        );
        assert!(CaptureTemplate::Custom.default_probes().is_empty());
        assert_eq!(CaptureTemplate::GfxPipeline.default_probes().len(), 5);
        assert_eq!(CaptureTemplate::MemoryProfile.default_probes().len(), 9);
    }

    #[::core::prelude::v1::test]
    fn config_validation_rejects_out_of_range_values_and_empty_custom_configs() {
        let mut config = CaptureConfig::new(CaptureTemplate::SystemOverview);
        config.duration_seconds = 0;
        assert!(config.validate().is_err());
        config.duration_seconds = 601;
        assert!(config.validate().is_err());
        config.duration_seconds = 10;
        config.buffer_size_kb = 512;
        assert!(config.validate().is_err());
        config.buffer_size_kb = 32_768;
        let mut empty_custom = CaptureConfig::new(CaptureTemplate::Custom);
        empty_custom.custom_config_text = Some("   ".to_owned());
        assert!(empty_custom.validate().is_err());
    }

    #[::core::prelude::v1::test]
    fn numeric_fields_clamp_to_compose_ranges() {
        assert_eq!(clamp_parse_u32("", 10, 1, 600), 10);
        assert_eq!(clamp_parse_u32("0", 10, 1, 600), 1);
        assert_eq!(clamp_parse_u32("9999", 10, 1, 600), 600);
        assert_eq!(clamp_parse_u32("32", 32, 1, 1_024), 32);
    }

    #[::core::prelude::v1::test]
    fn preferred_device_selection_matches_compose_rules() {
        let offline = CaptureDevice {
            serial: "offline".to_owned(),
            display_name: "Offline (offline)".to_owned(),
            online: false,
        };
        let online = CaptureDevice {
            serial: "online".to_owned(),
            display_name: "Online (online)".to_owned(),
            online: true,
        };
        assert_eq!(
            preferred_device_serial(None, &[offline.clone(), online.clone()]).as_deref(),
            Some("online")
        );
        assert_eq!(
            preferred_device_serial(Some("missing"), &[offline, online.clone()]).as_deref(),
            Some("online")
        );
        assert_eq!(
            preferred_device_serial(Some("online"), &[online.clone()]).as_deref(),
            Some("online")
        );
        let two = [
            CaptureDevice {
                serial: "one".to_owned(),
                display_name: "One".to_owned(),
                online: true,
            },
            CaptureDevice {
                serial: "two".to_owned(),
                display_name: "Two".to_owned(),
                online: true,
            },
        ];
        assert_eq!(preferred_device_serial(None, &two), None);
    }

    #[::core::prelude::v1::test]
    fn unix_timestamp_formatting_is_utc_and_stable() {
        assert_eq!(format_unix_ms(0), "1970-01-01 00:00:00 UTC");
        assert_eq!(format_unix_ms(1_700_000_000_000), "2023-11-14 22:13:20 UTC");
    }

    #[::core::prelude::v1::test]
    fn unique_ids_are_bounded_and_filesystem_safe() {
        let first = unique_id("artifact");
        let second = unique_id("artifact");
        assert_ne!(first, second);
        for id in [first, second] {
            assert!(id.len() <= 64);
            assert!(
                id.bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
            );
        }
    }

    #[::core::prelude::v1::test]
    fn system_overview_template_emits_sched_atrace_and_frame_sources() {
        let text = build_config_text(&CaptureConfig::new(CaptureTemplate::SystemOverview));
        assert!(text.contains("duration_ms: 10000"));
        assert!(text.contains("size_kb: 32768"));
        assert!(text.contains("flush_period_ms: 5000"));
        assert!(text.contains("ftrace_events: \"sched/sched_switch\""));
        assert!(text.contains("atrace_categories: \"binder_driver\""));
        assert!(text.contains("atrace_categories: \"gfx\""));
        assert!(text.contains("name: \"linux.sys_stats\""));
        assert!(text.contains("stat_period_ms: 1000"));
        assert!(text.contains("cpufreq_period_ms: 250"));
        assert!(text.contains("name: \"linux.process_stats\""));
        assert!(text.contains("name: \"android.surfaceflinger.frametimeline\""));
        assert!(text.contains("name: \"android.packages_list\""));
    }

    #[::core::prelude::v1::test]
    fn gfx_pipeline_template_emits_gpu_frequency_and_frame_sources() {
        let text = build_config_text(&CaptureConfig::new(CaptureTemplate::GfxPipeline));
        assert!(text.contains("ftrace_events: \"power/gpu_frequency\""));
        assert!(text.contains("atrace_categories: \"gfx\""));
        assert!(text.contains("name: \"android.surfaceflinger.frametimeline\""));
        assert!(!text.contains("android.log"));
    }

    #[::core::prelude::v1::test]
    fn custom_config_without_probes_is_still_a_bounded_capture() {
        let mut config = CaptureConfig::new(CaptureTemplate::SystemOverview);
        config.enabled_probes = Some(BTreeSet::new());
        let text = build_config_text(&config);
        assert!(text.contains("name: \"android.packages_list\""));
        assert!(!text.contains("name: \"linux.ftrace\""));
        assert!(config.validate().is_ok());
    }

    #[::core::prelude::v1::test]
    fn capture_commands_match_the_compose_session_contract() {
        let remote = RemoteCapturePaths::new();
        let command = remote.start_command(CaptureTemplate::SystemOverview);
        assert!(remote.trace.starts_with("/data/misc/perfetto-traces/"));
        assert!(!remote.trace.starts_with("/data/local/tmp/"));
        assert!(command.contains("perfetto --txt -c - -o"));
        assert!(command.contains("--background-wait"));
        assert!(command.contains("--no-clobber"));
        assert!(command.contains(&remote.trace));
        assert!(!command.contains("exec-out"));
        let custom = remote.start_command(CaptureTemplate::Custom);
        assert_eq!(custom, remote.start_command(CaptureTemplate::FullCapture));
        assert!(custom.contains("nohup sh -c"));
        assert!(custom.contains("--no-clobber"));
        assert!(!custom.contains("--background"));
        assert!(custom.contains("wait \"$pid\""));
        assert!(custom.contains("trap \"\" HUP"));
        assert!(custom.contains(&format!("</dev/null >>{} 2>&1", remote.log)));
        assert!(custom.contains(&format!(") 2>&1 | tail -c 8192 >{}", remote.log)));
        assert!(custom.contains(".accepted"));
        assert!(!custom.contains("kill -"));
        for template in CaptureTemplate::ALL {
            let command = remote.start_command(template);
            if matches!(
                template,
                CaptureTemplate::Custom | CaptureTemplate::FullCapture
            ) {
                assert!(command.contains("nohup sh -c"));
                assert!(!command.contains("--background"));
            } else {
                assert!(command.contains("--background-wait"));
            }
        }
        let another = RemoteCapturePaths::new();
        assert_ne!(remote.config, another.config);
        assert_ne!(remote.trace, another.trace);
        assert_eq!(ADB_START_TIMEOUT, Duration::from_secs(60));
        assert_eq!(PERFETTO_QUERY_ARGUMENTS, ["shell", "perfetto", "--query"]);
    }

    #[::core::prelude::v1::test]
    fn device_shell_commands_reach_the_device_as_one_argument() {
        let adb = AdbRunner::new(
            PathBuf::from("/platform-tools/adb"),
            "serial".to_owned(),
            CancellationToken::new(),
        );
        let start_command = adb.remote.start_command(CaptureTemplate::SystemOverview);
        for command in [
            start_command.as_str(),
            DEVICE_INFO_COMMAND,
            DEVICE_BUILD_COMMAND,
        ] {
            assert_eq!(
                adb.shell_arguments(command),
                vec![
                    OsString::from("-s"),
                    OsString::from("serial"),
                    OsString::from("shell"),
                    OsString::from(command),
                ]
            );
        }
    }

    #[::core::prelude::v1::test]
    fn custom_completion_tolerates_spacing_around_the_duration_key() {
        let config = custom("buffers { size_kb: 1024 }\n  duration_ms :\t4500  ");
        assert_eq!(
            automatic_completion_delay(&config),
            Some(Duration::from_millis(4_500))
        );
    }

    #[::core::prelude::v1::test]
    fn zero_or_malformed_custom_durations_are_ignored() {
        assert_eq!(automatic_completion_delay(&custom("duration_ms: 0")), None);
        assert_eq!(
            automatic_completion_delay(&custom("duration_ms: 12x")),
            None
        );
        assert_eq!(automatic_completion_delay(&custom("duration_ms: -5")), None);
    }
}
