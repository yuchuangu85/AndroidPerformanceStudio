//! Route menus shared by the native macOS menu bar and the in-window strip.
//!
//! Every route publishes the menus of the functionality it shows. On macOS the
//! shell installs them as the application menu bar at the top of the screen, so
//! the app follows the platform convention instead of drawing a Windows-style
//! in-window menu row. Platforms without a native menu bar fall back to the
//! strip below the title bar.
//!
//! Menus are rebuilt from live workspace state and are only reinstalled when
//! their content changes, so enabled/checked state stays current without the
//! menu flickering on every notification. A menu row carries an opaque command
//! id into a dispatch table built with the menu; the table runs the publishing
//! workspace's typed command, so a route never sees another screen's state.
//! Rows that also have a real GPUI action use it in the native menu, which is
//! what lets macOS resolve and display the action's key equivalent.

use aps_ui_foundation::{Language, TextKey};
#[cfg(not(target_os = "macos"))]
use gpui_kit::component::{
    button::{Button, ButtonVariants},
    menu::{DropdownMenu, PopupMenuItem},
};
use gpui_kit::{prelude::*, *};
use std::rc::Rc;

use crate::studio::StudioColors;

/// Height of the fallback menu strip on platforms without a native menu bar.
#[cfg(not(target_os = "macos"))]
pub(crate) const MENUBAR_HEIGHT: f32 = 30.0;

/// One command row in the native menu bar. The id addresses the dispatch table
/// that was built with the menus, so route commands stay typed instead of being
/// encoded as strings.
#[derive(Clone, PartialEq, Debug, Action)]
#[action(namespace = studio, no_json)]
pub struct RouteMenuAction {
    pub id: u32,
}

/// Runs one route menu command. Called outside the shell update so a command can
/// write back through its workspace's shell handle without re-entering it.
pub(crate) type RouteDispatch = Rc<dyn Fn(&mut Window, &mut App)>;

/// A label resolved when the menu is built: a catalog key for static wording, or
/// runtime text such as an imported snapshot filename.
#[derive(Clone, PartialEq, Eq)]
pub(crate) enum MenuLabel {
    Key(TextKey),
    Text(SharedString),
}

impl MenuLabel {
    fn resolve(&self, language: Language) -> SharedString {
        match self {
            Self::Key(key) => SharedString::from(language.text(*key)),
            Self::Text(text) => text.clone(),
        }
    }
}

impl From<TextKey> for MenuLabel {
    fn from(key: TextKey) -> Self {
        Self::Key(key)
    }
}

impl From<SharedString> for MenuLabel {
    fn from(text: SharedString) -> Self {
        Self::Text(text)
    }
}

impl From<String> for MenuLabel {
    fn from(text: String) -> Self {
        Self::Text(SharedString::from(text))
    }
}

/// One menu title and the commands it publishes for the active route.
pub(crate) struct MenuGroup<C> {
    title: TextKey,
    entries: Vec<MenuEntry<C>>,
}

/// A submenu, e.g. File > Import.
pub(crate) struct Submenu<C> {
    entries: Vec<MenuEntry<C>>,
}

/// A single row inside a menu.
pub(crate) enum MenuEntry<C> {
    Separator,
    Item {
        label: MenuLabel,
        enabled: bool,
        checked: bool,
        command: C,
        /// Real GPUI action, when the row has one. The native menu uses it so
        /// macOS shows the action's key equivalent; the fallback strip keeps
        /// using the command.
        action: Option<Box<dyn Action>>,
    },
    Submenu {
        label: MenuLabel,
        enabled: bool,
        entries: Vec<MenuEntry<C>>,
    },
}

impl<C> MenuGroup<C> {
    pub(crate) fn new(title: TextKey) -> Self {
        Self {
            title,
            entries: Vec::new(),
        }
    }

    /// Appends a command that is always available.
    pub(crate) fn item(self, label: impl Into<MenuLabel>, command: C) -> Self {
        self.push(label.into(), true, false, command, None)
    }

    /// Appends a command that is only available when `enabled` is true.
    pub(crate) fn item_when(self, enabled: bool, label: impl Into<MenuLabel>, command: C) -> Self {
        self.push(label.into(), enabled, false, command, None)
    }

    /// Appends a checkable command; the menu shows the current state.
    pub(crate) fn check(self, label: impl Into<MenuLabel>, checked: bool, command: C) -> Self {
        self.push(label.into(), true, checked, command, None)
    }

