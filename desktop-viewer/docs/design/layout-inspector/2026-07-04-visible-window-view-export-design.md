# Visible Window View Export Design

## Goal

Add a localized top-level **Advanced / 高级** menu to the desktop viewer. Its
**Export Visible Window Views… / 导出 Visible Window Views…** item lets the user
choose a directory and exports both the raw Android window dump and a readable
text representation.

## User Experience

1. The user opens the **Advanced / 高级** menu.
2. The user selects **Export Visible Window Views… / 导出 Visible Window Views…**.
3. A native directory chooser opens.
4. After a directory is selected, the application obtains the visible-window
   dump from the selected Android device.
5. The chosen directory receives:
   - `visible-window-views.zip`
   - `visible-window-views.txt`
6. A localized result dialog reports success and the destination directory, or
   reports a specific failure.

Cancelling the directory chooser performs no export and displays no error.

## Architecture

### Menu and application coordination

The desktop UI owns menu visibility, directory selection, asynchronous
execution, and result feedback. Export runs away from the Compose UI thread so
ADB and filesystem work cannot freeze the interface.

The menu action is enabled only when the currently selected target represents
one online device. Device-selection validation is repeated by the export use
case to protect against connection changes after the menu is opened.

### ADB gateway

The ADB gateway exposes a dedicated operation that executes:

```text
adb -s <serial> exec-out cmd window dump-visible-window-views
```

The operation returns the exact binary ZIP payload. A non-zero exit code,
missing payload, offline device, or ambiguous device selection is an explicit
failure rather than a partially successful export.

### Decoder and text format

The existing visible-window hierarchy binary decoder is reused and generalized
so it can decode every non-empty ZIP entry, rather than only selecting the
foreground package root.

The TXT file contains:

- source metadata and window count;
- one section per ZIP entry;
- window name and encoded size;
- window-level properties;
- the complete indented View tree;
- every decoded property for each View;
- a summary of successfully decoded windows.

An empty window payload is recorded as an informational entry and does not
prevent other windows from being decoded. A malformed non-empty entry is
recorded with its parsing error while decoding continues. This preserves the
raw ZIP and produces the most useful text output available.

### File output

The exporter first captures and validates the ZIP in memory, then prepares the
TXT representation. Both outputs are written through temporary files in the
selected directory and renamed to their final names only after writing
succeeds. Existing files with the standard names are replaced.

If TXT generation fails before finalization, neither final output is replaced.
Temporary files are cleaned up on failure.

## Localization

All new visible strings support English and Simplified Chinese through the
existing Compose Resources `Res.string` mechanism:

- Advanced / 高级
- Export Visible Window Views… / 导出 Visible Window Views…
- export success title and message
- export failure title and actionable error messages

## Error Handling

The result dialog distinguishes:

- no selected online device;
- multiple or stale device selection;
- unsupported or failed Android window command;
- empty command output;
- invalid ZIP data;
- destination directory or filesystem failure;
- unexpected decoding failure.

Errors retain their technical cause for diagnostics without exposing raw stack
traces in the UI.

## Testing

Automated tests cover:

- English and Chinese menu labels;
- Advanced menu item wiring;
- exact ADB command construction;
- successful ZIP capture;
- full multi-window TXT decoding;
- empty window payload handling;
- malformed entry isolation;
- atomic two-file export;
- replacement of existing output files;
- cleanup and failure behavior;
- directory chooser cancellation.

The complete desktop test suite must pass before delivery.

## Scope

This change exports the currently supported Android
`dump-visible-window-views` format. It does not add alternative hierarchy
sources, change the live capture source, or add configurable output names.
