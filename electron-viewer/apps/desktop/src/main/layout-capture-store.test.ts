import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { CURRENT_PROTOCOL_VERSION, walkNode, type LayoutSnapshot } from '@aps/layout-inspector';
import { LayoutCaptureStore } from './layout-capture-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-layout-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function snapshot(capturedAt: number): LayoutSnapshot {
  return {
    protocolVersion: CURRENT_PROTOCOL_VERSION,
    packageName: 'com.example.app',
    capturedAtEpochMillis: capturedAt,
    display: { widthPx: 1080, heightPx: 1920, density: 2.75 },
    capabilities: { viewHierarchy: true, composeSemantics: false, screenshots: true, timeline: false },
    root: {
      type: 'view',
      id: '0',
      className: 'android.widget.FrameLayout',
      bounds: { left: 0, top: 0, right: 1080, bottom: 1920 },
      visible: true,
      alpha: 1,
      children: [],
      attributes: { rawProperties: {} },
    },
    windows: [],
  };
}

function countNodes(value: LayoutSnapshot): number {
  let count = 0;
  walkNode(value.root, () => {
    count += 1;
  });
  return count;
}

describe('LayoutCaptureStore', () => {
  it('round-trips a snapshot and its screenshot', async () => {
    const directory = await temporaryDirectory();
    const store = new LayoutCaptureStore(join(directory, 'captures'), { countNodes });
    expect(await store.list()).toEqual([]);

    const record = await store.add(snapshot(1000), Buffer.from([1, 2, 3]));
    expect(record).toEqual({
      id: '1000',
      packageName: 'com.example.app',
      capturedAtEpochMillis: 1000,
      nodeCount: 1,
    });
    const loaded = await store.loadSnapshot('1000');
    expect(loaded?.packageName).toBe('com.example.app');
    expect(await store.loadScreenshot('1000')).toEqual(Buffer.from([1, 2, 3]));
    expect((await store.list()).map((entry) => entry.id)).toEqual(['1000']);
  });

  it('keeps the newest capture first and deduplicates ids', async () => {
    const directory = await temporaryDirectory();
    const store = new LayoutCaptureStore(join(directory, 'captures'), { countNodes });
    await store.add(snapshot(1000), Buffer.from([1]));
    await store.add(snapshot(2000), Buffer.from([2]));
    await store.add(snapshot(1000), Buffer.from([3]));
    const records = await store.list();
    expect(records.map((entry) => entry.id)).toEqual(['1000', '2000']);
    expect(await store.loadScreenshot('1000')).toEqual(Buffer.from([3]));
  });

  it('returns undefined for unknown or malformed captures', async () => {
    const directory = await temporaryDirectory();
    const store = new LayoutCaptureStore(join(directory, 'captures'), { countNodes });
    expect(await store.loadSnapshot('missing')).toBeUndefined();
    expect(await store.loadScreenshot('missing')).toBeUndefined();
  });
});