    /// Appends a command that also has a real GPUI action, so the native menu
    /// can display the action's key equivalent.
    pub(crate) fn item_action(
        self,
        label: impl Into<MenuLabel>,
        command: C,
        action: impl Action + 'static,
    ) -> Self {
        self.push(label.into(), true, false, command, Some(Box::new(action)))
    }

    /// Command with a real GPUI action and an enabled state.
    pub(crate) fn item_action_when(
        self,
        enabled: bool,
        label: impl Into<MenuLabel>,
        command: C,
        action: impl Action + 'static,
    ) -> Self {
        self.push(
            label.into(),
            enabled,
            false,
            command,
            Some(Box::new(action)),
        )
    }

    /// Checkable command with a real GPUI action for the key equivalent.
    pub(crate) fn check_action(
        self,
        label: impl Into<MenuLabel>,
        checked: bool,
        command: C,
        action: impl Action + 'static,
    ) -> Self {
        self.push(label.into(), true, checked, command, Some(Box::new(action)))
    }

    /// Appends a submenu. A submenu with no command row is dropped.
    pub(crate) fn submenu(
        self,
        label: impl Into<MenuLabel>,
        enabled: bool,
        build: impl FnOnce(Submenu<C>) -> Submenu<C>,
    ) -> Self {
        let mut this = self;
        let entries = build(Submenu::new()).entries;
        if !entries_have_item(&entries) {
            return this;
        }
        this.entries.push(MenuEntry::Submenu {
            label: label.into(),
            enabled,
            entries,
        });
        this
    }

    pub(crate) fn separator(mut self) -> Self {
        self.entries.push(MenuEntry::Separator);
        self
    }

    fn push(
        mut self,
        label: MenuLabel,
        enabled: bool,
        checked: bool,
        command: C,
        action: Option<Box<dyn Action>>,
    ) -> Self {
        self.entries.push(MenuEntry::Item {
            label,
            enabled,
            checked,
            command,
            action,
        });
        self
    }

    /// Menu title, so tests can prove which route published a group.
    pub(crate) fn title(&self) -> TextKey {
        self.title
    }

    /// Number of top-level command rows in this menu.
    pub(crate) fn len(&self) -> usize {
        self.entries
            .iter()
            .filter(|entry| matches!(entry, MenuEntry::Item { .. }))
            .count()
    }

    /// Number of enabled top-level command rows; used by tests to prove
    /// state-dependent rows stay disabled until their artifact exists.
    pub(crate) fn enabled(&self) -> usize {
        self.entries
            .iter()
            .filter(|entry| matches!(entry, MenuEntry::Item { enabled: true, .. }))
            .count()
    }

    /// A menu with no command is not published; separators alone are empty.
    pub(crate) fn is_empty(&self) -> bool {
        !entries_have_item(&self.entries)
    }
}

impl<C> Submenu<C> {
    pub(crate) fn new() -> Self {
        Self {
            entries: Vec::new(),
        }
    }

    pub(crate) fn item(mut self, label: impl Into<MenuLabel>, command: C) -> Self {
        self.push(label.into(), true, false, command, None);
        self
    }

    pub(crate) fn item_when(
        mut self,
        enabled: bool,
        label: impl Into<MenuLabel>,
        command: C,
    ) -> Self {
        self.push(label.into(), enabled, false, command, None);
        self
    }

    pub(crate) fn check(mut self, label: impl Into<MenuLabel>, checked: bool, command: C) -> Self {
        self.push(label.into(), true, checked, command, None);
        self
    }

    /// Submenu row with a real GPUI action, so macOS shows its key equivalent
    /// (for example File > Export > Session package with Cmd+E).
    pub(crate) fn item_action(
        mut self,
        label: impl Into<MenuLabel>,
        command: C,
        action: impl Action + 'static,
    ) -> Self {
        self.push(label.into(), true, false, command, Some(Box::new(action)));
        self
    }

    pub(crate) fn item_action_when(
        mut self,
        enabled: bool,
        label: impl Into<MenuLabel>,
        command: C,
        action: impl Action + 'static,
    ) -> Self {
        self.push(
            label.into(),
            enabled,
            false,
            command,
            Some(Box::new(action)),
        );
        self
    }

