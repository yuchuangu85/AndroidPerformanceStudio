mod ai;
mod capture;
mod cpu;
mod disassembly;
mod evidence;
mod frame;
mod home_recent;
mod import;
mod inspector;
mod method;
mod perfetto_tool;
mod shell;
mod source;
mod studio;
mod targets;
mod trace;

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
        input,
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
}

fn main() {
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
    application().with_assets(assets::Assets).run(move |cx| {
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
            // Compose reserves Cmd+Shift+O for the page's Open Folder…
            (
                "secondary-shift-o",
                Box::new(shell::OpenFolderActiveRoute) as Box<dyn Action>,
                None,
            ),
            // Compose assigns Cmd+E to each page's most common export.
            (
                "secondary-e",
                Box::new(shell::ExportActiveRoute) as Box<dyn Action>,
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
        let bounds = Bounds::centered(None, size(px(1320.), px(860.)), cx);
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
