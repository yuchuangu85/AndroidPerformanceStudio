#!/usr/bin/env node
/**
 * D2 UI gate: the hierarchy must hold 55 fps while scrolling, hit testing, and
 * zooming at 10,000 nodes.
 *
 * It drives the built application rather than a test page: Electron is started
 * with its own user-data directory, a synthetic capture is seeded into the
 * layout store, and the renderer is driven through the Chrome DevTools Protocol
 * that Electron already exposes. No extra dependency, and the numbers come from
 * the same code path a user gets.
 *
 * Zoom is reported as uncovered: the canvas has no zoom yet, and the gate says
 * so instead of quietly counting two of the three interactions as all three.
 *
 * Usage: node e2e/ui-perf.mjs [--nodes 10000] [--seconds 2] [--out perf/ui-tree.json]
 * Requires the app to be built first: pnpm --filter @aps/desktop build
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const desktop = join(here, '..', 'apps', 'desktop');
const mainEntry = join(desktop, 'out', 'main', 'index.js');
/** pnpm links the binary into the package that depends on it, not the root. */
function resolveElectron() {
  const candidates = [
    join(desktop, 'node_modules', '.bin', 'electron'),
    join(here, '..', 'node_modules', '.bin', 'electron'),
    join(here, '..', 'node_modules', 'electron', 'dist', 'Electron.app', 'Contents', 'MacOS', 'Electron'),
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate)) return candidate;
  }
  throw new Error('the electron binary was not found; run pnpm install first');
}

function argument(name, fallback) {
  const index = process.argv.indexOf('--' + name);
  return index === -1 ? fallback : (process.argv[index + 1] ?? fallback);
}

const NODES = Number(argument('nodes', '10000'));
const SECONDS = Number(argument('seconds', '2'));
const OUT = argument('out', join(here, '..', 'perf', 'ui-tree.json'));
/** The PRD gate. */
const MIN_FPS = 55;
const DEBUG_PORT = 9222 + (process.pid % 500);

/**
 * Chromium refuses to start on Linux unless its SUID helper is root-owned and
 * setuid, and a pnpm store preserves neither. Rather than fail the gate, the
 * harness relaxes the OS-level sandbox and records that it did, so a reported
 * number never claims a stricter environment than the one it ran in.
 */
function findChromeSandbox() {
  const store = join(here, '..', 'node_modules', '.pnpm');
  try {
    for (const entry of readdirSync(store, { recursive: true })) {
      const candidate = String(entry);
      if (candidate.endsWith('electron/dist/chrome-sandbox')) return join(store, candidate);
    }
  } catch {
    // No store, or an unreadable one: fall through to the relaxed path.
  }
  return undefined;
}

function sandboxArguments() {
  if (process.platform !== 'linux') return { args: [], state: 'not-applicable' };
  const helper = findChromeSandbox();
  if (helper !== undefined) {
    try {
      const stats = statSync(helper);
      if (stats.uid === 0 && (stats.mode & 0o4000) !== 0) return { args: [], state: 'enabled' };
    } catch {
      // Unreadable helper counts as unusable.
    }
  }
  return { args: ['--no-sandbox'], state: 'relaxed' };
}

/**
 * The same shape the package fixture builds: wide and deep containers with
 * leaves, nested bounds, and invisible subtrees. Kept inline because this runs
 * before any TypeScript is compiled; the node-side gate asserts the same
 * parameters produce a ten-thousand-node tree.
 */
