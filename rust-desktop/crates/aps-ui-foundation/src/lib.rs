//! Shared product vocabulary and preferences for the independent Rust desktop.
//!
//! This crate deliberately knows nothing about GPUI or legacy Compose preferences.

mod localization;
mod settings;

pub use localization::{FormatError, TextKey};
pub use settings::{LayoutPreferences, MAX_SETTINGS_BYTES, Settings, SettingsError};

/// Logical pixel tiers matching the product's shared desktop typography contract.
pub mod typography {
    pub const TITLE: f32 = 16.0;
    pub const BODY: f32 = 13.0;
    pub const SUPPORTING: f32 = 11.0;
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Language {
    #[default]
    System,
    English,
    SimplifiedChinese,
}

impl Language {
    /// Resolve the operating-system language without changing the persisted preference.
    /// macOS uses AppleLanguages rather than a terminal-specific LANG value.
    pub fn resolved(self) -> Self {
        if self != Self::System {
            return self;
        }
        static SYSTEM_LANGUAGE: std::sync::OnceLock<Language> = std::sync::OnceLock::new();
        *SYSTEM_LANGUAGE.get_or_init(|| {
            let locale = ["LC_ALL", "LC_MESSAGES", "LANG"]
                .into_iter()
                .filter_map(|key| std::env::var(key).ok())
                .find(|value| !value.trim().is_empty() && value != "C" && value != "POSIX");
            #[cfg(target_os = "macos")]
            let locale = {
                std::process::Command::new("/usr/bin/defaults")
                    .args(["read", "-g", "AppleLanguages"])
                    .output()
                    .ok()
                    .filter(|output| output.status.success())
                    .and_then(|output| String::from_utf8(output.stdout).ok())
                    .and_then(|output| {
                        output
                            .split(|c: char| !c.is_ascii_alphanumeric() && c != '-' && c != '_')
                            .find(|token| !token.is_empty())
                            .map(str::to_owned)
                    })
                    .or(locale)
            };
            #[cfg(target_os = "windows")]
            let locale = std::process::Command::new("powershell.exe")
                .args([
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "[System.Globalization.CultureInfo]::CurrentUICulture.Name",
                ])
                .output()
                .ok()
                .filter(|output| output.status.success())
                .and_then(|output| String::from_utf8(output.stdout).ok())
                .map(|locale| locale.trim().to_owned())
                .filter(|locale| !locale.is_empty())
                .or(locale);
            let locale = locale.unwrap_or_default().to_ascii_lowercase();
            if locale.starts_with("zh")
                && !locale.contains("hant")
                && !locale.starts_with("zh_tw")
                && !locale.starts_with("zh-tw")
                && !locale.starts_with("zh_hk")
                && !locale.starts_with("zh-hk")
                && !locale.starts_with("zh_mo")
                && !locale.starts_with("zh-mo")
            {
                Self::SimplifiedChinese
            } else {
                Self::English
            }
        })
    }
    pub const ALL: [Self; 3] = [Self::System, Self::SimplifiedChinese, Self::English];

