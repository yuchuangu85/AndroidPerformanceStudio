# App-Only Canvas and Compact Hierarchy Design

## Goal

Make the Desktop Viewer prioritize the foreground application's real visible area instead of shrinking the entire emulator or device screenshot into a portrait-shaped preview. Increase hierarchy density so more nodes remain visible without scrolling.

## Scope

### Included

- Add a `仅应用` toggle to the CANVAS panel, enabled by default.
- In app-only mode, use the captured hierarchy root bounds as the screenshot source rectangle.
- Size the preview surface with the selected source rectangle's real width-to-height ratio.
- Transform selected-node bounds from absolute display coordinates into the cropped preview coordinate space.
- Keep a full-device mode that renders the complete screenshot and absolute bounds.
- Reduce hierarchy item vertical padding from `7dp` to `3dp`.

### Excluded

- Modifying or replacing the captured PNG bytes.
- Persisting the display-mode preference between launches.
- Changing hierarchy font size, indentation, or selection behavior.
- Adding arbitrary zoom, pan, or manual crop controls.

## Design

### Canvas mode

`LayoutInspectorMainPage` owns a local `appOnly` Boolean state. The CANVAS panel title exposes a compact `仅应用` toggle. The state defaults to `true` for each Desktop Viewer launch.

The preview derives a source rectangle:

- App-only mode: clamp the hierarchy root bounds to the screenshot dimensions.
- Full-device mode: use `(0, 0, display.widthPx, display.heightPx)`.
- Invalid or empty root bounds: fall back to the full-device rectangle.

The preview surface uses the source rectangle's aspect ratio. Portrait application windows retain a portrait surface; landscape and desktop windows use their actual wider ratio.

### Screenshot rendering

The original screenshot remains unchanged in `InspectorState`. The Compose canvas draws only the selected source rectangle into the preview destination. This avoids allocating and encoding a new PNG on every capture.

Selected-node bounds remain absolute in the protocol model. Before rendering the overlay, the canvas:

1. intersects the selected bounds with the source rectangle;
2. subtracts the source rectangle's left and top offsets;
3. scales the result into the preview destination.

Nodes outside the app crop do not render an overlay.

### Compact hierarchy

Hierarchy row typography and horizontal indentation remain unchanged. Row top and bottom padding change from `7dp` to `3dp`, reducing vertical spacing while retaining a usable click target.

## Error handling

- Missing screenshot or display metadata retains the existing waiting state.
- Empty, negative, or out-of-display root bounds use the full-device source rectangle.
- Partially visible selected nodes are clipped to the active source rectangle.
- Switching applications resets the current capture as before; the app-only preference remains enabled for the running Desktop process.

## Testing

- Source rectangle selects and clamps valid application bounds.
- Invalid application bounds fall back to the full display.
- Overlay mapping subtracts the crop origin and uses the crop scale.
- Bounds outside the crop produce no overlay.
- Existing full-device contain-fit behavior remains covered.
- Desktop UI smoke verification confirms:
  - `com.codemx.anrdemo` fills the CANVAS at its real application ratio;
  - `仅应用` switches between application-only and full-device views;
  - a selected hierarchy item remains aligned with its red overlay;
  - hierarchy rows display more densely.

## Success criteria

- The application content is no longer reduced by unrelated system desktop space.
- The CANVAS surface matches the real application window aspect ratio.
- Selection overlays remain aligned in both canvas modes.
- The user can switch back to the complete emulator or device screenshot.
- More hierarchy nodes are visible at once without reducing text readability.
