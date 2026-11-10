mod ai;
mod benchmark;
mod capture;
mod cpu;
mod disassembly;
mod evidence;
mod frame;
mod home_recent;
mod import;
mod inspector;
mod method;
mod net;
mod perfetto_tool;
mod shell;
mod source;
mod studio;
mod targets;
mod trace;
mod user_guide;

use aps_ui_foundation::{Settings, ThemeMode as StudioTheme, typography};
use gpui_kit::{
    component::{Theme, ThemeMode},
    prelude::*,
    *,
};
use shell::Studio;
use std::path::PathBuf;
use std::rc::Rc;

// Settings live in a separate namespace; no legacy settings are read or migrated.
fn settings_path() -> Result<PathBuf, String> {
    if let Some(root) = std::env::var_os("APS_RUST_DATA_DIR") {
        let root = PathBuf::from(root);
        if !root.is_absolute() {
            return Err("APS_RUST_DATA_DIR must be an absolute Rust-owned directory".into());
        }
        return Ok(root.join("settings.conf"));
    }
    #[cfg(target_os = "windows")]
    let root = std::env::var_os("APPDATA").map(PathBuf::from);
    #[cfg(target_os = "macos")]
    let root = std::env::var_os("HOME")
        .map(|home| PathBuf::from(home).join("Library/Application Support"));
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    let root = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".config")));
    root.filter(|root| root.is_absolute())
        .map(|root| root.join("AndroidPerformanceStudio-Rust/settings.conf"))
        .ok_or_else(|| "Cannot locate Rust application data directory".into())
}

#[derive(Clone, Copy)]
struct AppliedSettings(Settings);
impl Global for AppliedSettings {}

pub(crate) fn apply_theme(settings: &Settings, cx: &mut App) {
    apply_theme_with_appearance(settings, cx.window_appearance(), cx);
}

fn apply_theme_with_appearance(settings: &Settings, appearance: WindowAppearance, cx: &mut App) {
    // Keep the latest preference for appearance callbacks; selecting a fixed theme
    // must stop following the OS without reopening the window.
    cx.set_global(AppliedSettings(*settings));
    let dark = settings
        .theme
        .is_dark(ThemeMode::from(appearance).is_dark());
    component::set_locale(settings.language.locale());
    // The shell owns the route menu bar: it installs the native application
    // menu on macOS and draws the in-window strip on other platforms.
    Theme::change(
        if dark {
            ThemeMode::Dark
        } else {
            ThemeMode::Light
        },
        None,
        cx,
    );
    Theme::update(cx, |theme| {
        theme.font_size = px(typography::BODY * settings.display_scale.multiplier());
        let accent: Hsla = match settings.custom_accent {
            Some(argb) => rgba(argb.rotate_left(8)).into(),
            None => rgb(settings.accent_color.rgb()).into(),
        };
        theme.accent = accent;
        theme.sidebar_accent = accent;
        unify_background_surfaces(theme, studio::titlebar_background(dark).into());
        // One separator color for every screen: themed component borders (tables,
        // title/status bars, sidebars, windows) match the studio palette hairlines.
        let divider: Hsla = studio::divider_color(dark).into();
        theme.colors.border = divider;
        theme.colors.sidebar_border = divider;
        theme.colors.table_row_border = divider;
        theme.colors.title_bar_border = divider;
        theme.colors.status_bar_border = divider;
        theme.colors.window_border = divider;
        theme.colors.drag_border = divider;
    });
}

/// Repaints every GPUI background surface with the title bar color, matching the
/// custom title bar and the studio palette. Component states (hover, active,
/// selection) keep their own tints.
fn unify_background_surfaces(theme: &mut Theme, surface: Hsla) {
    macro_rules! unify {
        ($theme:expr, $surface:expr; $($field:ident),+ $(,)?) => {
            $( $theme.colors.$field = $surface; )+
        };
    }
    unify!(
        theme,
        surface;
        accordion,
        background,
        button,
        button_secondary,
        group_box,
        list,
        list_even,
        list_head,
        muted,
        popover,
        secondary,
        sidebar,
        status_bar,
        tab,
        tab_bar,
        tab_bar_segmented,
        table,
        table_even,
        table_foot,
        table_head,
        title_bar,
    );
    // `input` is the control border, including unchecked radio rings, not a fill.
    theme.colors.input =
        studio::StudioColors::for_mode_with_accent(theme.is_dark(), theme.accent.into())
            .secondary_text
            .into();
}

/// Unbundled macOS binaries take the application-menu and Dock name from the
/// process name, which defaults to the cargo binary name. Use the project name
/// so the system surfaces read `Android Performance Studio`.
#[cfg(target_os = "macos")]
fn set_macos_process_name() {
    use objc2_foundation::{NSProcessInfo, NSString};
    let name = NSString::from_str("Android Performance Studio");
    NSProcessInfo::processInfo().setProcessName(&name);
}

