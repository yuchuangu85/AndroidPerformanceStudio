# Action Menu Design

## Goal

Expose the viewer's common operations in a header menu and show the keyboard shortcut beside each operation.

## Menu

- Automatic scan — `⌘R / Ctrl+R`
- Previous node — `↑`
- Next node — `↓`
- Collapse/expand node — `Enter`
- Toggle hierarchy — `⌘1 / Ctrl+1`
- Toggle findings — `⌘2 / Ctrl+2`
- Toggle details — `⌘3 / Ctrl+3`
- Settings — `⌘, / Ctrl+,`

The menu invokes the same callbacks as the existing header controls and hierarchy keyboard navigation. Command shortcuts are handled at the viewer root so they work while a child panel owns focus.

## Verification

- A unit test locks action order, labels, shortcut labels, and grouping.
- Desktop and full-project tests pass.