    pub(crate) fn separator(mut self) -> Self {
        self.entries.push(MenuEntry::Separator);
        self
    }

    fn push(
        &mut self,
        label: MenuLabel,
        enabled: bool,
        checked: bool,
        command: C,
        action: Option<Box<dyn Action>>,
    ) {
        self.entries.push(MenuEntry::Item {
            label,
            enabled,
            checked,
            command,
            action,
        });
    }
}

fn entries_have_item<C>(entries: &[MenuEntry<C>]) -> bool {
    entries.iter().any(|entry| match entry {
        MenuEntry::Item { .. } => true,
        MenuEntry::Submenu { entries, .. } => entries_have_item(entries),
        MenuEntry::Separator => false,
    })
}

/// A workspace publishes its route menus and executes the chosen command.
pub(crate) trait MenuHost: Sized + 'static {
    type Command: Clone + PartialEq + 'static;

    fn menu_groups(&self, language: Language) -> Vec<MenuGroup<Self::Command>>;

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        window: &mut Window,
        cx: &mut Context<Self>,
    );
}

/// One row of the fallback strip, kept alongside the native menu data so both
/// representations come from the same build.
#[derive(Clone)]
pub(crate) enum StripRow {
    Separator,
    /// Submenu title; the fallback strip flattens the children below it.
    Header(SharedString),
    Item {
        label: SharedString,
        enabled: bool,
        checked: bool,
        id: u32,
    },
}

/// One menu of the fallback strip.
#[derive(Clone)]
pub(crate) struct StripMenu {
    pub title: SharedString,
    pub rows: Vec<StripRow>,
}

/// Menu data for the active route plus the command table it dispatches.
#[derive(Default)]
pub(crate) struct RouteMenuBuild {
    /// Native menu bar data; taken by the macOS installer.
    pub menus: Vec<Menu>,
    /// Fallback strip data for platforms without a native menu bar.
    pub strip: Vec<StripMenu>,
    pub dispatch: Vec<RouteDispatch>,
    pub signature: String,
}

impl RouteMenuBuild {
    /// Appends one group. The signature is the localized content of the route
    /// menus, so unchanged state never reinstalls the native menu bar.
    pub(crate) fn push<C: Clone + 'static>(
        &mut self,
        group: MenuGroup<C>,
        language: Language,
        run: impl Fn(C, &mut Window, &mut App) + 'static,
    ) {
        if group.is_empty() {
            return;
        }
        let title: SharedString = language.text(group.title).into();
        let run: Rc<dyn Fn(C, &mut Window, &mut App)> = Rc::new(run);
        let mut items = Vec::new();
        let mut rows = Vec::new();
        self.signature.push_str(group.title.as_str());
        self.push_entries(group.entries, language, &run, &mut items, &mut rows);
        self.signature.push('#');
        self.menus.push(Menu::new(title.clone()).items(items));
        self.strip.push(StripMenu { title, rows });
    }

    fn push_entries<C: Clone + 'static>(
        &mut self,
        entries: Vec<MenuEntry<C>>,
        language: Language,
        run: &Rc<dyn Fn(C, &mut Window, &mut App)>,
        items: &mut Vec<MenuItem>,
        rows: &mut Vec<StripRow>,
    ) {
        for entry in entries {
            match entry {
                MenuEntry::Separator => {
                    self.signature.push('|');
                    items.push(MenuItem::separator());
                    rows.push(StripRow::Separator);
                }
                MenuEntry::Item {
                    label,
                    enabled,
                    checked,
                    command,
                    action,
                } => {
                    let id = u32::try_from(self.dispatch.len()).unwrap_or(u32::MAX);
                    let command = command.clone();
                    let run_command = run.clone();
                    self.dispatch.push(Rc::new(move |window, cx| {
                        run_command(command.clone(), window, cx);
                    }));
                    let name = label.resolve(language);
                    self.signature.push_str(&name);
                    self.signature.push(if enabled { '+' } else { '-' });
                    self.signature.push(if checked { '1' } else { '0' });
                    let action = action.unwrap_or_else(|| Box::new(RouteMenuAction { id }));
                    items.push(MenuItem::Action {
                        name: name.clone(),
                        action,
                        os_action: None,
                        checked,
                        disabled: !enabled,
                    });
                    rows.push(StripRow::Item {
                        label: name,
                        enabled,
                        checked,
                        id,
                    });
                }
                MenuEntry::Submenu {
                    label,
                    enabled,
                    entries,
                } => {
                    let name = label.resolve(language);
                    self.signature.push_str(&name);
                    self.signature.push('>');
                    let mut submenu_items = Vec::new();
                    let mut submenu_rows = Vec::new();
                    self.push_entries(
                        entries,
                        language,
                        run,
                        &mut submenu_items,
                        &mut submenu_rows,
                    );
                    self.signature.push('<');
                    let mut menu = Menu::new(name.clone()).items(submenu_items);
                    menu.disabled = !enabled;
                    items.push(MenuItem::submenu(menu));
                    rows.push(StripRow::Header(name));
                    rows.extend(submenu_rows);
                }
            }
        }
    }
}

