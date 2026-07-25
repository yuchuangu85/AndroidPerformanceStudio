# Compact Collapsible Hierarchy Design

## Goal

Display more hierarchy rows while supporting subtree folding and horizontal
inspection of long, single-line labels.

## Interaction

- Rows with children show a disclosure chevron.
- Clicking the chevron toggles only that subtree.
- Clicking the remaining row area selects the node.
- All rows share one horizontal scroll position.
- Every tree starts fully expanded.

## Layout

- Row height changes from the 24dp baseline to 16dp, a one-third reduction.
- Labels use compact 10sp monospace text.
- Labels never wrap or ellipsize; horizontally clipped content is reachable by
  dragging left and right.

## State

The presenter marks rows that have children. An immutable hierarchy expansion
state stores collapsed node IDs and filters descendants from the flat
depth-first row list without changing node numbering.

## Testing

- Verify the exact 24dp-to-16dp reduction.
- Verify child-bearing row metadata.
- Verify default expansion and independent nested subtree filtering.

