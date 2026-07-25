# Hierarchy Keyboard Navigation Design

## Goal

After a hierarchy row is clicked, let the keyboard move selection through visible rows and toggle the selected subtree.

## Interaction

- Clicking a row gives the hierarchy pane keyboard focus and selects the row.
- Up and Down move selection by one item in the current visible row order.
- Navigation stops at the first and last visible rows.
- Collapsed descendants are skipped.
- Enter toggles the selected row only when it has children.
- Selection continues through the existing store callback so the preview and details refresh.

## Verification

- Unit tests cover visible-order navigation, edge clamping, collapsed descendants, and expandable-only toggling.
- Desktop and full-project tests pass.
