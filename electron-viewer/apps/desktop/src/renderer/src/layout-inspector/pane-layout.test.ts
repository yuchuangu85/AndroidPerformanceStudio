import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PANE_WIDTHS,
  PANE_LAYOUT,
  dragHierarchyWidth,
  dragPropertiesWidth,
  fitPaneWidths,
} from './pane-layout';

/** Every assertion here is PaneLayoutTest.kt's, ported line for line. */
describe('pane layout', () => {
  it('default hierarchy pane width is twenty five percent wider', () => {
    expect(DEFAULT_PANE_WIDTHS.hierarchy).toBe(375);
  });

  it('dragging separators changes only the adjacent side pane', () => {
    expect(dragHierarchyWidth(DEFAULT_PANE_WIDTHS, 80, 1200)).toEqual({ hierarchy: 455, properties: 300 });
    expect(dragPropertiesWidth(DEFAULT_PANE_WIDTHS, 40, 1200)).toEqual({ hierarchy: 375, properties: 260 });
  });

  it('dragging clamps side panes and preserves canvas minimum width', () => {
    expect(dragHierarchyWidth(DEFAULT_PANE_WIDTHS, -1000, 1100)).toEqual({ hierarchy: 180, properties: 300 });
    expect(dragHierarchyWidth(DEFAULT_PANE_WIDTHS, 1000, 1100)).toEqual({ hierarchy: 466, properties: 300 });
    expect(dragPropertiesWidth(DEFAULT_PANE_WIDTHS, 1000, 1100)).toEqual({ hierarchy: 375, properties: 240 });
    expect(dragPropertiesWidth(DEFAULT_PANE_WIDTHS, -1000, 1100)).toEqual({ hierarchy: 375, properties: 391 });
    const hierarchyMaximum = dragHierarchyWidth(DEFAULT_PANE_WIDTHS, 1000, 1100);
    expect(dragHierarchyWidth(hierarchyMaximum, 1000, 1100)).toEqual(hierarchyMaximum);
  });

  it('fitting remembered widths preserves canvas minimum after window shrinks', () => {
    const fitted = fitPaneWidths({ hierarchy: 500, properties: 500 }, 1100);

    expect(fitted).toEqual({ hierarchy: 500, properties: 266 });
    expect(1100 - fitted.hierarchy - fitted.properties - PANE_LAYOUT.splitterWidth * PANE_LAYOUT.splitterCount).toBe(
      PANE_LAYOUT.canvasMinWidth,
    );
  });
});
