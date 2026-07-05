# View Display Options Design

## Goal

Add a native **View / 视图** menu that lets users reduce visual noise without changing captured inspector data.

## Menu Structure

The native menu bar order becomes:

1. Actions / 操作
2. View / 视图
3. Advanced / 高级

The View menu contains three checkbox items:

- Hide invisible views in hierarchy / 隐藏层级结构中的不可见视图
- Hide invisible-view findings / 隐藏问题列表中的不可见视图内容
- Hide hierarchy indices / 隐藏层级索引

All three options default to disabled. They are session-only and reset when the application restarts.

## State and Data Flow

Introduce an immutable `ViewDisplayOptions` value owned by `DesktopViewerApp`. It contains one Boolean for each menu option and exposes small toggle operations.

`NativeViewerMenuModel` receives the current options and produces three checked menu items. Menu callbacks return a typed view action to `DesktopViewerApp`, which updates the options.

Captured snapshots, analysis results, node numbering, and the selected node remain unchanged. The options affect only presentation:

- The hierarchy derives display rows from the full presenter rows.
- Findings derive display items from the full finding list and row visibility metadata.
- The hierarchy row renderer conditionally emits the index prefix.

## Hierarchy Filtering

When hierarchy filtering is disabled, the tree behaves exactly as it does now.

When enabled, a row whose `visible` value is false is removed together with its descendants. Removing the subtree avoids showing orphaned children with indentation that refers to a hidden parent and reflects the effective rendering behavior of an invisible ancestor.

Expansion state remains intact while rows are filtered. Keyboard up/down navigation uses the same filtered and expansion-aware row sequence that the hierarchy renders. If the currently selected node becomes hidden, its details remain available, but the hierarchy shows no selected row until the user selects a visible row or disables the filter.

## Findings Filtering

When findings filtering is enabled, findings associated with a hierarchy row whose `visible` value is false are omitted. Findings whose node is absent from the current snapshot retain their existing behavior because their visibility cannot be determined reliably.

Severity totals in the Findings header describe the displayed findings, so the badges stay consistent with the visible list. Finding colors, double-click selection, and messages do not otherwise change.

## Hierarchy Index Display

The hierarchy index option only controls the `depth-index` prefix rendered in the left hierarchy row. Node numbering remains available internally and continues to appear in Findings, preserving node-to-finding correlation.

## Localization

Add English and Simplified Chinese strings for the View menu and all three options. Existing language selection continues to update native menu labels immediately.

## Testing

Use test-first coverage for:

- Default option values and each toggle.
- Native View menu order, labels, and checked state.
- Hierarchy subtree filtering.
- Keyboard navigation over the filtered, expansion-aware row list.
- Finding filtering and displayed severity totals.
- Hierarchy row text with and without the index.
- English and Simplified Chinese labels.

Run the complete Gradle test suite, build a desktop distributable, and smoke-test the native menu and all three toggles on macOS.