fn main() {
    #[cfg(target_os = "macos")]
    set_macos_process_name();
    let path = settings_path();
    let (settings, notice) = match path
        .as_ref()
        .map_err(Clone::clone)
        .and_then(|path| Settings::load(path).map_err(|e| e.to_string()))
    {
        Ok(settings) => {
            // Persist normalized defaults and version upgrades (including the
            // historical zero capture values) without touching Compose settings.
            let notice = path
                .as_ref()
                .ok()
                .and_then(|path| settings.save(path).err().map(|error| error.to_string()));
            (settings, notice)
        }
        Err(error) => (Settings::default(), Some(error)),
    };
    // GPUI's default HTTP client is a null implementation; the AI runtime needs
    // real transport for model discovery and provider requests. Request bodies
    // are staged under the Rust-owned data directory, never the settings file.
    let http_body_root = path
        .as_ref()
        .ok()
        .and_then(|settings| settings.parent())
        .map(std::path::Path::to_path_buf)
        .unwrap_or_else(std::env::temp_dir);
    let http_client = std::sync::Arc::new(net::CurlHttpClient::new(http_body_root));
    let application = application()
        .with_assets(assets::Assets)
        .with_http_client(http_client);
    application.run(move |cx| {
        init(cx);
        apply_theme(&settings, cx);
        cx.on_action(|_: &shell::Quit, cx| cx.quit());
        // `secondary-*` is an OS-resolved alias in GPUI. Use the dynamic loader;
        // `KeyBinding::new` uses the platform-independent parser and panics on it.
        let bindings: Vec<_> = [
            (
                "secondary-q",
                Box::new(shell::Quit) as Box<dyn Action>,
                None,
            ),
            // Cmd+O opens the file picker of the visible page, matching the
            // window menu's per-page Open… row.
            (
                "secondary-o",
                Box::new(shell::OpenActiveRouteFile) as Box<dyn Action>,
                None,
            ),
            // IDEA: Cmd+E opens the active page's recent files.
            (
                "secondary-e",
                Box::new(shell::RecentFilesActiveRoute) as Box<dyn Action>,
                None,
            ),
            // IDEA: Cmd+W closes the active page's open document.
            (
                "secondary-w",
                Box::new(shell::CloseActiveRoute) as Box<dyn Action>,
                None,
            ),
            // IDEA: Cmd+F focuses the active page's search.
            (
                "secondary-f",
                Box::new(shell::FocusActiveSearch) as Box<dyn Action>,
                None,
            ),
            // IDEA: Cmd+B navigates to the declaration/source of the selection.
            (
                "secondary-b",
                Box::new(shell::GoToSourceActiveRoute) as Box<dyn Action>,
                None,
            ),
            // IDEA AI Assistant: Cmd+\ prepares the active page's AI analysis.
            (
                "secondary-\\",
                Box::new(shell::PrepareCurrentAi) as Box<dyn Action>,
                None,
            ),
            // IDEA: Shift+Cmd+F12 hides or restores all inspector panes.
            (
                "secondary-shift-f12",
                Box::new(shell::ToggleAllInspectorPanes) as Box<dyn Action>,
                Some("Inspector"),
            ),
            // IDEA: Shift+Cmd+] selects the next tab; here the next inspector window.
            (
                "secondary-shift-]",
                Box::new(shell::NextInspectorWindow) as Box<dyn Action>,
                None,
            ),
            // IDEA: Ctrl+R runs the active page's capture.
            (
                "ctrl-r",
                Box::new(shell::StartCaptureActiveRoute) as Box<dyn Action>,
                None,
            ),
            (
                "secondary-,",
                Box::new(shell::OpenSettings) as Box<dyn Action>,
                None,
            ),
            (
                "/",
                Box::new(shell::FocusCpuSearch) as Box<dyn Action>,
                None,
            ),
            // Layout Inspector pane toggles, mirroring Compose's Cmd+1/2/3.
            (
                "secondary-1",
                Box::new(shell::ToggleInspectorHierarchy) as Box<dyn Action>,
                Some("Inspector"),
            ),
            (
                "secondary-2",
                Box::new(shell::ToggleInspectorFindings) as Box<dyn Action>,
                Some("Inspector"),
            ),
            (
                "secondary-3",
                Box::new(shell::ToggleInspectorDetails) as Box<dyn Action>,
                Some("Inspector"),
            ),
            // Hierarchy navigation is scoped to the Layout Inspector page so it
            // never steals arrows from tables or inputs on other screens.
            (
                "up",
                Box::new(shell::PreviousInspectorNode) as Box<dyn Action>,
                Some("Inspector"),
            ),
            (
                "down",
                Box::new(shell::NextInspectorNode) as Box<dyn Action>,
                Some("Inspector"),
            ),
            (
                "enter",
                Box::new(shell::ToggleSelectedInspectorNode) as Box<dyn Action>,
                Some("Inspector"),
            ),
        ]
        .into_iter()
        .filter_map(|(keystroke, action, context)| {
            let context = context.map(|context| {
                Rc::new(
                    KeyBindingContextPredicate::parse(context)
                        .expect("route key context must parse"),
                )
            });
            KeyBinding::load(keystroke, action, context, false, None, cx)
                .map_err(|error| eprintln!("Ignoring invalid key binding {keystroke}: {error}"))
                .ok()
        })
        .collect();
        cx.bind_keys(bindings);
        // Debug-only native screenshot harness: allows capturing the page at a
        // Compose reference size. Release builds always use the product size.
        #[cfg(debug_assertions)]
        let (window_width, window_height) = std::env::var("APS_WINDOW_SIZE")
            .ok()
            .and_then(|value| {
                let (width, height) = value.split_once('x')?;
                Some((width.parse::<f32>().ok()?, height.parse::<f32>().ok()?))
            })
            .unwrap_or((1320., 860.));
        #[cfg(not(debug_assertions))]
        let (window_width, window_height) = (1320., 860.);
        let bounds = Bounds::centered(None, size(px(window_width), px(window_height)), cx);
        let result = open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                window_min_size: Some(size(px(900.), px(600.))),
                titlebar: Some(TitlebarOptions {
                    title: None,
                    appears_transparent: true,
                    traffic_light_position: Some(point(px(9.), px(9.))),
                    ..Default::default()
                }),
                app_owns_titlebar_drag: true,
                app_id: Some("com.androidperformancestudio.rust".into()),
                ..Default::default()
            },
            cx,
            |window, cx| {
                apply_theme_with_appearance(&settings, window.appearance(), cx);
                window
                    .observe_window_appearance(|window, cx| {
                        let settings = cx.global::<AppliedSettings>().0;
                        if settings.theme == StudioTheme::System {
                            apply_theme_with_appearance(&settings, window.appearance(), cx);
                        }
                    })
                    .detach();
                cx.new(|cx| Studio::new(settings, path.ok(), notice, window, cx))
            },
        );
        match result {
            Ok(_) => cx.activate(true),
            Err(error) => {
                eprintln!("Unable to create GPUI window: {error}");
                cx.quit();
            }
        }
    });
}

