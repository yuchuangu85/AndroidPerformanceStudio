use crate::{AccentColor, DisplayScale, Language, ThemeMode};
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

pub const MAX_SETTINGS_BYTES: usize = 1024;
static NEXT_TEMP_ID: AtomicU64 = AtomicU64::new(0);

fn parse_capture_default(
    line: Option<&str>,
    key: &'static str,
    default: u32,
    max: u32,
    allow_historical_zero: bool,
) -> Result<u32, SettingsError> {
    let prefix = format!("{key}=");
    let value = line
        .and_then(|line| line.strip_prefix(&prefix))
        .and_then(|value| value.parse::<u32>().ok())
        .ok_or(SettingsError::Invalid("numeric preference is malformed"))?;
    // Version-2 settings briefly wrote zero before capture defaults were wired
    // into the GPUI form. Treat that historical value as the product default.
    if value == 0 && allow_historical_zero {
        return Ok(default);
    }
    if value == 0 || value > max {
        return Err(SettingsError::Invalid(
            "numeric preference is outside bounds",
        ));
    }
    Ok(value)
}

/// Rust-owned Layout Inspector preferences. Colors are packed ARGB.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LayoutPreferences {
    pub hide_invisible_hierarchy: bool,
    pub hide_invisible_findings: bool,
    pub hide_indices: bool,
    pub show_ids: bool,
    pub show_layer_buttons: bool,
    pub show_visible_bounds: bool,
    pub hit_smallest: bool,
    pub archive_multiplier: u8,
    pub normal_color: u32,
    pub hover_color: u32,
    pub selected_color: u32,
}

impl Default for LayoutPreferences {
    fn default() -> Self {
        Self {
            hide_invisible_hierarchy: false,
            hide_invisible_findings: false,
            hide_indices: false,
            show_ids: true,
            show_layer_buttons: false,
            show_visible_bounds: true,
            hit_smallest: true,
            archive_multiplier: 1,
            normal_color: 0xff7dd3fc,
            hover_color: 0xfff59e0b,
            selected_color: 0xffef4444,
        }
    }
}

fn parse_value<'a>(line: Option<&'a str>, key: &str) -> Result<&'a str, SettingsError> {
    line.and_then(|line| line.strip_prefix(key))
        .and_then(|value| value.strip_prefix('='))
        .ok_or(SettingsError::Invalid(
            "missing or out-of-order preference field",
        ))
}

fn parse_bool(line: Option<&str>, key: &str) -> Result<bool, SettingsError> {
    match parse_value(line, key)? {
        "true" => Ok(true),
        "false" => Ok(false),
        _ => Err(SettingsError::Invalid("boolean preference is malformed")),
    }
}

fn parse_argb(value: &str) -> Result<u32, SettingsError> {
    if value.len() != 8 || !value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(SettingsError::Invalid(
            "color preference must be 8 ARGB hex digits",
        ));
    }
    u32::from_str_radix(value, 16).map_err(|_| SettingsError::Invalid("invalid ARGB color"))
}

fn parse_index(line: Option<&str>, key: &str, max: u8) -> Result<u8, SettingsError> {
    parse_value(line, key)?
        .parse::<u8>()
        .ok()
        .filter(|value| *value <= max)
        .ok_or(SettingsError::Invalid("preference index is outside bounds"))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Settings {
    pub language: Language,
    pub theme: ThemeMode,
    pub display_scale: DisplayScale,
    pub navigation_rail_collapsed: bool,
    pub accent_color: AccentColor,
    pub capture_duration_seconds: u32,
    pub capture_frequency_hz: u32,
    pub custom_accent: Option<u32>,
    pub layout: LayoutPreferences,
    pub simpleperf_follow_tooltip: bool,
    /// 0: Local, 1: Firefox Profiler local, 2: Firefox Profiler.
    pub simpleperf_viewer_engine: u8,
    /// 0: Provider, 1: Agent, 2: Skill, 3: Policy.
    pub ai_settings_tab: u8,
    /// Experimental semantic navigation: launches a local LSP server. Off by
    /// default because language servers can consume hundreds of MB.
    pub source_lsp_enabled: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            language: Language::default(),
            theme: ThemeMode::default(),
            display_scale: DisplayScale::default(),
            navigation_rail_collapsed: false,
            accent_color: AccentColor::default(),
            capture_duration_seconds: 10,
            capture_frequency_hz: 99,
            custom_accent: None,
            layout: LayoutPreferences::default(),
            simpleperf_follow_tooltip: true,
            simpleperf_viewer_engine: 0,
            ai_settings_tab: 0,
            source_lsp_enabled: false,
        }
    }
}