    pub fn label(self) -> &'static str {
        match self {
            Self::System => self.text(TextKey::SettingsFoundationSystem),
            Self::English => self.text(TextKey::FoundationEnglish),
            Self::SimplifiedChinese => self.text(TextKey::FoundationSimplifiedChinese),
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum ThemeMode {
    #[default]
    System,
    Light,
    Dark,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum DisplayScale {
    Percent80,
    Percent90,
    #[default]
    Percent100,
    Percent110,
    Percent125,
    Percent150,
}

impl DisplayScale {
    pub const ALL: [Self; 6] = [
        Self::Percent80,
        Self::Percent90,
        Self::Percent100,
        Self::Percent110,
        Self::Percent125,
        Self::Percent150,
    ];

    pub const fn multiplier(self) -> f32 {
        match self {
            Self::Percent80 => 0.8,
            Self::Percent90 => 0.9,
            Self::Percent100 => 1.0,
            Self::Percent110 => 1.1,
            Self::Percent125 => 1.25,
            Self::Percent150 => 1.5,
        }
    }

    pub const fn label(self) -> &'static str {
        match self {
            Self::Percent80 => "80%",
            Self::Percent90 => "90%",
            Self::Percent100 => "100%",
            Self::Percent110 => "110%",
            Self::Percent125 => "125%",
            Self::Percent150 => "150%",
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum AccentColor {
    Azure,
    #[default]
    Cornflower,
    Jade,
    Orange,
    Purple,
    BananaRed,
    MerlotPink,
    LemonYellow,
}

impl AccentColor {
    pub const ALL: [Self; 8] = [
        Self::BananaRed,
        Self::Orange,
        Self::Cornflower,
        Self::Jade,
        Self::MerlotPink,
        Self::Azure,
        Self::LemonYellow,
        Self::Purple,
    ];

    pub fn label(self, language: Language) -> &'static str {
        match self {
            Self::Azure => language.text(TextKey::SettingsFoundationAzure),
            Self::Cornflower => language.text(TextKey::SettingsFoundationCornflower),
            Self::Jade => language.text(TextKey::SettingsFoundationJade),
            Self::Orange => language.text(TextKey::SettingsFoundationOrange),
            Self::Purple => language.text(TextKey::SettingsFoundationPurple),
            Self::BananaRed => language.text(TextKey::SettingsFoundationBananaRed),
            Self::MerlotPink => language.text(TextKey::SettingsFoundationMerlotPink),
            Self::LemonYellow => language.text(TextKey::SettingsFoundationLemonYellow),
        }
    }

    pub const fn rgb(self) -> u32 {
        match self {
            Self::Azure => 0x41b5c2,
            Self::Cornflower => 0x5a92e5,
            Self::Jade => 0x5e8034,
            Self::Orange => 0xdb7a0e,
            Self::Purple => 0x722169,
            Self::BananaRed => 0xd4042d,
            Self::MerlotPink => 0xeb6d98,
            Self::LemonYellow => 0xfaca2e,
        }
    }
}

impl ThemeMode {
    pub const ALL: [Self; 3] = [Self::System, Self::Light, Self::Dark];

    pub const fn is_dark(self, system_dark: bool) -> bool {
        match self {
            Self::System => system_dark,
            Self::Light => false,
            Self::Dark => true,
        }
    }
    pub fn label(self, language: Language) -> &'static str {
        match self {
            Self::System => language.text(TextKey::SettingsFoundationSystem),
            Self::Light => language.text(TextKey::CommonLight),
            Self::Dark => language.text(TextKey::CommonDark),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImplementationStatus {
    /// The initial Rust slice has a usable page; this does not imply full parity.
    Available,
    /// Only a migration boundary exists. Do not present it as an operational tool.
    Planned,
}

impl ImplementationStatus {
    pub fn label(self, language: Language) -> &'static str {
        match self {
            Self::Available => language.text(TextKey::FoundationInitialSlice),
            Self::Planned => language.text(TextKey::FoundationNotMigrated),
        }
    }
}

/// The single navigation catalog, corresponding to the existing desktop routes.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Hash)]
pub enum Destination {
    #[default]
    Home,
    LayoutInspector,
    Cpu,
    Capture,
    Trace,
    Memory,
    Frame,
    Startup,
    Battery,
    Network,
    Gpu,
    Benchmark,
    MethodRecording,
    SourceWorkspaces,
    Ai,
    Settings,
}

impl Destination {
    pub const ALL: [Self; 16] = [
        Self::Home,
        Self::LayoutInspector,
        Self::Cpu,
        Self::Capture,
        Self::Trace,
        Self::Memory,
        Self::Frame,
        Self::Startup,
        Self::Battery,
        Self::Network,
        Self::Gpu,
        Self::Benchmark,
        Self::MethodRecording,
        Self::SourceWorkspaces,
        Self::Ai,
        Self::Settings,
    ];

    pub fn label(self, language: Language) -> &'static str {
        match self {
            Self::Home => language.text(TextKey::FoundationHome),
            Self::LayoutInspector => language.text(TextKey::FoundationLayoutInspector),
            Self::Cpu => language.text(TextKey::FoundationCpuProfiler),
            Self::Capture => language.text(TextKey::FoundationCapturePlanning),
            Self::Trace => language.text(TextKey::FoundationTraceAnalyzer),
            Self::Memory => language.text(TextKey::FoundationMemoryProfiler),
            Self::Frame => language.text(TextKey::FoundationFrameProfiler),
            Self::Startup => language.text(TextKey::FoundationStartupProfiler),
            Self::Battery => language.text(TextKey::FoundationBatteryProfiler),
            Self::Network => language.text(TextKey::FoundationNetworkProfiler),
            Self::Gpu => language.text(TextKey::FoundationGpuInspector),
            Self::Benchmark => language.text(TextKey::FoundationBenchmarkRegression),
            Self::MethodRecording => language.text(TextKey::FoundationMethodRecording),
            Self::SourceWorkspaces => language.text(TextKey::FoundationSourceWorkspaces),
            Self::Ai => language.text(TextKey::FoundationAiAnalysis),
            Self::Settings => language.text(TextKey::FoundationSettings),
        }
    }

    pub fn status(self) -> ImplementationStatus {
        // Preflight/planning-only pages do not enable AI execution or capture.
        match self {
            Self::Ai | Self::Capture => ImplementationStatus::Planned,
            _ => ImplementationStatus::Available,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn navigation_is_complete_unique_and_localized() {
        assert_eq!(Destination::ALL.len(), 16);
        let mut seen = HashSet::new();
        for destination in Destination::ALL {
            assert!(seen.insert(destination));
            assert!(!destination.label(Language::English).is_empty());
            assert!(!destination.label(Language::SimplifiedChinese).is_empty());
        }
        assert_eq!(Destination::ALL[0], Destination::Home);
        assert_eq!(Destination::ALL[15], Destination::Settings);
    }

    #[test]
    fn only_real_initial_pages_are_available() {
        let available: Vec<_> = Destination::ALL
            .into_iter()
            .filter(|destination| destination.status() == ImplementationStatus::Available)
            .collect();
        assert_eq!(available.len(), Destination::ALL.len() - 2);
        assert_eq!(Destination::Ai.status(), ImplementationStatus::Planned);
        assert_eq!(Destination::Capture.status(), ImplementationStatus::Planned);
        assert_eq!(
            ImplementationStatus::Planned.label(Language::English),
            "Not migrated"
        );
    }

    #[test]
    fn shared_typography_has_exactly_three_product_tiers() {
        assert_eq!(
            [typography::TITLE, typography::BODY, typography::SUPPORTING],
            [16.0, 13.0, 11.0]
        );
    }

    #[test]
    fn system_preferences_resolve_without_erasing_follow_system_choice() {
        let resolved = Language::System.resolved();
        assert_ne!(resolved, Language::System);
        assert_eq!(Language::System.locale(), resolved.locale());
        assert_eq!(
            Language::System.text(TextKey::FoundationHome),
            resolved.text(TextKey::FoundationHome)
        );
        assert!(ThemeMode::System.is_dark(true));
        assert!(!ThemeMode::System.is_dark(false));
        assert!(!ThemeMode::Light.is_dark(true));
        assert!(ThemeMode::Dark.is_dark(false));
        assert_eq!(Language::default(), Language::System);
        assert_eq!(ThemeMode::default(), ThemeMode::System);
        assert_eq!(DisplayScale::Percent150.multiplier(), 1.5);
        assert_eq!(AccentColor::ALL.len(), 8);
        assert_eq!(AccentColor::default(), AccentColor::Cornflower);
    }

    #[test]
    fn language_selects_embedded_resource_copy() {
        assert_eq!(Language::English.text(TextKey::FoundationHome), "Home");
        assert_eq!(
            Language::SimplifiedChinese.text(TextKey::FoundationHome),
            "首页"
        );
    }
}