#[cfg(test)]
mod theme_tests {
    use super::*;

    fn luminance(color: Rgba) -> f32 {
        let linear = |value: f32| {
            if value <= 0.04045 {
                value / 12.92
            } else {
                ((value + 0.055) / 1.055).powf(2.4)
            }
        };
        0.2126 * linear(color.r) + 0.7152 * linear(color.g) + 0.0722 * linear(color.b)
    }

    #[::core::prelude::v1::test]
    fn unchecked_control_borders_stay_visible_on_light_and_dark_surfaces() {
        for dark in [false, true] {
            let mut theme = Theme::default();
            theme.mode = if dark {
                ThemeMode::Dark
            } else {
                ThemeMode::Light
            };
            let surface: Hsla = studio::titlebar_background(dark).into();
            unify_background_surfaces(&mut theme, surface);
            let border: Rgba = theme.input.into();
            assert_eq!(border.a, 1.);
            let colors = studio::StudioColors::for_mode_with_accent(dark, theme.accent.into());
            for background in [colors.workspace, colors.panel, colors.field] {
                let border_luminance = luminance(border);
                let background_luminance = luminance(background);
                let ratio = (border_luminance.max(background_luminance) + 0.05)
                    / (border_luminance.min(background_luminance) + 0.05);
                assert!(ratio >= 3., "dark={dark}: contrast={ratio}");
            }
        }
    }

    #[::core::prelude::v1::test]
    fn control_border_fix_preserves_selected_colors_and_neutral_surfaces() {
        for dark in [false, true] {
            let mut theme = Theme::default();
            theme.mode = if dark {
                ThemeMode::Dark
            } else {
                ThemeMode::Light
            };
            let primary = theme.primary;
            let primary_foreground = theme.primary_foreground;
            let accent = theme.accent;
            let surface: Hsla = studio::titlebar_background(dark).into();
            unify_background_surfaces(&mut theme, surface);
            assert_eq!(theme.primary, primary);
            assert_eq!(theme.primary_foreground, primary_foreground);
            assert_eq!(theme.accent, accent);
            assert_eq!(theme.background, surface);
            assert_eq!(theme.group_box, surface);
            assert_eq!(theme.sidebar, surface);
            assert_ne!(theme.input, surface);
        }
    }
}