#[derive(Debug)]
pub enum SettingsError {
    Io(io::Error),
    Invalid(&'static str),
    TooLarge,
}

impl fmt::Display for SettingsError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(f, "Rust desktop settings: {error}"),
            Self::Invalid(message) => write!(f, "Invalid Rust desktop settings: {message}"),
            Self::TooLarge => write!(f, "Rust desktop settings exceed {MAX_SETTINGS_BYTES} bytes"),
        }
    }
}

impl std::error::Error for SettingsError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            _ => None,
        }
    }
}

impl From<io::Error> for SettingsError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

impl Settings {
    /// Load only the explicitly supplied Rust-owned preference file.
    /// Missing files use defaults; malformed files are not silently overwritten.
    pub fn load(path: &Path) -> Result<Self, SettingsError> {
        let file = match File::open(path) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Self::default()),
            Err(error) => return Err(error.into()),
        };
        let mut bytes = Vec::new();
        file.take((MAX_SETTINGS_BYTES + 1) as u64)
            .read_to_end(&mut bytes)?;
        if bytes.len() > MAX_SETTINGS_BYTES {
            return Err(SettingsError::TooLarge);
        }
        let text =
            std::str::from_utf8(&bytes).map_err(|_| SettingsError::Invalid("expected UTF-8"))?;
        Self::decode(text)
    }

    fn decode(text: &str) -> Result<Self, SettingsError> {
        let mut lines = text.lines();
        let version = lines.next();
        if !matches!(
            version,
            Some("version=1" | "version=2" | "version=3" | "version=4")
        ) {
            return Err(SettingsError::Invalid(
                "expected version=1, version=2, version=3 or version=4",
            ));
        }
        let language = match lines.next() {
            Some("language=system") if matches!(version, Some("version=3" | "version=4")) => {
                Language::System
            }
            Some("language=en") => Language::English,
            Some("language=zh-CN") => Language::SimplifiedChinese,
            _ => {
                return Err(SettingsError::Invalid(
                    "expected a supported language preference",
                ));
            }
        };
        let theme = match lines.next() {
            Some("theme=system") if matches!(version, Some("version=3" | "version=4")) => {
                ThemeMode::System
            }
            Some("theme=light") => ThemeMode::Light,
            Some("theme=dark") => ThemeMode::Dark,
            _ => {
                return Err(SettingsError::Invalid(
                    "expected a supported theme preference",
                ));
            }
        };
        if version == Some("version=1") {
            if lines.next().is_some() {
                return Err(SettingsError::Invalid("unexpected preference fields"));
            }
            return Ok(Self {
                language,
                theme,
                ..Self::default()
            });
        }
        let display_scale = match lines.next() {
            Some("displayScale=80") => DisplayScale::Percent80,
            Some("displayScale=90") => DisplayScale::Percent90,
            Some("displayScale=100") => DisplayScale::Percent100,
            Some("displayScale=110") => DisplayScale::Percent110,
            Some("displayScale=125") => DisplayScale::Percent125,
            Some("displayScale=150") if matches!(version, Some("version=3" | "version=4")) => {
                DisplayScale::Percent150
            }
            _ => {
                return Err(SettingsError::Invalid(
                    "expected a supported displayScale preference",
                ));
            }
        };
        let navigation_rail_collapsed = match lines.next() {
            Some("navigationRail=collapsed") => true,
            Some("navigationRail=expanded") => false,
            _ => {
                return Err(SettingsError::Invalid(
                    "expected navigationRail=collapsed or navigationRail=expanded",
                ));
            }
        };
        let accent_color = match lines.next() {
            Some("accent=azure") => AccentColor::Azure,
            Some("accent=cornflower") => AccentColor::Cornflower,
            Some("accent=jade") => AccentColor::Jade,
            Some("accent=orange") => AccentColor::Orange,
            Some("accent=purple") => AccentColor::Purple,
            Some("accent=bananaRed") if matches!(version, Some("version=3" | "version=4")) => {
                AccentColor::BananaRed
            }
            Some("accent=merlotPink") if matches!(version, Some("version=3" | "version=4")) => {
                AccentColor::MerlotPink
            }
            Some("accent=lemonYellow") if matches!(version, Some("version=3" | "version=4")) => {
                AccentColor::LemonYellow
            }
            _ => return Err(SettingsError::Invalid("invalid accent color")),
        };
        let capture_duration_seconds = parse_capture_default(
            lines.next(),
            "captureDuration",
            10,
            300,
            version == Some("version=2"),
        )?;
        let capture_frequency_hz = parse_capture_default(
            lines.next(),
            "captureFrequency",
            99,
            4000,
            version == Some("version=2"),
        )?;
        let mut settings = Self {
            language,
            theme,
            display_scale,
            navigation_rail_collapsed,
            accent_color,
            capture_duration_seconds,
            capture_frequency_hz,
            ..Self::default()
        };
        if matches!(version, Some("version=3" | "version=4")) {
            settings.custom_accent = match parse_value(lines.next(), "customAccent")? {
                "none" => None,
                color => Some(parse_argb(color)?),
            };
            settings.layout = LayoutPreferences {
                hide_invisible_hierarchy: parse_bool(lines.next(), "layoutHideInvisibleHierarchy")?,
                hide_invisible_findings: parse_bool(lines.next(), "layoutHideInvisibleFindings")?,
                hide_indices: parse_bool(lines.next(), "layoutHideIndices")?,
                show_ids: parse_bool(lines.next(), "layoutShowIds")?,
                show_layer_buttons: parse_bool(lines.next(), "layoutShowLayerButtons")?,
                show_visible_bounds: parse_bool(lines.next(), "layoutShowVisibleBounds")?,
                hit_smallest: parse_bool(lines.next(), "layoutHitSmallest")?,
                archive_multiplier: parse_index(lines.next(), "layoutArchiveMultiplier", 10)?,
                normal_color: parse_argb(parse_value(lines.next(), "layoutNormalColor")?)?,
                hover_color: parse_argb(parse_value(lines.next(), "layoutHoverColor")?)?,
                selected_color: parse_argb(parse_value(lines.next(), "layoutSelectedColor")?)?,
            };
            if settings.layout.archive_multiplier == 0 {
                return Err(SettingsError::Invalid(
                    "archive multiplier must be in 1..=10",
                ));
            }
            settings.simpleperf_follow_tooltip =
                parse_bool(lines.next(), "simpleperfFollowTooltip")?;
            settings.simpleperf_viewer_engine =
                parse_index(lines.next(), "simpleperfViewerEngine", 2)?;
            settings.ai_settings_tab = parse_index(lines.next(), "aiSettingsTab", 3)?;
        }
        if version == Some("version=4") {
            settings.source_lsp_enabled = parse_bool(lines.next(), "sourceLspEnabled")?;
        }
        if lines.next().is_some() {
            return Err(SettingsError::Invalid("unexpected preference fields"));
        }
        Ok(settings)
    }

    /// Write a complete same-directory temporary file, then replace by rename.
    /// On rename failure (including a locked Windows target) the old file is kept;
    /// this never falls back to deleting or truncating the old preference file.
    pub fn save(&self, path: &Path) -> Result<(), SettingsError> {
        if !(1..=300).contains(&self.capture_duration_seconds)
            || !(1..=4000).contains(&self.capture_frequency_hz)
            || !(1..=10).contains(&self.layout.archive_multiplier)
            || self.simpleperf_viewer_engine > 2
            || self.ai_settings_tab > 3
        {
            return Err(SettingsError::Invalid(
                "numeric preference is outside bounds",
            ));
        }
        let parent = path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        let name = path
            .file_name()
            .ok_or(SettingsError::Invalid("missing file name"))?;
        fs::create_dir_all(parent)?;
        let mut temporary = None;
        for _ in 0..32 {
            let id = NEXT_TEMP_ID.fetch_add(1, Ordering::Relaxed);
            let mut temp_name = name.to_os_string();
            temp_name.push(format!(".{}.{id}.tmp", std::process::id()));
            let temp_path = parent.join(temp_name);
            match OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temp_path)
            {
                Ok(file) => {
                    temporary = Some((TemporaryFile(temp_path), file));
                    break;
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error.into()),
            }
        }
        let (temporary, mut file) = temporary.ok_or_else(|| {
            SettingsError::Io(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "could not allocate preferences temporary file",
            ))
        })?;
        let language = match self.language {
            Language::System => "system",
            Language::English => "en",
            Language::SimplifiedChinese => "zh-CN",
        };
        let theme = match self.theme {
            ThemeMode::System => "system",
            ThemeMode::Light => "light",
            ThemeMode::Dark => "dark",
        };
        let display_scale = self.display_scale.label().trim_end_matches('%');
        let navigation_rail = if self.navigation_rail_collapsed {
            "collapsed"
        } else {
            "expanded"
        };
        let accent = match self.accent_color {
            AccentColor::Azure => "azure",
            AccentColor::Cornflower => "cornflower",
            AccentColor::Jade => "jade",
            AccentColor::Orange => "orange",
            AccentColor::Purple => "purple",
            AccentColor::BananaRed => "bananaRed",
            AccentColor::MerlotPink => "merlotPink",
            AccentColor::LemonYellow => "lemonYellow",
        };
        write!(
            file,
            "version=4\nlanguage={language}\ntheme={theme}\ndisplayScale={display_scale}\nnavigationRail={navigation_rail}\naccent={accent}\ncaptureDuration={}\ncaptureFrequency={}\n",
            self.capture_duration_seconds, self.capture_frequency_hz,
        )?;
        let custom_accent = self
            .custom_accent
            .map(|color| format!("{color:08X}"))
            .unwrap_or_else(|| "none".to_owned());
        let layout = self.layout;
        write!(
            file,
            "customAccent={custom_accent}\nlayoutHideInvisibleHierarchy={}\nlayoutHideInvisibleFindings={}\nlayoutHideIndices={}\nlayoutShowIds={}\nlayoutShowLayerButtons={}\nlayoutShowVisibleBounds={}\nlayoutHitSmallest={}\nlayoutArchiveMultiplier={}\nlayoutNormalColor={:08X}\nlayoutHoverColor={:08X}\nlayoutSelectedColor={:08X}\nsimpleperfFollowTooltip={}\nsimpleperfViewerEngine={}\naiSettingsTab={}\nsourceLspEnabled={}\n",
            layout.hide_invisible_hierarchy,
            layout.hide_invisible_findings,
            layout.hide_indices,
            layout.show_ids,
            layout.show_layer_buttons,
            layout.show_visible_bounds,
            layout.hit_smallest,
            layout.archive_multiplier,
            layout.normal_color,
            layout.hover_color,
            layout.selected_color,
            self.simpleperf_follow_tooltip,
            self.simpleperf_viewer_engine,
            self.ai_settings_tab,
            self.source_lsp_enabled,
        )?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary.0, path)?;
        Ok(())
    }
}

