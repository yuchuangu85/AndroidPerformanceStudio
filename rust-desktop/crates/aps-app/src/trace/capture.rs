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
    collections::BTreeSet,
    ffi::OsString,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

/// Device-side config text pushed for the next capture (Compose parity).
const DEVICE_CONFIG_PATH: &str = "/data/local/tmp/perfetto-config.pbtxt";
/// Traces are written to the Android Perfetto trace directory, never world-writable tmp.
const DEVICE_TRACE_PATH: &str = "/data/misc/perfetto-traces/aps-perfetto-trace.pftrace";
const REMOTE_STOP_POLL_ATTEMPTS: usize = 20;
const REMOTE_STOP_POLL_DELAY: Duration = Duration::from_millis(250);
const ADB_QUERY_TIMEOUT: Duration = Duration::from_secs(15);
const ADB_START_TIMEOUT: Duration = Duration::from_secs(60);
const ADB_SHORT_TIMEOUT: Duration = Duration::from_secs(5);
const ADB_TRANSFER_TIMEOUT: Duration = Duration::from_secs(120);
const ADB_DEVICE_PUSH_TIMEOUT: Duration = Duration::from_secs(30);
const SHELL_OUTPUT_LIMIT: usize = 512 * 1024;
const CAPTURE_CONFIG_PATH_SEGMENT: &str = "captures";
const DEVICE_TRACE_COMMAND: &str = "cat /data/local/tmp/perfetto-config.pbtxt | perfetto --txt -c - -o /data/misc/perfetto-traces/aps-perfetto-trace.pftrace --background-wait";
const DEVICE_INFO_COMMAND: &str = "getprop ro.product.model; getprop ro.build.version.sdk";
const DEVICE_BUILD_COMMAND: &str = "getprop ro.build.version.sdk; getprop ro.build.type";
const PERFETTO_QUERY_ARGUMENTS: [&str; 2] = ["perfetto", "--query"];

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
    Custom,
}

impl CaptureTemplate {
    pub(in crate::trace) const ALL: [Self; 6] = [
        Self::SystemOverview,
        Self::AppPerformance,
        Self::GfxPipeline,
        Self::InputLatency,
        Self::MemoryProfile,
        Self::Custom,
    ];

    pub(in crate::trace) fn label(self) -> TextKey {
        match self {
            Self::SystemOverview => TextKey::SystemOverview,
            Self::AppPerformance => TextKey::AppPerformance,
            Self::GfxPipeline => TextKey::GraphicsPipeline,
            Self::InputLatency => TextKey::InputLatency,
            Self::MemoryProfile => TextKey::MemoryProfile,
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
            Self::Custom => TextKey::CustomDescription,
        }
    }

    /// PerfettoTraceTemplate.defaultProbes(); Custom starts empty.
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
}

