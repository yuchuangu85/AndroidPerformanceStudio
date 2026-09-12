import { describe, expect, it } from 'vitest';
import type { UiNode } from '@aps/layout-inspector';
import { nodeDetailSections } from './details';

const NODE: UiNode = {
  type: 'view',
  id: 'window:42177c9/root/0',
  className: 'android.widget.TextView',
  bounds: { left: 50, top: 100, right: 610, bottom: 180 },
  visible: true,
  alpha: 0.5,
  children: [
    {
      type: 'view',
      id: 'window:42177c9/root/0/0',
      className: 'android.view.View',
      bounds: { left: 0, top: 0, right: 10, bottom: 10 },
      visible: false,
      alpha: 1,
      children: [],
      attributes: { rawProperties: {} },
    },
  ],
  resourceName: 'com.codemx.anrdemo:id/title',
  text: 'Title',
  attributes: {
    rawProperties: { 'drawing:elevation': '8.0', 'layout:left': '40' },
    visibility: 'VISIBLE',
    layoutBounds: { left: 40, top: 80, right: 600, bottom: 160 },
    elevation: 8,
    z: 10,
    translationX: 0,
    translationY: 0,
    translationZ: 2,
    rotation: 5,
    rotationX: 1,
    rotationY: 2,
    scaleX: 1,
    scaleY: 1,
    pivotX: 540,
    pivotY: 1200,
    padding: { left: 16, top: 24, right: 16, bottom: 24 },
    margin: { left: 8, top: 12, right: 8, bottom: 12 },
    layoutWidth: -1,
    layoutHeight: -2,
    layoutParamsClass: 'android.widget.FrameLayout.LayoutParams',
    measuredWidth: 1080,
    measuredHeight: 2400,
    minWidth: 0,
    minHeight: 0,
    scrollX: 0,
    scrollY: 12,
    clipBounds: { left: 0, top: 0, right: 1080, bottom: 2300 },
    clipChildren: true,
    clipToPadding: false,
    background: 'android.graphics.drawable.ColorDrawable@1',
    backgroundColor: '#ff0000',
    foreground: 'android.graphics.drawable.ColorDrawable@2',
    opaque: false,
    willNotDraw: false,
    hardwareAccelerated: true,
    layerType: 'SOFTWARE',
    layoutRequested: false,
    enabled: true,
    clickable: true,
    longClickable: true,
    focusable: true,
    focused: false,
    selected: false,
    contentDescription: 'Root container',
  },
};

