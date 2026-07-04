# Selectable Findings Implementation Plan

1. Add tests for unique finding row keys and exclusive row selection.
2. Add the finding key to the presentation model and a small immutable selection state.
3. Wrap each finding text in a selection container.
4. Handle row double clicks, highlight the selected row, and select the referenced node.
5. Run desktop and project tests, then restart the desktop application.
