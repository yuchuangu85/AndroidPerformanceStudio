/**
 * Port of PaneLayout: the widths of the workspace's two side panes, the clamps
 * the separators drag against, and the fit that keeps the canvas usable when
 * the window shrinks under a remembered width.
 *
 * The reference works in dp and this page works in CSS pixels; on the displays
 * the app runs on they are the same number, so the constants port unchanged.
 */
export interface PaneWidths {
  readonly hierarchy: number;
  readonly properties: number;
}

export const PANE_LAYOUT = {
  hierarchyMinWidth: 180,
  propertiesMinWidth: 240,
  canvasMinWidth: 320,
  splitterWidth: 7,
  splitterCount: 2,
} as const;

/** Given both panes their defaults: the hierarchy is 25% wider than properties. */
export const DEFAULT_PANE_WIDTHS: PaneWidths = { hierarchy: 375, properties: 300 };

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(Math.max(value, minimum), maximum);
}

/**
 * Clamps remembered widths into the space that is actually there, in the order
 * the panes are laid out: the hierarchy first, then properties against what is
 * left of the budget.
 */
export function fitPaneWidths(widths: PaneWidths, availableWidth: number): PaneWidths {
  const sidePaneBudget = availableWidth - PANE_LAYOUT.canvasMinWidth - PANE_LAYOUT.splitterWidth * PANE_LAYOUT.splitterCount;
  const hierarchyMaximum = Math.max(PANE_LAYOUT.hierarchyMinWidth, sidePaneBudget - PANE_LAYOUT.propertiesMinWidth);
  const hierarchy = clamp(widths.hierarchy, PANE_LAYOUT.hierarchyMinWidth, hierarchyMaximum);
  const propertiesMaximum = Math.max(PANE_LAYOUT.propertiesMinWidth, sidePaneBudget - hierarchy);
  const properties = clamp(widths.properties, PANE_LAYOUT.propertiesMinWidth, propertiesMaximum);
  return { hierarchy, properties };
}

/** The separator between the hierarchy and the canvas moved by `delta` pixels. */
export function dragHierarchyWidth(widths: PaneWidths, delta: number, availableWidth: number): PaneWidths {
  const maximumWidth = Math.max(
    PANE_LAYOUT.hierarchyMinWidth,
    availableWidth - widths.properties - PANE_LAYOUT.canvasMinWidth - PANE_LAYOUT.splitterWidth * PANE_LAYOUT.splitterCount,
  );
  return {
    ...widths,
    hierarchy: clamp(widths.hierarchy + delta, PANE_LAYOUT.hierarchyMinWidth, maximumWidth),
  };
}

/**
 * The separator between the canvas and the properties pane. The delta is
 * subtracted: dragging right makes that pane narrower, because the pane grows
 * leftwards from the window's right edge.
 */
export function dragPropertiesWidth(widths: PaneWidths, delta: number, availableWidth: number): PaneWidths {
  const maximumWidth = Math.max(
    PANE_LAYOUT.propertiesMinWidth,
    availableWidth - widths.hierarchy - PANE_LAYOUT.canvasMinWidth - PANE_LAYOUT.splitterWidth * PANE_LAYOUT.splitterCount,
  );
  return {
    ...widths,
    properties: clamp(widths.properties - delta, PANE_LAYOUT.propertiesMinWidth, maximumWidth),
  };
}