describe('node details sections', () => {
  it('renders the five reference sections plus raw properties, in order', () => {
    const sections = nodeDetailSections(NODE, 3, 'en');
    expect(sections.map((entry) => entry.title)).toEqual([
      'RENDER RISKS',
      'IDENTITY',
      'LAYOUT',
      'DRAWING',
      'INTERACTION',
      'RAW PROPERTIES',
    ]);
    expect(sections[0]?.highlightsRenderingRisk).toBe(true);
    expect(nodeDetailSections(NODE, 3, 'zh').map((entry) => entry.title)).toEqual([
      '渲染风险',
      '标识',
      '布局',
      '绘制',
      '交互',
      '原始属性',
    ]);
  });

  it('carries every reference row instead of a filtered handful', () => {
    const sections = nodeDetailSections(NODE, 3, 'en');
    const byTitle = new Map(sections.map((entry) => [entry.title, entry.rows]));
    expect(byTitle.get('RENDER RISKS')?.map((row) => row.label)).toEqual([
      'Overdraw estimate',
      'Subtree complexity',
      'Hidden descendants',
      'Blending',
      'Layer cost',
    ]);
    expect(byTitle.get('IDENTITY')?.map((row) => row.label)).toEqual([
      'Class',
      'ID',
      'Resource',
      'Text',
      'Content description',
      'Semantics role',
    ]);
    expect(byTitle.get('LAYOUT')?.map((row) => row.label)).toEqual([
      'Bounds',
      'Size',
      'Local layout bounds',
      'Local layout size',
      'Visibility',
      'Tree depth',
      'Direct children',
      'Descendants',
      'Subtree depth',
      'Layout width',
      'Layout height',
      'Layout params class',
      'Measured size',
      'Minimum size',
      'Padding',
      'Margin',
      'Scroll',
      'Layout requested',
    ]);
    expect(byTitle.get('DRAWING')).toHaveLength(17);
    expect(byTitle.get('INTERACTION')?.map((row) => row.label)).toEqual([
      'Enabled',
      'Clickable',
      'Long clickable',
      'Focusable',
      'Focused',
      'Selected',
    ]);
  });

  it('formats values the way the reference formatted them', () => {
    const sections = nodeDetailSections(NODE, 3, 'en');
    const rows = new Map(sections.flatMap((section) => section.rows.map((row) => [row.label, row])));
    expect(rows.get('Bounds')?.value).toBe('50, 100, 610, 180');
    expect(rows.get('Size')?.value).toBe('560 × 80');
    expect(rows.get('Local layout bounds')?.value).toBe('40, 80, 600, 160');
    expect(rows.get('Layout width')?.value).toBe('MATCH_PARENT (-1)');
    expect(rows.get('Layout height')?.value).toBe('WRAP_CONTENT (-2)');
    expect(rows.get('Measured size')?.value).toBe('1080 × 2400');
    expect(rows.get('Padding')?.value).toBe('16, 24, 16, 24');
    expect(rows.get('Margin')?.value).toBe('8, 12, 8, 12');
    expect(rows.get('Scroll')?.value).toBe('0, 12');
    expect(rows.get('Tree depth')?.value).toBe('3');
    expect(rows.get('Direct children')?.value).toBe('1');
    expect(rows.get('Descendants')?.value).toBe('1');
    expect(rows.get('Subtree depth')?.value).toBe('2');
    expect(rows.get('Alpha')?.value).toBe('0.5');
    expect(rows.get('Z')?.value).toBe('10.0');
    expect(rows.get('Elevation')?.value).toBe('8.0');
    expect(rows.get('Translation')?.value).toBe('0.0, 0.0, 2.0');
    expect(rows.get('Rotation')?.value).toBe('1.0, 2.0, 5.0');
    expect(rows.get('Scale')?.value).toBe('1.0, 1.0');
    expect(rows.get('Pivot')?.value).toBe('540.0, 1200.0');
    expect(rows.get('Clip bounds')?.value).toBe('0, 0, 1080, 2300');
    expect(rows.get('Blending')?.value).toBe('Alpha 0.5 requires blending');
    expect(rows.get('Blending')?.tone).toBe('warning');
    expect(rows.get('Layer cost')?.value).toBe('SOFTWARE');
    expect(rows.get('Layer cost')?.tone).toBe('warning');
    expect(rows.get('Hidden descendants')?.value).toBe('1');
    expect(rows.get('Hidden descendants')?.tone).toBe('info');
    expect(rows.get('Resource')?.value).toBe('com.codemx.anrdemo:id/title');
    expect(rows.get('Text')?.value).toBe('Title');
    expect(rows.get('Content description')?.value).toBe('Root container');
    expect(rows.get('Visibility')?.value).toBe('VISIBLE');
  });

  it('renders a dash for a field the device did not report', () => {
    const bare: UiNode = {
      type: 'view',
      id: 'root',
      className: 'android.view.View',
      bounds: { left: 0, top: 0, right: 10, bottom: 10 },
      visible: true,
      alpha: 1,
      children: [],
      attributes: { rawProperties: {} },
    };
    const sections = nodeDetailSections(bare, 1, 'en');
    const rows = new Map(sections.flatMap((section) => section.rows.map((row) => [row.label, row.value])));
    expect(rows.get('Class')).toBe('android.view.View');
    expect(rows.get('Resource')).toBe('—');
    expect(rows.get('Padding')).toBe('—');
    expect(rows.get('Layout type')).toBeUndefined();
    expect(rows.get('Layer type')).toBe('—');
    // A view with no raw properties has no RAW PROPERTIES section at all.
    expect(sections.map((section) => section.title)).not.toContain('RAW PROPERTIES');
  });

  it('sorts raw properties and keeps the property names the device reports', () => {
    const sections = nodeDetailSections(NODE, 1, 'en');
    const raw = sections.find((section) => section.title === 'RAW PROPERTIES');
    expect(raw?.rows.map((row) => row.label)).toEqual(['drawing:elevation', 'layout:left']);
    expect(raw?.rows[0]?.value).toBe('8.0');
  });
});