/// Installs the route menus as the macOS application menu bar.
///
/// The application menu stays first in the bar, following the platform
/// convention. Other platforms keep the in-window strip, so this is a no-op
/// there.
pub(crate) fn install_native_menus(menus: Vec<Menu>, language: Language, cx: &mut App) {
    #[cfg(target_os = "macos")]
    {
        let mut all = Vec::with_capacity(menus.len() + 1);
        all.push(application_menu(language));
        all.extend(menus);
        cx.set_menus(all);
        override_application_menu_title(language);
    }
    #[cfg(not(target_os = "macos"))]
    let _ = (menus, language, cx);
}

#[cfg(target_os = "macos")]
/// AppKit replaces the first menu item's title with the executable name for
/// unbundled binaries. Write the project name back after every menu install so
/// the macOS application menu reads "Android Performance Studio".
#[cfg(target_os = "macos")]
fn override_application_menu_title(language: Language) {
    use objc2::MainThreadMarker;
    use objc2_app_kit::NSApplication;
    use objc2_foundation::NSString;
    let Some(mtm) = MainThreadMarker::new() else {
        return;
    };
    let app = NSApplication::sharedApplication(mtm);
    let Some(menu) = app.mainMenu() else {
        return;
    };
    let Some(item) = menu.itemAtIndex(0) else {
        return;
    };
    let title = NSString::from_str(&language.text(TextKey::FoundationAndroidPerformanceStudio));
    item.setTitle(&title);
}

fn application_menu(language: Language) -> Menu {
    Menu::new(language.text(TextKey::FoundationAndroidPerformanceStudio)).items([
        MenuItem::action(
            language.text(TextKey::ShellMenuSettings),
            crate::shell::OpenSettings,
        ),
        MenuItem::separator(),
        MenuItem::action(language.text(TextKey::ShellMenuQuit), crate::shell::Quit),
    ])
}

/// Draws the fallback menu strip for platforms without a native menu bar.
///
/// macOS uses the native application menu bar at the top of the screen, so no
/// in-window row is drawn there. Submenus are flattened into a disabled header
/// row followed by their children.
pub(crate) fn strip(
    menus: &[StripMenu],
    dispatch: Rc<Vec<RouteDispatch>>,
    colors: &StudioColors,
) -> AnyElement {
    #[cfg(target_os = "macos")]
    {
        let _ = (menus, dispatch, colors);
        return div().into_any_element();
    }
    #[cfg(not(target_os = "macos"))]
    {
        let mut titles = Vec::with_capacity(menus.len());
        for (index, menu) in menus.iter().enumerate() {
            let title = menu.title.clone();
            let rows = menu.rows.clone();
            let dispatch = dispatch.clone();
            titles.push(
                Button::new(("studio-menubar", index))
                    .ghost()
                    .h(px(MENUBAR_HEIGHT - 8.))
                    .px(px(9.))
                    .rounded(px(crate::studio::metrics::SMALL_RADIUS))
                    .text_color(colors.secondary_text)
                    .accessibility_label(title.clone())
                    .child(
                        div()
                            .text_size(px(crate::studio::FONT_BODY))
                            .whitespace_nowrap()
                            .child(title),
                    )
                    .dropdown_menu(move |menu, _, _| {
                        let mut menu = menu.min_w(px(220.));
                        for row in &rows {
                            menu = match row {
                                StripRow::Separator => menu.separator(),
                                StripRow::Header(label) => {
                                    menu.item(PopupMenuItem::new(label.clone()).disabled(true))
                                }
                                StripRow::Item {
                                    label,
                                    enabled,
                                    checked,
                                    id,
                                } => {
                                    let dispatch = dispatch.clone();
                                    let id = *id;
                                    let mut entry = PopupMenuItem::new(label.clone())
                                        .checked(*checked)
                                        .disabled(!*enabled);
                                    if *enabled {
                                        entry = entry.on_click(move |_, window, cx| {
                                            if let Some(run) = dispatch.get(id as usize) {
                                                run(window, cx);
                                            }
                                        });
                                    }
                                    menu.item(entry)
                                }
                            };
                        }
                        menu
                    })
                    .into_any_element(),
            );
        }
        bar(titles, colors)
    }
}

