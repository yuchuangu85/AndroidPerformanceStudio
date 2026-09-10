import { describe, expect, it } from 'vitest';
import { decodeLayoutSnapshot, encodeLayoutSnapshot, UnsupportedProtocolVersionError } from './codec.js';
import { walkNode } from './model.js';
import { effectiveDefaultWindowId, effectiveWindows, LEGACY_WINDOW_ID } from './snapshot.js';

const WIRE = {
  protocolVersion: { major: 1, minor: 1 },
  packageName: 'com.example.launcher',
  capturedAtEpochMillis: 1_700_000_000_000,
  display: { widthPx: 1080, heightPx: 1920, density: 2.75 },
  capabilities: { viewHierarchy: true, screenshots: true },
  root: {
    id: '0',
    className: 'android.widget.FrameLayout',
    bounds: { left: 0, top: 0, right: 1080, bottom: 1920 },
    children: [
      {
        id: '0.0',
        className: 'android.widget.TextView',
        bounds: { left: 10, top: 10, right: 200, bottom: 60 },
        text: 'hi',
        children: [],
      },
    ],
  },
  windows: [],
};

describe('layout snapshot codec', () => {
  it('decodes a snapshot and fills capability defaults', () => {
    const snapshot = decodeLayoutSnapshot(JSON.stringify(WIRE));
    expect(snapshot.packageName).toBe('com.example.launcher');
    expect(snapshot.capabilities).toEqual({
      viewHierarchy: true,
      composeSemantics: false,
      screenshots: true,
      timeline: false,
    });
    expect(snapshot.root.children).toHaveLength(1);
    expect(snapshot.root.children[0]?.text).toBe('hi');
  });

  it('accepts protocol 1.0 and rejects another major', () => {
    const older = { ...WIRE, protocolVersion: { major: 1, minor: 0 } };
    expect(decodeLayoutSnapshot(JSON.stringify(older)).protocolVersion).toEqual({ major: 1, minor: 0 });
    const newer = { ...WIRE, protocolVersion: { major: 2, minor: 0 } };
    expect(() => decodeLayoutSnapshot(JSON.stringify(newer))).toThrow(UnsupportedProtocolVersionError);
  });

  it('round-trips through the encoder', () => {
    const snapshot = decodeLayoutSnapshot(JSON.stringify(WIRE));
    expect(decodeLayoutSnapshot(encodeLayoutSnapshot(snapshot))).toEqual(snapshot);
  });

  it('falls back to a legacy window when none are declared', () => {
    const snapshot = decodeLayoutSnapshot(JSON.stringify(WIRE));
    const windows = effectiveWindows(snapshot);
    expect(windows).toHaveLength(1);
    expect(windows[0]?.id).toBe(LEGACY_WINDOW_ID);
    expect(windows[0]?.title).toBe('launcher');
    expect(effectiveDefaultWindowId(snapshot)).toBe(LEGACY_WINDOW_ID);
  });

  it('walks every node with its depth', () => {
    const snapshot = decodeLayoutSnapshot(JSON.stringify(WIRE));
    const visited: Array<[string, number]> = [];
    walkNode(snapshot.root, (node, depth) => visited.push([node.id, depth]));
    expect(visited).toEqual([
      ['0', 0],
      ['0.0', 1],
    ]);
  });
});
