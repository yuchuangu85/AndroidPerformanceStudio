# Manual Refresh and Detail Stripes Design

## Manual Refresh

- When automatic scanning is off, show a refresh icon immediately after the auto-scan switch.
- Hide the refresh icon while automatic scanning is on.
- Disable the icon while a one-shot capture is already running.
- A click connects to the foreground app, captures exactly one frame, updates the snapshot, and closes the session.
- Enabling automatic scanning cancels an in-flight one-shot capture before starting the live loop.

## Property Row Stripes

- Property rows restart their stripe index inside each section.
- Rows 0, 2, 4, ... use the deeper background.
- Rows 1, 3, 5, ... use the lighter background.
- Section headers use a dedicated accent-tinted background that remains visibly distinct from both stripe colors.
- Rendering-risk section headers use a stronger warning-tinted background.
- Severity tint remains layered over the stripe background.
- Both light and dark themes define distinct stripe colors.

## Verification

- Unit tests cover refresh visibility/enabled state and even/odd stripe classification.
- Palette tests verify distinct stripe colors.
- Desktop and full-project tests pass.