/// Lays the fallback menu titles out below the title bar.
#[cfg(not(target_os = "macos"))]
fn bar(children: Vec<AnyElement>, colors: &StudioColors) -> AnyElement {
    div()
        .id("studio-menubar")
        .h(px(MENUBAR_HEIGHT))
        .w_full()
        .flex()
        .items_center()
        .gap(px(1.))
        .px(px(8.))
        .bg(colors.workspace)
        .border_b_1()
        .border_color(colors.border)
        .children(children)
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn menus_without_commands_are_not_published() {
        assert!(MenuGroup::<()>::new(TextKey::ShellMenuFile).is_empty());
        assert!(
            MenuGroup::<()>::new(TextKey::ShellMenuFile)
                .separator()
                .is_empty()
        );
        assert!(
            !MenuGroup::<()>::new(TextKey::ShellMenuFile)
                .item(TextKey::ShellMenuOpen, ())
                .is_empty()
        );
    }

    #[::core::prelude::v1::test]
    fn menu_title_keeps_its_locale_key_for_ids() {
        let group = MenuGroup::<()>::new(TextKey::ShellMenuFile)
            .item(TextKey::ShellMenuOpen, ())
            .check(TextKey::ShellMenuView, true, ());
        assert_eq!(group.title(), TextKey::ShellMenuFile);
        assert_eq!(group.len(), 2);
        assert_eq!(TextKey::ShellMenuFile.as_str(), "shell_menu_file");
    }

    #[::core::prelude::v1::test]
    fn build_assigns_one_dispatch_id_per_command() {
        let mut build = RouteMenuBuild::default();
        build.push(
            MenuGroup::new(TextKey::ShellMenuFile)
                .item(TextKey::ShellMenuOpen, 1u8)
                .separator()
                .item(TextKey::ShellMenuHelp, 2u8),
            Language::English,
            |_command, _window, _cx| {},
        );
        assert_eq!(build.menus.len(), 1);
        assert_eq!(build.strip.len(), 1);
        assert_eq!(build.dispatch.len(), 2);
        let MenuItem::Action { action, .. } = &build.menus[0].items[0] else {
            panic!("expected a command row");
        };
        let id = action
            .as_any()
            .downcast_ref::<RouteMenuAction>()
            .expect("route action")
            .id;
        assert_eq!(id, 0);
    }

    #[::core::prelude::v1::test]
    fn submenus_keep_nested_rows_and_flatten_in_the_strip() {
        let mut build = RouteMenuBuild::default();
        build.push(
            MenuGroup::new(TextKey::ShellMenuFile).submenu(
                TextKey::ShellMenuImport,
                true,
                |menu| {
                    menu.item_when(false, TextKey::ShellMenuImportScreenshot, 1u8)
                        .separator()
                        .item(TextKey::ShellMenuOpenRecent, 2u8)
                },
            ),
            Language::English,
            |_command, _window, _cx| {},
        );
        assert_eq!(build.menus.len(), 1);
        assert!(matches!(build.menus[0].items[0], MenuItem::Submenu(_)));
        // Header row + two command rows + separator in the flattened strip.
        assert_eq!(build.strip[0].rows.len(), 4);
        assert_eq!(build.dispatch.len(), 2);
    }

    #[::core::prelude::v1::test]
    fn signature_tracks_labels_and_checked_state() {
        fn signature(checked: bool) -> String {
            let mut build = RouteMenuBuild::default();
            build.push(
                MenuGroup::new(TextKey::ShellMenuView).check(
                    TextKey::SettingsUiShowBounds,
                    checked,
                    0u8,
                ),
                Language::English,
                |_command, _window, _cx| {},
            );
            build.signature
        }
        assert_ne!(signature(true), signature(false));
    }
}
