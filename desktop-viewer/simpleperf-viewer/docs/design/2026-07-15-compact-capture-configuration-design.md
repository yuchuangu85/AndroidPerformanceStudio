# Compact Capture Configuration Design

## Goal

Make the capture configuration page denser without hiding editable sampling controls. Sampling templates and sampling parameters should share the same horizontal content band at normal desktop widths, while narrow windows remain usable.

## Approved layout

- Keep the page header first, followed by the capture status/action card so the primary collection action remains visible outside the scrolling configuration area.
- Reduce page padding from 24 dp to 16 dp and reduce major vertical gaps from 16 dp to 10–12 dp.
- Render `Capture Configuration` with `titleLarge` instead of `headlineMedium`; keep the selected-target line directly below it using secondary body text.
- At widths of at least 900 dp, place configuration content in a row:
  - left, 36%: a compact sampling-template panel with vertically stacked template cards;
  - right, 64%: the editable advanced-parameter card.
- Below 900 dp, stack the same two panels vertically so fields and labels remain readable.
- Remove the separate read-only `Parameters` summary card. It duplicates the editable parameter card and adds avoidable height.

## Component changes

- `CapturePage`: owns outer spacing, header typography, fixed capture controls, scrolling content, and responsive row/column selection.
- `SamplingTemplatePanel`: owns the template heading and compact template-card list.
- `AdvancedCaptureParameters`: remains the single parameter surface; only its padding and internal spacing become denser.
- `TemplateCard`: keeps name and description but uses reduced padding and spacing.
- `CaptureControls`: retains behavior and position; padding is reduced without changing actions or state copy.

## Behavior and accessibility

- Template selection, field validation, event chips, rate mode, duration, call graph, scope, Start/Stop/Cancel, and localization behavior remain unchanged.
- Full card click targets remain available for template selection.
- Horizontal layout must not introduce horizontal scrolling; narrow windows use the stacked fallback.
- No new dependency, theme layer, color, or icon is introduced.

## Verification

- Add a regression test for the responsive layout policy and source-level checks for the reduced title style, side-by-side panels, and removal of the duplicate summary card.
- Run presentation tests, ktlint, detekt, and the full `checkAll` suite.
- Capture the page at a normal desktop width and a narrow width; review density, clipping, hierarchy, and action visibility before completion.