impl DeviceCapabilities {
    /// Mirrors PerfettoDeviceCapabilities.unsupportedReason.
    pub(in crate::trace) fn unsupported_reason(&self, probe: Probe) -> Option<String> {
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
            Some(format!("Requires Android {}+", probe.minimum_android_sdk()))
        } else if unsupported_build {
            Some("Requires a userdebug or eng build".to_owned())
        } else if source_unavailable {
            Some(format!(
                "{} is unavailable on this device",
                source.unwrap_or_default()
            ))
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
            enabled_probes: None,
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
    Preparing,
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
    pub(in crate::trace) fn in_progress(&self) -> bool {
        matches!(
            self,
            Self::Preparing | Self::Recording { .. } | Self::Pulling
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

    let mut atrace_categories: Vec<String> = if probes.contains(&Probe::Atrace) {
        atrace_categories(config.template)
            .iter()
            .map(|category| (*category).to_owned())
            .collect()
    } else {
        Vec::new()
    };
    atrace_categories.extend(extra_atrace_categories);
    dedup_preserving_order(&mut atrace_categories);

    let mut atrace_apps: Vec<String> = Vec::new();
    if let Some(target) = config
        .target_package
        .as_deref()
        .filter(|target| !target.trim().is_empty())
    {
        atrace_apps.push(target.to_owned());
    }
    if probes.contains(&Probe::LowMemoryKiller) && !atrace_apps.iter().any(|app| app == "lmkd") {
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
    push_direct_data_sources(&mut data_sources, &probes, config.target_package.as_deref());
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
        CaptureTemplate::Custom => &[],
    }
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
        let mut delay = None;
        for line in text.lines() {
            let trimmed = line.trim();
            let Some(rest) = trimmed.strip_prefix("duration_ms") else {
                continue;
            };
            let Some(rest) = rest.trim_start().strip_prefix(':') else {
                continue;
            };
            let value = rest.trim();
            if !value.is_empty()
                && value.bytes().all(|byte| byte.is_ascii_digit())
                && let Ok(millis) = value.parse::<u64>()
                && millis > 0
            {
                delay = Some(Duration::from_millis(millis));
            }
        }
        delay
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
}

impl AdbRunner {
    fn new(executable: PathBuf, serial: String, cancel: CancellationToken) -> Self {
        Self {
            executable,
            serial,
            cancel,
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
        let arguments = vec![
            "-s".into(),
            self.serial.clone().into(),
            "shell".into(),
            "sh".into(),
            "-c".into(),
            command.into(),
        ];
        self.run(arguments, timeout, SHELL_OUTPUT_LIMIT)
    }

    fn push_config(&self, local: &Path) -> Result<(), String> {
        let mut arguments = self.base_arguments();
        arguments.push("push".into());
        arguments.push(local.as_os_str().to_owned());
        arguments.push(DEVICE_CONFIG_PATH.into());
        let output = self.run(arguments, ADB_DEVICE_PUSH_TIMEOUT, SHELL_OUTPUT_LIMIT)?;
        if !output.status.success() {
            return Err("Unable to push the capture config to the device".to_owned());
        }
        Ok(())
    }

    fn start_capture(&self) -> Result<u32, String> {
        let output = self.shell(DEVICE_TRACE_COMMAND, ADB_START_TIMEOUT)?;
        if !output.status.success() {
            return Err(
                "Perfetto could not start the capture; check that the device allows system tracing"
                    .to_owned(),
            );
        }
        let mut text = String::from_utf8_lossy(&output.stdout).into_owned();
        text.push('\n');
        text.push_str(&String::from_utf8_lossy(&output.stderr));
        parse_perfetto_pid(&text)
            .ok_or_else(|| "Perfetto started without returning a background process id".to_owned())
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
        let pid = pid.to_string();
        let _ = self.run(
            self.with_arguments(&["shell", "kill", signal, pid.as_str()]),
            ADB_SHORT_TIMEOUT,
            SHELL_OUTPUT_LIMIT,
        )?;
        Ok(())
    }

    /// A failed kill -0 means the process is gone; cancellation aborts the poll.
    fn wait_for_remote_stop(&self, pid: u32) -> Result<bool, String> {
        let pid_text = pid.to_string();
        for _ in 0..REMOTE_STOP_POLL_ATTEMPTS {
            if self.cancel.is_cancelled() {
                return Err("Capture cancelled".to_owned());
            }
            match self.run(
                self.with_arguments(&["shell", "kill", "-0", pid_text.as_str()]),
                Duration::from_secs(2),
                SHELL_OUTPUT_LIMIT,
            ) {
                Ok(output) => {
                    if !output.status.success() {
                        return Ok(true);
                    }
                }
                Err(error) if error == "Capture cancelled" => return Err(error),
                Err(_) => return Ok(true),
            }
            std::thread::sleep(REMOTE_STOP_POLL_DELAY);
        }
        Ok(false)
    }

    fn transfer_trace(&self, destination: &Path) -> Result<u64, String> {
        let output = self.run(
            self.with_arguments(&["exec-out", "cat", DEVICE_TRACE_PATH]),
            ADB_TRANSFER_TIMEOUT,
            MAX_OUTPUT_LIMIT,
        )?;
        if !output.status.success() {
            return Err("Unable to read the captured trace from the device".to_owned());
        }
        if output.stdout.is_empty() {
            return Err("The captured trace is empty".to_owned());
        }
        std::fs::write(destination, &output.stdout).map_err(|_| {
            "Unable to store the captured trace in the local data directory".to_owned()
        })?;
        Ok(output.stdout.len() as u64)
    }

    fn cleanup_device_files(&self) {
        let _ = self.run(
            self.with_arguments(&["shell", "rm", "-f", DEVICE_CONFIG_PATH, DEVICE_TRACE_PATH]),
            ADB_SHORT_TIMEOUT,
            SHELL_OUTPUT_LIMIT,
        );
    }
}

fn sanitized_process_error(error: ProcessError) -> String {
    match error {
        ProcessError::Cancelled => "Capture cancelled".to_owned(),
        ProcessError::Timeout => "The ADB command timed out".to_owned(),
        ProcessError::OutputLimit(_) => "ADB output exceeded the bounded transfer limit".to_owned(),
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
    let result = (|| {
        adb.push_config(&host_config)?;
        let started_unix_ms = unix_ms();
        let pid = adb.start_capture()?;
        let (device_model, android_sdk) = adb.device_info();
        Ok(ActiveCapture {
            executable: adb.executable.clone(),
            serial,
            pid,
            config: config.clone(),
            started_unix_ms,
            device_model,
            android_sdk,
            capability_query_failed,
            automatic_delay: automatic_completion_delay(&config),
        })
    })();
    let _ = std::fs::remove_file(&host_config);
    if result.is_err() {
        adb.cleanup_device_files();
    }
    result
}

fn complete_capture_blocking(
    active: ActiveCapture,
    request_remote_stop: bool,
    data_dir: PathBuf,
    cancel: CancellationToken,
    language: Language,
) -> Result<CaptureOutput, String> {
    let adb = AdbRunner::new(active.executable.clone(), active.serial.clone(), cancel);
    if request_remote_stop {
        adb.kill("-TERM", active.pid)?;
    }
    if !adb.wait_for_remote_stop(active.pid)? {
        adb.kill("-TERM", active.pid)?;
        if !adb.wait_for_remote_stop(active.pid)? {
            adb.kill("-KILL", active.pid)?;
            if !adb.wait_for_remote_stop(active.pid)? {
                adb.cleanup_device_files();
                return Err(format!(
                    "Timed out waiting for device Perfetto process {} to stop",
                    active.pid
                ));
            }
        }
    }

    let captures_dir = data_dir.join(CAPTURE_CONFIG_PATH_SEGMENT);
    std::fs::create_dir_all(&captures_dir)
        .map_err(|_| "The capture artifact directory is unavailable".to_owned())?;
    let artifact_id = unique_id("artifact");
    let trace_path = captures_dir.join(format!("{artifact_id}.pftrace"));
    let transfer = adb.transfer_trace(&trace_path);
    adb.cleanup_device_files();
    let trace_size_bytes = transfer?;

    let finished_unix_ms = unix_ms();
    let duration_nanos = finished_unix_ms
        .saturating_sub(active.started_unix_ms)
        .saturating_mul(1_000_000);

    let mut warnings = Vec::new();
    let mut limitations = Vec::new();
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

    let display_name = active
        .config
        .target_package
        .clone()
        .filter(|target| !target.trim().is_empty())
        .unwrap_or_else(|| language.text(active.config.template.label()).to_owned());
    let mut summary = format!(
        "{} · {} s · {} MB",
        language.text(active.config.template.label()),
        active.config.duration_seconds,
        active.config.buffer_size_kb / 1_024
    );
    if let Some(target) = active
        .config
        .target_package
        .as_deref()
        .filter(|target| !target.trim().is_empty())
    {
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
        completeness_label: language
            .text(TextKey::DetailsCaptureStateComplete)
            .to_owned(),
        limitations,
        warnings,
        config_summary: Some(summary),
    })
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
    match query {
        Ok(output) if output.status.success() => DeviceCapabilities {
            android_sdk,
            build_type,
            data_source_names: parse_perfetto_data_source_names(&String::from_utf8_lossy(
                &output.stdout,
            )),
            query_error: None,
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
    }
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
}

impl Capture {
    pub(in crate::trace) fn new(data_dir: Option<PathBuf>) -> Self {
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
        }
    }

    // -- device discovery -------------------------------------------------

    pub(in crate::trace) fn devices(&self) -> &[CaptureDevice] {
        &self.devices
    }

    pub(in crate::trace) fn devices_loaded(&self) -> bool {
        self.devices_loaded
    }

    pub(in crate::trace) fn selected_serial(&self) -> Option<&str> {
        self.selected_serial.as_deref()
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
            return;
        };
        self.capability_generation = self.capability_generation.wrapping_add(1);
        let generation = self.capability_generation;
        self.capabilities = None;
        self.capabilities_in_progress = true;
        let cancel = CancellationToken::new();
        let task = cx
            .background_executor()
            .spawn(async move { detect_capabilities_blocking(serial, cancel) });
        cx.spawn(async move |this, cx| {
            let capabilities = task.await;
            this.update(cx, |this, cx| {
                if this.capture.capability_generation != generation {
                    return;
                }
                this.capture.capabilities = Some(capabilities);
                this.capture.capabilities_in_progress = false;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // -- config UI state --------------------------------------------------

    pub(in crate::trace) fn selected_template(&self) -> CaptureTemplate {
        self.selected_template
    }

    pub(in crate::trace) fn enabled_probes(&self) -> &BTreeSet<Probe> {
        &self.enabled_probes
    }

    pub(in crate::trace) fn select_template(&mut self, template: CaptureTemplate) {
        if self.selected_template == template {
            return;
        }
        self.selected_template = template;
        self.enabled_probes = template.default_probes();
    }

    pub(in crate::trace) fn toggle_probe(&mut self, probe: Probe, enabled: bool) {
        if enabled {
            self.enabled_probes.insert(probe);
        } else {
            self.enabled_probes.remove(&probe);
        }
    }

    pub(in crate::trace) fn inputs(&self) -> Option<&CaptureInputs> {
        self.inputs.as_ref()
    }

    /// Lazily creates the configuration fields and applies the current language.
    pub(in crate::trace) fn ensure_inputs(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<crate::trace::TraceWorkspace>,
    ) {
        if self.inputs.is_some() {
            self.set_language(language, window, cx);
            return;
        }
        let target_package = cx.new(|cx| InputState::new(window, cx));
        let duration = cx.new(|cx| InputState::new(window, cx).default_value("10"));
        let buffer_mb = cx.new(|cx| InputState::new(window, cx).default_value("32"));
        let additional_categories = cx.new(|cx| InputState::new(window, cx));
        let custom_config = cx.new(|cx| TextareaState::new(window, cx));
        let subscriptions = vec![
            cx.subscribe(&target_package, |_, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    cx.notify();
                }
            }),
            cx.subscribe(&duration, |_, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    cx.notify();
                }
            }),
            cx.subscribe(&buffer_mb, |_, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    cx.notify();
                }
            }),
            cx.subscribe(&additional_categories, |_, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    cx.notify();
                }
            }),
            cx.subscribe(&custom_config, |_, _, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
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
                .to_string()
                .split([',', '\n'])
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
        config.additional_categories = additional_categories;
        config.custom_config_text = custom_config_text;
        config.enabled_probes = Some(
            self.enabled_probes
                .iter()
                .copied()
                .filter(|probe| {
                    self.capabilities
                        .as_ref()
                        .and_then(|capabilities| capabilities.unsupported_reason(*probe))
                        .is_none()
                        && (!probe.requires_target_package() || config.target_package.is_some())
                })
                .collect(),
        );
        config
    }

    // -- capture lifecycle ------------------------------------------------

    pub(in crate::trace) fn state(&self) -> &CaptureState {
        &self.state
    }

    pub(in crate::trace) fn is_active(&self) -> bool {
        self.state.in_progress()
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
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        let cancel = self.cancel.clone();
        self.active = None;
        self.state = CaptureState::Preparing;
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
                        this.capture.state = CaptureState::Recording {
                            started_unix_ms: active.started_unix_ms,
                        };
                        this.capture.active = Some(active);
                        this.capture.schedule_automatic_completion(generation, cx);
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

    /// Stops the recording and pulls the trace; no-op unless recording.
    pub(in crate::trace) fn stop(&mut self, cx: &mut Context<crate::trace::TraceWorkspace>) {
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
                this.capture.active = None;
                match result {
                    Ok(output) => {
                        this.capture.state = CaptureState::Completed {
                            trace_path: output.trace_path.clone(),
                            size_bytes: output.trace_size_bytes,
                        };
                        this.on_capture_completed(output, cx);
                    }
                    Err(error) => {
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
    fn shared_devices_populate_the_selected_target_without_opening_capture() {
        let devices = aps_adb::parse_devices("serial device model:Pixel_8\n").unwrap();
        let mut capture = Capture::new(None);
        capture.set_selected_serial(Some("serial".into()));
        capture.refresh_in_progress = true;
        let refresh_generation = capture.device_generation;
        capture.publish_devices(&devices);
        assert!(capture.devices_loaded());
        assert!(capture.device_ready());
        assert_eq!(capture.selected_serial(), Some("serial"));
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
        assert_eq!(capture.selected_serial(), None);
        assert!(!capture.capabilities_in_progress());
        assert_ne!(capture.capability_generation, generation);
        capture.publish_devices(&devices);
        assert!(capture.device_ready());
        capture.capabilities = Some(DeviceCapabilities::default());
        let generation = capture.capability_generation;
        capture.set_selected_serial(None);
        assert_eq!(capture.selected_serial(), None);
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
    fn capability_reasons_cover_sdk_build_and_source_availability() {
        let capabilities = DeviceCapabilities {
            android_sdk: 28,
            build_type: "user".to_owned(),
            data_source_names: ["linux.ftrace".to_owned()].into_iter().collect(),
            query_error: None,
        };
        assert_eq!(
            capabilities
                .unsupported_reason(Probe::NativeHeap)
                .as_deref(),
            Some("Requires Android 29+")
        );
        assert_eq!(
            capabilities.unsupported_reason(Probe::Syscalls).as_deref(),
            Some("Requires a userdebug or eng build")
        );
        // SDK gating takes precedence over source availability (Compose order).
        assert_eq!(
            capabilities
                .unsupported_reason(Probe::FrameTimeline)
                .as_deref(),
            Some("Requires Android 31+")
        );
        assert_eq!(
            capabilities
                .unsupported_reason(Probe::GpuRenderStages)
                .as_deref(),
            Some("gpu.renderstages is unavailable on this device")
        );
        assert_eq!(capabilities.unsupported_reason(Probe::CpuScheduling), None);
    }

    #[::core::prelude::v1::test]
    fn query_failure_disables_only_source_checks() {
        let capabilities = DeviceCapabilities {
            android_sdk: 34,
            build_type: "user".to_owned(),
            data_source_names: BTreeSet::new(),
            query_error: Some("Perfetto is unavailable on this device".to_owned()),
        };
        assert_eq!(capabilities.unsupported_reason(Probe::GpuMemory), None);
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
        assert!(DEVICE_TRACE_PATH.starts_with("/data/misc/perfetto-traces/"));
        assert!(!DEVICE_TRACE_PATH.starts_with("/data/local/tmp/"));
        assert!(DEVICE_TRACE_COMMAND.contains("perfetto --txt -c - -o"));
        assert!(DEVICE_TRACE_COMMAND.contains("--background-wait"));
        assert!(DEVICE_TRACE_COMMAND.contains(DEVICE_TRACE_PATH));
        assert_eq!(ADB_START_TIMEOUT, Duration::from_secs(60));
        assert_eq!(PERFETTO_QUERY_ARGUMENTS, ["perfetto", "--query"]);
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
