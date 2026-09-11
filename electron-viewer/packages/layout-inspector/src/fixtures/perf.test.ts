/**
 * D2 gate: 10,000 nodes must load in under three seconds, and the stages must be
 * reported separately so a failure says where the time went.
 *
 * Gated behind APS_PERF like the other benchmarks, but the workload is also a
 * correctness check: the walked node count has to match the generator, otherwise
 * a fixture that silently shrank would make the gate pass for the wrong reason.
 */
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { decodeLayoutSnapshot } from '../codec.js';
import { hitTestCandidates } from '../hit-test.js';
import { flattenVisibleTree, type TreeRow } from '../tree.js';
import { walkNode } from '../model.js';
import { DEFAULT_SYNTHETIC_NODES, syntheticWireSnapshot } from './synthetic-tree.js';

const PERF = process.env['APS_PERF'] === '1';
const NODE_COUNT = Number(process.env['APS_PERF_NODES'] ?? DEFAULT_SYNTHETIC_NODES);
/** The PRD gate, in milliseconds. */
const LOAD_BUDGET_MS = 3000;

const directory = PERF ? mkdtempSync(join(tmpdir(), 'aps-layout-perf-')) : undefined;

afterAll(() => {
  if (directory !== undefined) rmSync(directory, { recursive: true, force: true });
});

function elapsed(body: () => void): number {
  const start = process.hrtime.bigint();
  body();
  return Number(process.hrtime.bigint() - start) / 1e6;
}

describe.runIf(PERF)('layout hierarchy gate', () => {
  it('loads the synthetic hierarchy within the budget', () => {
    const wire = syntheticWireSnapshot({ nodeCount: NODE_COUNT });
    const file = join(directory ?? tmpdir(), 'snapshot.json');
    writeFileSync(file, wire);

    // Read: what the capture store does before the decoder sees anything.
    let text = '';
    const readMs = elapsed(() => {
      text = readFileSync(file, 'utf8');
    });

    // Decode: JSON.parse plus the zod schema walk, the usual suspect at scale.
    let decoded: ReturnType<typeof decodeLayoutSnapshot> | undefined;
    const decodeMs = elapsed(() => {
      decoded = decodeLayoutSnapshot(text);
    });
    if (decoded === undefined) throw new Error('the fixture did not decode');
    const snapshot = decoded;

    let walked = 0;
    walkNode(snapshot.root, () => {
      walked += 1;
    });
    expect(walked).toBe(NODE_COUNT);

    // Flatten: every node expanded, which is the worst case for the tree view.
    const expanded = new Set<string>();
    walkNode(snapshot.root, (node) => expanded.add(node.id));
    let rows: readonly TreeRow[] = [];
    const flattenMs = elapsed(() => {
      rows = flattenVisibleTree(snapshot.root, { expanded });
    });
    expect(rows.length).toBe(NODE_COUNT);

    // Hit test: a click in the middle runs the full-tree candidate scan.
    let candidates = 0;
    const hitTestMs = elapsed(() => {
      candidates = hitTestCandidates(snapshot.root, {
        x: Math.floor(snapshot.display.widthPx / 2),
        y: Math.floor(snapshot.display.heightPx / 2),
      }).length;
    });

    const totalMs = readMs + decodeMs + flattenMs + hitTestMs;
    const report = {
      nodeCount: NODE_COUNT,
      rowCount: rows.length,
      hitCandidates: candidates,
      readMs: Number(readMs.toFixed(2)),
      decodeMs: Number(decodeMs.toFixed(2)),
      flattenMs: Number(flattenMs.toFixed(2)),
      hitTestMs: Number(hitTestMs.toFixed(2)),
      totalMs: Number(totalMs.toFixed(2)),
      budgetMs: LOAD_BUDGET_MS,
    };
    const out = process.env['APS_PERF_OUT'];
    if (out !== undefined) writeFileSync(out, JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));

    expect(totalMs).toBeLessThanOrEqual(LOAD_BUDGET_MS);
  });
});