struct TemporaryFile(PathBuf);

impl Drop for TemporaryFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TempDirectory(PathBuf);

    impl TempDirectory {
        fn new() -> Self {
            let id = NEXT_TEMP_ID.fetch_add(1, Ordering::Relaxed);
            let path =
                std::env::temp_dir().join(format!("aps-foundation-{}-{id}", std::process::id()));
            fs::create_dir(&path).unwrap();
            Self(path)
        }

        fn file(&self) -> PathBuf {
            self.0.join("rust-desktop.settings")
        }
    }

    impl Drop for TempDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn missing_settings_use_defaults_without_creating_a_file() {
        let temp = TempDirectory::new();
        assert_eq!(Settings::load(&temp.file()).unwrap(), Settings::default());
        assert!(!temp.file().exists());
    }

    #[test]
    fn settings_roundtrip_and_replace_existing_file() {
        let temp = TempDirectory::new();
        Settings::default().save(&temp.file()).unwrap();
        let changed = Settings {
            language: Language::SimplifiedChinese,
            theme: ThemeMode::Light,
            ..Settings::default()
        };
        changed.save(&temp.file()).unwrap();
        assert_eq!(Settings::load(&temp.file()).unwrap(), changed);
        assert_eq!(fs::read_dir(&temp.0).unwrap().count(), 1);
    }

    #[test]
    fn invalid_versions_enums_missing_duplicate_and_extra_fields_fail_closed() {
        let temp = TempDirectory::new();
        for text in [
            "",
            "version=3\nlanguage=en\ntheme=dark\n",
            "version=1\nlanguage=xx\ntheme=dark\n",
            "version=1\nlanguage=en\ntheme=system\n",
            "version=1\nlanguage=en\n",
            "version=1\nlanguage=en\nlanguage=en\n",
            "version=1\nlanguage=en\ntheme=dark\nsecret=bad\n",
        ] {
            fs::write(temp.file(), text).unwrap();
            assert!(
                matches!(Settings::load(&temp.file()), Err(SettingsError::Invalid(_))),
                "accepted {text:?}"
            );
            assert_eq!(fs::read_to_string(temp.file()).unwrap(), text);
        }
    }

    #[test]
    fn historical_preferences_upgrade_with_new_fields_defaulted() {
        let version_one = Settings::decode("version=1\nlanguage=zh-CN\ntheme=light\n").unwrap();
        assert_eq!(version_one.language, Language::SimplifiedChinese);
        assert_eq!(version_one.layout, LayoutPreferences::default());
        let version_two = Settings::decode(
            "version=2\nlanguage=en\ntheme=dark\ndisplayScale=125\nnavigationRail=collapsed\naccent=azure\ncaptureDuration=0\ncaptureFrequency=0\n",
        ).unwrap();
        assert_eq!(version_two.display_scale, DisplayScale::Percent125);
        assert!(version_two.navigation_rail_collapsed);
        assert_eq!(version_two.capture_duration_seconds, 10);
        assert_eq!(version_two.capture_frequency_hz, 99);
        assert_eq!(version_two.custom_accent, None);
        assert_eq!(version_two.simpleperf_viewer_engine, 0);
        assert!(version_two.simpleperf_follow_tooltip);
        assert_eq!(version_two.ai_settings_tab, 0);
        assert!(!version_two.source_lsp_enabled);
        let version_three = Settings::decode(
            "version=3\nlanguage=en\ntheme=dark\ndisplayScale=100\nnavigationRail=expanded\naccent=azure\ncaptureDuration=10\ncaptureFrequency=99\ncustomAccent=none\nlayoutHideInvisibleHierarchy=false\nlayoutHideInvisibleFindings=false\nlayoutHideIndices=false\nlayoutShowIds=true\nlayoutShowLayerButtons=true\nlayoutShowVisibleBounds=true\nlayoutHitSmallest=true\nlayoutArchiveMultiplier=1\nlayoutNormalColor=FF7DD3FC\nlayoutHoverColor=FFF59E0B\nlayoutSelectedColor=FFEF4444\nsimpleperfFollowTooltip=true\nsimpleperfViewerEngine=0\naiSettingsTab=0\n",
        )
        .unwrap();
        assert!(!version_three.source_lsp_enabled);
    }

    #[test]
    fn version_four_roundtrips_all_settings_without_losing_argb() {
        let temp = TempDirectory::new();
        let settings = Settings {
            language: Language::System,
            theme: ThemeMode::System,
            display_scale: DisplayScale::Percent150,
            accent_color: AccentColor::MerlotPink,
            custom_accent: Some(0x805a92e5),
            layout: LayoutPreferences {
                hide_invisible_hierarchy: true,
                hide_invisible_findings: true,
                hide_indices: true,
                show_ids: false,
                show_layer_buttons: true,
                show_visible_bounds: false,
                hit_smallest: false,
                archive_multiplier: 10,
                normal_color: 0x807dd3fc,
                hover_color: 0x40f59e0b,
                selected_color: 0x20ef4444,
            },
            simpleperf_follow_tooltip: false,
            simpleperf_viewer_engine: 2,
            ai_settings_tab: 3,
            source_lsp_enabled: true,
            ..Settings::default()
        };
        settings.save(&temp.file()).unwrap();
        let text = fs::read_to_string(temp.file()).unwrap();
        assert!(text.starts_with("version=4\n"));
        assert!(text.len() <= MAX_SETTINGS_BYTES);
        assert_eq!(Settings::load(&temp.file()).unwrap(), settings);
    }

    #[test]
    fn every_preference_enum_variant_roundtrips() {
        let temp = TempDirectory::new();
        for language in Language::ALL {
            for theme in ThemeMode::ALL {
                for display_scale in DisplayScale::ALL {
                    for accent_color in AccentColor::ALL {
                        let settings = Settings {
                            language,
                            theme,
                            display_scale,
                            accent_color,
                            ..Settings::default()
                        };
                        settings.save(&temp.file()).unwrap();
                        assert_eq!(Settings::load(&temp.file()).unwrap(), settings);
                    }
                }
            }
        }
    }

    #[test]
    fn version_four_rejects_corrupt_indices_colors_and_extra_fields() {
        let temp = TempDirectory::new();
        Settings::default().save(&temp.file()).unwrap();
        let valid = fs::read_to_string(temp.file()).unwrap();
        for invalid in [
            valid.replace("captureDuration=10", "captureDuration=0"),
            valid.replace("captureFrequency=99", "captureFrequency=0"),
            valid.replace("layoutArchiveMultiplier=1", "layoutArchiveMultiplier=0"),
            valid.replace("layoutArchiveMultiplier=1", "layoutArchiveMultiplier=11"),
            valid.replace("layoutShowIds=true", "layoutShowIds=1"),
            valid.replace("layoutNormalColor=FF7DD3FC", "layoutNormalColor=7DD3FC"),
            valid.replace("customAccent=none", "customAccent=not-a-color"),
            valid.replace("simpleperfViewerEngine=0", "simpleperfViewerEngine=3"),
            valid.replace("aiSettingsTab=0", "aiSettingsTab=4"),
            valid.replace("sourceLspEnabled=false", "sourceLspEnabled=1"),
            format!("{valid}unexpected=value\n"),
        ] {
            assert!(matches!(
                Settings::decode(&invalid),
                Err(SettingsError::Invalid(_))
            ));
        }
        let mut invalid = Settings::default();
        invalid.layout.archive_multiplier = 0;
        assert!(invalid.save(&temp.file()).is_err());
        assert_eq!(fs::read_to_string(temp.file()).unwrap(), valid);
    }

    #[test]
    fn oversized_and_invalid_utf8_settings_are_rejected_without_mutation() {
        let temp = TempDirectory::new();
        fs::write(temp.file(), vec![b'a'; MAX_SETTINGS_BYTES + 1]).unwrap();
        assert!(matches!(
            Settings::load(&temp.file()),
            Err(SettingsError::TooLarge)
        ));
        fs::write(temp.file(), [0xff]).unwrap();
        assert!(matches!(
            Settings::load(&temp.file()),
            Err(SettingsError::Invalid(_))
        ));
        assert_eq!(fs::read(temp.file()).unwrap(), [0xff]);
    }

    #[test]
    fn rust_preferences_do_not_read_or_modify_legacy_files() {
        let temp = TempDirectory::new();
        let legacy = temp.0.join("compose.preferences");
        fs::write(&legacy, "legacy language and provider state").unwrap();
        Settings::default().save(&temp.file()).unwrap();
        assert_eq!(
            fs::read_to_string(legacy).unwrap(),
            "legacy language and provider state"
        );
    }

    #[test]
    fn failed_replace_preserves_existing_target_and_cleans_temporary_file() {
        let temp = TempDirectory::new();
        fs::create_dir(temp.file()).unwrap();
        let marker = temp.file().join("keep");
        fs::write(&marker, "unchanged").unwrap();
        assert!(Settings::default().save(&temp.file()).is_err());
        assert_eq!(fs::read_to_string(marker).unwrap(), "unchanged");
        assert_eq!(fs::read_dir(&temp.0).unwrap().count(), 1);
    }

    #[test]
    fn nested_rust_settings_directory_is_created() {
        let temp = TempDirectory::new();
        let path = temp.0.join("rust-only/preferences.settings");
        Settings::default().save(&path).unwrap();
        assert_eq!(Settings::load(&path).unwrap(), Settings::default());
    }
}
