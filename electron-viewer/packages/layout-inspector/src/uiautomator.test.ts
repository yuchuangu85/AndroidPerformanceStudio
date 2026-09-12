import { describe, expect, it } from 'vitest';
import { AdbOutputParseError } from './errors.js';
import { parseBoundsAttribute, parseUiAutomatorHierarchy } from './uiautomator.js';

const HIERARCHY = [
  "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>",
  '<hierarchy rotation="0">',
  '  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,1920]">',
  '    <node index="0" text="Hello &amp; welcome" resource-id="com.example:id/title" class="android.widget.TextView" package="com.example" content-desc="Title" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[40,100][1040,220]" />',
  '    <node index="1" text="" resource-id="com.example:id/list" class="androidx.recyclerview.widget.RecyclerView" package="com.example" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="true" focused="true" scrollable="true" long-clickable="false" password="false" selected="true" bounds="[0,220][1080,1920]" />',
  '  </node>',
  '</hierarchy>',
].join('\n');

describe('parseBoundsAttribute', () => {
  it('parses the uiautomator bounds form', () => {
    expect(parseBoundsAttribute('[0,0][1080,1920]')).toEqual({ left: 0, top: 0, right: 1080, bottom: 1920 });
    expect(parseBoundsAttribute('[40,100][1040,220]')).toEqual({ left: 40, top: 100, right: 1040, bottom: 220 });
  });

  it('rejects malformed bounds', () => {
    expect(parseBoundsAttribute('0,0,1080,1920')).toBeUndefined();
    expect(parseBoundsAttribute('')).toBeUndefined();
  });
});

describe('parseUiAutomatorHierarchy', () => {
  it('builds a tree with structural path ids', () => {
    const hierarchy = parseUiAutomatorHierarchy(HIERARCHY);
    expect(hierarchy.rotation).toBe(0);
    expect(hierarchy.root.type).toBe('view');
    expect(hierarchy.root.className).toBe('android.widget.FrameLayout');
    expect(hierarchy.root.id).toBe('root');
    expect(hierarchy.root.children.map((child) => child.id)).toEqual(['root/0', 'root/1']);
    expect(hierarchy.root.children[0]?.className).toBe('android.widget.TextView');
  });

  it('maps text, resource ids, and boolean attributes', () => {
    const hierarchy = parseUiAutomatorHierarchy(HIERARCHY);
    const title = hierarchy.root.children[0];
    if (title?.type !== 'view') throw new Error('expected a view node');
    expect(title.text).toBe('Hello & welcome');
    expect(title.resourceName).toBe('com.example:id/title');
    expect(title.attributes.clickable).toBe(true);
    expect(title.attributes.enabled).toBe(true);
    expect(title.attributes.contentDescription).toBe('Title');
    expect(title.attributes.rawProperties['package']).toBe('com.example');

    const list = hierarchy.root.children[1];
    if (list?.type !== 'view') throw new Error('expected a view node');
    expect(list.attributes.selected).toBe(true);
    expect(list.attributes.focused).toBe(true);
  });

  it('reads the rotation attribute', () => {
    const rotated = HIERARCHY.replace('rotation="0"', 'rotation="1"');
    expect(parseUiAutomatorHierarchy(rotated).rotation).toBe(1);
  });

  it('rejects malformed hierarchies', () => {
    expect(() => parseUiAutomatorHierarchy('<hierarchy rotation="0"></hierarchy>')).toThrow(AdbOutputParseError);
    expect(() => parseUiAutomatorHierarchy('<hierarchy><node class="a" bounds="[0,0][1,1]"></hierarchy>')).toThrow(
      AdbOutputParseError,
    );
    expect(() =>
      parseUiAutomatorHierarchy('<hierarchy><node class="a" bounds="not-bounds"></node></hierarchy>'),
    ).toThrow(AdbOutputParseError);
    expect(() => parseUiAutomatorHierarchy('<hierarchy><node bounds="[0,0][1,1]"></node></hierarchy>')).toThrow(
      AdbOutputParseError,
    );
  });
});
