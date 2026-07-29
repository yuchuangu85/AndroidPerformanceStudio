# Native Menu Bar Design

## Goal

Expose the viewer's existing **Actions / 操作**, **Advanced / 高级**, and
**Settings / 设置** commands through the desktop operating system menu bar
without removing any current in-window controls.

## User Experience

The existing header remains unchanged:

- **Actions / 操作** dropdown remains available.
- **Advanced / 高级** dropdown remains available.
- The settings gear remains available.

The operating system menu bar gains:

- **Actions / 操作**, mirroring the existing action menu.
- **Advanced / 高级**, containing **Export Visible Window Views… / 导出 Visible
  Window Views…**.

On macOS, the application menu gains the native **Settings…** item immediately
below **About…**. Selecting it opens the same settings dialog as the gear,
Actions menu item, and `⌘,` shortcut.

## Menu Structure

### Actions

The native Actions menu uses the same `ViewerActionMenu` model as the header
dropdown so labels, ordering, grouping, enabled state, active state, and
shortcuts cannot drift.

Items remain:

1. Auto scan
2. Previous node
3. Next node
4. Collapse/expand node
5. Show/hide left panel
6. Show/hide bottom panel
7. Show/hide right panel
8. Settings

Separators follow the existing menu groups. Toggle items use native checkbox
items. Tree navigation items are disabled when no node is selected.

### Advanced

The native Advanced menu contains one item:

- Export Visible Window Views…

It calls the same export callback as the in-window Advanced dropdown and is
disabled while an export is running.

### Application Settings

The macOS application menu integration uses Java Desktop's application
preferences handler. The operating system owns the native item placement and
wording, including its position below About.

Unsupported operating systems or desktop environments skip this integration
without preventing application startup. The in-window settings entry points
remain available everywhere.

## Architecture

`LayoutInspectorMainPage` becomes a `WindowScope` composable so it can install a
Compose Desktop `MenuBar` beside the existing content. Both native and
in-window menus receive the same action callbacks and state models.

A small application-menu adapter isolates `java.awt.Desktop` capability checks
and preferences-handler registration. It accepts an `onOpenSettings` callback
and returns a cleanup action. `Main.kt` registers it for the lifetime of the
application window and forwards requests to `LayoutInspectorMainPage` through a
monotonic request counter.

`LayoutInspectorMainPage` observes the request counter and sets its existing
`settingsVisible` state. All settings entry points therefore open one shared
dialog and cannot create duplicate windows.

## Shortcut Ownership

Native menu items advertise the current shortcuts. Existing Compose keyboard
handling remains the behavioral fallback for platforms where native menu
accelerators are unavailable.

Each activation reaches the existing `performAction` callback once. No new
settings or export implementation is introduced.

## Localization

Actions, Advanced, and every child item use the active `ViewerStrings`
language. The macOS Settings item uses system-owned wording, consistent with
the host OS language and application-menu conventions.

## Error Handling

- Missing `Desktop` support leaves the application menu unchanged.
- Unsupported `APP_PREFERENCES` support leaves the application menu unchanged.
- Registration failures are contained so the viewer still starts.
- Existing Visible Window View export success and failure dialogs remain the
  only export feedback.

## Testing

Automated tests cover:

- native Actions menu models reuse the same labels and grouping;
- checked and enabled state for scan, panels, tree actions, and export;
- native Advanced menu invokes the shared export action;
- preferences registration invokes the shared settings request;
- unsupported Desktop capabilities are a no-op;
- repeated settings requests open the existing dialog state rather than
  creating another settings surface;
- English and Simplified Chinese menu labels;
- the complete desktop and repository test suites.

A macOS smoke test verifies that:

- Actions and Advanced appear in the system menu bar;
- Settings appears below About in the application menu;
- Settings, the gear, and `⌘,` open the same dialog;
- the native Advanced export item opens the directory chooser.

## Scope

This change adds native menu entry points only. It does not remove the header
controls, redesign the settings dialog, add About content, or change action and
export behavior.
