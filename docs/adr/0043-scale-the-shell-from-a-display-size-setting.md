# Scale the whole shell from one display-size setting

Status: Accepted (2026-09-13).

## Context

The shell is dense by design: 11–12px text, 20px tree rows, 24px controls, and a
Layout Inspector that fits three panes and a findings strip into one window. That
density assumes a certain display, and it is the one thing about the layout a
user cannot argue with — on a 4K panel, or with weaker eyesight, there is no
lever short of the operating system's own scaling, which changes every other
application too.

The reference has no such setting; its density is fixed. This is a deliberate
addition rather than a port.

## Decision

One setting — Settings → General → Display size — scales the entire shell, stored
as a percentage of the default and applied as the window's zoom factor.

- `displayScalePercent` joins `ApplicationUiSettings`, defaults to 100, and is
  clamped to 75–200 by `parseDisplayScalePercent`. A stored value outside the
  range leaves the shell unusable rather than merely wrong, so it is clamped
  rather than rejected.
- The settings page offers 80 / 90 / 100 / 110 / 125 / 150 in the same segmented
  control the theme and the language use.
- Main applies it with `webContents.setZoomFactor`: to every window after a load
  (`did-finish-load`, because the factor belongs to the contents and a navigation
  resets it) and immediately when the setting changes, so the choice is visible
  without a relaunch.
- Zoom rather than a CSS transform: the shell is written in px against a CSS
  viewport, so zooming reflows it exactly the way resizing the window does. A
  transform would scale the pixels without giving the layout the smaller
  viewport it needs, and a rem-based rewrite would touch every rule in the
  stylesheet.

## Consequences

- A larger size shrinks the CSS viewport (1456px → 1165px at 125%), so the dense
  pages reflow: the home grid re-columns, and the Layout Inspector keeps its pane
  minimums by giving the canvas less room. Nothing is clipped.
- The window's minimum size (1100×720) is in CSS pixels, so a scaled window keeps
  the same usable layout rather than the same physical size.
- `packages/settings/src/model.test.ts` pins the default, the steps and the
  clamping; the setting is otherwise just another field of the settings store, so
  it migrates and merges like every other one.
