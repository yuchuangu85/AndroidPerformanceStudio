# Adopt the macOS system visual language for the desktop shell

Status: Accepted (2026-09-12). The sidebar it describes was removed by
ADR-0038; the visual language, controls and tokens still hold.

## Context

The Electron rewrite replaced the Kotlin / Compose desktop workstation with a
React renderer. The first renderer shell was a neutral dark web layout: a fixed
dark palette, 6px controls, uppercase letter-spaced section headings, and one
scrolling document per destination. It worked, but it looked like a web page in
a window frame rather than an application on the platform it ships on, and the
screens that carry the most product meaning (the three-pane Layout Inspector,
the profiler session tables, the tree of 10,000 nodes) were the least
legible.

The product ships DMG/PKG for macOS first and the development machines are
macOS, so the reference visual language is available to copy rather than
invent. macOS also supplies window chrome the shell was not using at all: the
traffic lights, a unified toolbar, and the sidebar vibrancy material.

The alternative was a platform-neutral design system of our own. That costs a
token set nobody has calibrated against real screens, and it still has to
answer the same questions AppKit already answers (what is a separator, what is
a selected row, how tall is a control, what colour is disabled text).

## Decision

The desktop shell adopts the macOS system visual language, applied in one
stylesheet for the whole renderer.

- **AppKit semantic colours.** `styles.css` defines both appearances from
  AppKit's own values: `labelColor`, `secondaryLabelColor`,
  `separatorColor`, `controlBackgroundColor`, `windowBackgroundColor`, and
  `controlAccentColor` (systemBlue `#007aff` light / `#0a84ff` dark). Light
  is the default appearance; dark is an override of the same tokens, not a
  second design.
- **System typography.** The UI font is the system stack
  (`-apple-system`, SF Pro Text) at macOS control sizes — 13px body, 11px
  secondary and section headings. The 11px headings are sentence case and
  semibold, replacing the uppercase + letter-spacing treatment.
- **Real window chrome.** On macOS the window is created with
  `titleBarStyle: 'hiddenInset'` plus `vibrancy: 'sidebar'`, the traffic
  lights are positioned inside the shell's own sidebar header, and the sidebar
  and toolbar are `-webkit-app-region: drag` regions. The page keeps a
  transparent background so the vibrancy material shows through; other
  platforms paint the window colour themselves, which keeps one stylesheet
  correct everywhere.
- **AppKit controls.** Push buttons, pop-up buttons (custom chevron, no native
  `appearance`), text fields and segmented controls are 24px tall with a 6px
  radius, a hairline border, and the accent focus ring. Cards are 10px group
  boxes on a grouped background; the destination list is a source list with
  28px rows, 16px line-art symbols, and an accent-filled selection.
- **One scrolling region.** The destination title moves into the toolbar and
  the panels scroll inside the content column, so the toolbar and sidebar never
  scroll away — which is what makes the Layout Inspector's fixed-height panes
  and a normal profiler page behave the same way.

Colour is never the only state signal, and the accent focus ring plus
`:focus-visible` outlines keep every control keyboard-visible.

## Consequences

- Every destination inherits the language through the shared stylesheet and
  the class names the panels already use (`.card`, `.field`, `.button`,
  `.runs`, `.metric`, `.tree`, `.facts`), so a new panel is styled by
  construction instead of by remembering to opt in.
- The renderer is now bound to AppKit's names and values for its tokens. A
  future non-macOS visual identity would replace the token block, not the
  component rules.
- `hiddenInset` removes the native title bar, so the shell owns the window
  title area: the sidebar header must reserve the traffic-light inset and the
  toolbar must stay draggable, or the window cannot be moved.
- The frame-rate gate (`e2e/ui-perf.mjs`) depends on the hierarchy class names
  and on the destination buttons matching their labels; both are preserved and
  the gate still passes at 10,000 nodes (120fps, p95 9.3ms, no long frames).
- Vibrancy draws a native material behind the page, which no DOM screenshot can
  capture; the sidebar tint is therefore layered over an opaque window colour
  so the shell still reads correctly when the material is unavailable.