function syntheticSnapshot(nodeCount) {
  const display = { widthPx: 1080, heightPx: 2400, density: 3 };
  const containers = [
    'android.widget.FrameLayout',
    'android.widget.LinearLayout',
    'android.widget.RelativeLayout',
    'androidx.constraintlayout.widget.ConstraintLayout',
    'androidx.recyclerview.widget.RecyclerView',
    'android.widget.ScrollView',
  ];
  const leaves = [
    'android.widget.TextView',
    'android.widget.ImageView',
    'android.widget.Button',
    'android.widget.ProgressBar',
    'android.view.View',
  ];
  let created = 0;
  const fanOut = 5;
  const build = (index, depth, bounds) => {
    created += 1;
    const isContainer = depth < 24 && index % 3 !== 2;
    const children = [];
    if (isContainer) {
      const slice = Math.max(1, Math.floor((bounds.bottom - bounds.top) / fanOut));
      for (let child = 0; child < fanOut; child += 1) {
        if (created >= nodeCount) break;
        const top = bounds.top + child * slice;
        const bottom = child === fanOut - 1 ? bounds.bottom : Math.min(bounds.bottom, top + slice);
        children.push(
          build(created, depth + 1, {
            left: bounds.left + 4,
            top: top + 4,
            right: Math.max(bounds.left + 8, bounds.right - 4),
            bottom: Math.max(top + 8, bottom - 4),
          }),
        );
      }
    }
    return {
      type: 'view',
      id: 'node:' + String(index),
      className: isContainer ? containers[index % containers.length] : leaves[index % leaves.length],
      bounds,
      visible: index === 0 || (index * 7) % 100 >= 10,
      alpha: 1,
      children,
      resourceName: 'id/view_' + String(index),
      attributes: { rawProperties: {} },
    };
  };
  const root = build(0, 0, { left: 0, top: 0, right: display.widthPx, bottom: display.heightPx });
  return {
    protocolVersion: { major: 1, minor: 1 },
    packageName: 'com.example.synthetic',
    capturedAtEpochMillis: 1,
    display,
    capabilities: { viewHierarchy: true, composeSemantics: false, screenshots: false, timeline: false },
    root,
    windows: [
      { id: 'window:main', title: 'Synthetic', type: 'ACTIVITY', bounds: { left: 0, top: 0, right: display.widthPx, bottom: display.heightPx }, root },
    ],
    defaultWindowId: 'window:main',
  };
}

function seedCapture(userDataDirectory) {
  const directory = join(userDataDirectory, 'layout-captures');
  mkdirSync(directory, { recursive: true });
  const id = 'perf-capture';
  const snapshot = syntheticSnapshot(NODES);
  writeFileSync(join(directory, id + '.json'), JSON.stringify(snapshot));
  // A one-pixel PNG placeholder: the gate measures the tree and the canvas, and
  // a real device screenshot is not something this harness has.
  writeFileSync(
    join(directory, id + '.png'),
    Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64'),
  );
  writeFileSync(
    join(directory, 'index.json'),
    JSON.stringify([{ id, packageName: snapshot.packageName, capturedAtEpochMillis: 1, nodeCount: NODES }], null, 2),
  );
}

async function waitForTarget(port, child) {
  const deadline = Date.now() + 30_000;
  let exited = false;
  child.once('exit', () => { exited = true; });
  while (Date.now() < deadline) {
    try {
      const response = await fetch('http://127.0.0.1:' + String(port) + '/json/list');
      const targets = await response.json();
      const page = targets.find((target) => target.type === 'page' && typeof target.webSocketDebuggerUrl === 'string');
      if (page !== undefined) return page.webSocketDebuggerUrl;
    } catch {
      // The port is not open yet; keep waiting.
    }
    if (exited) {
      throw new Error('the application exited before it exposed a debugging target');
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('the renderer never exposed a debugging target');
}

function callMethod(socket, id, method, params) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(method + ' timed out')), 120_000);
    const onMessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.id !== id) return;
      clearTimeout(timer);
      socket.removeEventListener('message', onMessage);
      if (message.error !== undefined) reject(new Error(JSON.stringify(message.error)));
      else resolve(message.result);
    };
    socket.addEventListener('message', onMessage);
    socket.send(JSON.stringify({ id, method, params }));
  });
}

