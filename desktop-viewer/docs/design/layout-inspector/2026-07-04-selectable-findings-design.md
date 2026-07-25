# Selectable Findings Design

## Goal

Make every FINDINGS row text-selectable for copying. Double-clicking a row selects it visually and selects its referenced layout node.

## Interaction

- Each finding owns a stable, unique row key.
- Text selection uses Compose's `SelectionContainer`.
- A double click sets the selected finding key and invokes node selection.
- Only one finding row is highlighted at a time.
- A finding whose node no longer exists can still be highlighted; node synchronization safely does nothing.

## Verification

- Unit tests cover finding keys and single-selection state.
- Desktop module and full project tests pass.