/** The probe runs inside the renderer and returns frame statistics per action. */
function probeSource(seconds) {
  return [
    '(async () => {',
    '  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));',
    '  const destination = [...document.querySelectorAll(\'button, a\')].find((element) =>',
    '    /layout/i.test(element.textContent || \'\') || (element.textContent || \'\').includes(\'布局\'));',
    '  if (destination) destination.click();',
    '  await sleep(600);',
    '  const tree = document.querySelector(\'.tree\');',
    '  if (!tree) return { ok: false, reason: \'the tree view is not on screen\' };',
    '  const sample = async (action) => {',
    '    const stamps = [];',
    '    let running = true;',
    '    const frame = (time) => { stamps.push(time); if (running) requestAnimationFrame(frame); };',
    '    requestAnimationFrame(frame);',
    '    const started = performance.now();',
    '    while (performance.now() - started < ' + String(seconds) + ' * 1000) { action(); await sleep(16); }',
    '    running = false;',
    '    const deltas = [];',
    '    for (let index = 1; index < stamps.length; index += 1) deltas.push(stamps[index] - stamps[index - 1]);',
    '    if (deltas.length === 0) return { frames: 0, fps: 0, p95FrameMs: 0, longFrames: 0 };',
    '    deltas.sort((a, b) => a - b);',
    '    const median = deltas[Math.floor(deltas.length / 2)];',
    '    const p95 = deltas[Math.min(deltas.length - 1, Math.floor(deltas.length * 0.95))];',
    '    return { frames: deltas.length, fps: Math.round(1000 / median), p95FrameMs: Math.round(p95 * 100) / 100, longFrames: deltas.filter((delta) => delta > 20).length };',
    '  };',
    '  const scroll = await sample(() => { tree.scrollTop = (tree.scrollTop + 220) % Math.max(1, tree.scrollHeight - tree.clientHeight); });',
    '  const image = document.querySelector(\'.preview img\');',
    '  const hit = await sample(() => {',
    '    if (!image) return;',
    '    const rect = image.getBoundingClientRect();',
    '    image.dispatchEvent(new MouseEvent(\'click\', { bubbles: true, clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2 }));',
    '  });',
    '  return { ok: true, scroll, hit, zoom: null, rows: document.querySelectorAll(\'.tree__row\').length };',
    '})()',
  ].join('\n');
}

/** A GUI-less environment would otherwise wait forever on a window that never opens. */
const WATCHDOG_MS = 120_000;
const watchdog = setTimeout(() => {
  console.error('the UI gate timed out; a display (or xvfb) is required to run it');
  process.exit(2);
}, WATCHDOG_MS);
watchdog.unref();

async function main() {
  if (!existsSync(mainEntry)) {
    throw new Error('build the app first: pnpm --filter @aps/desktop build');
  }
  const userData = mkdtempSync(join(tmpdir(), 'aps-ui-perf-'));
  seedCapture(userData);
  const sandbox = sandboxArguments();
  const child = spawn(
    resolveElectron(),
    [mainEntry, '--user-data-dir=' + userData, '--remote-debugging-port=' + String(DEBUG_PORT), ...sandbox.args],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  );
  let stderr = '';
  child.stderr.on('data', (chunk) => { stderr += String(chunk); });
  try {
    const endpoint = await waitForTarget(DEBUG_PORT, child);
    const socket = new WebSocket(endpoint);
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', () => resolve(undefined));
      socket.addEventListener('error', () => reject(new Error('the debugging socket failed to open')));
    });
    await callMethod(socket, 1, 'Runtime.enable', {});
    const result = await callMethod(socket, 2, 'Runtime.evaluate', {
      expression: probeSource(SECONDS),
      awaitPromise: true,
      returnByValue: true,
    });
    const value = result.result.value;
    const report = {
      nodeCount: NODES,
      seconds: SECONDS,
      minFps: MIN_FPS,
      scroll: value?.scroll ?? null,
      hit: value?.hit ?? null,
      zoom: value?.zoom ?? null,
      rows: value?.rows ?? 0,
      zoomCovered: false,
      osSandbox: sandbox.state,
      ok: value?.ok === true,
      ...(value?.ok === true ? {} : { reason: value?.reason ?? 'the probe did not run' }),
    };
    mkdirSync(dirname(OUT), { recursive: true });
    writeFileSync(OUT, JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));
    const scrollOk = report.scroll !== null && report.scroll.fps >= MIN_FPS;
    const hitOk = report.hit !== null && report.hit.fps >= MIN_FPS;
    if (!report.ok || !scrollOk || !hitOk) {
      console.error('the UI gate did not pass; zoom is not covered because the canvas has no zoom yet');
      process.exitCode = 1;
    }
  } catch (error) {
    console.error(String(error));
    if (stderr.length > 0) console.error(stderr.slice(-2000));
    process.exitCode = 1;
  } finally {
    child.kill('SIGTERM');
    rmSync(userData, { recursive: true, force: true });
  }
}

await main();
