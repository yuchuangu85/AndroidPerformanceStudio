'use strict';

const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const test = require('node:test');
const { TextEncoder } = require('node:util');
const vm = require('node:vm');

const SCRIPT_PATH = join(__dirname, 'aps-query-api.js');
const SOURCE = readFileSync(SCRIPT_PATH, 'utf8');
const PROFILE_URL = 'http://127.0.0.1:8765/profiles/owned-profile.json';
const METHODS = ['capabilities', 'context', 'threads', 'selection', 'callTree', 'function', 'analysis'];
const MAX_BYTES = 60 * 1024;

function freezeData(value) {
  if (value && typeof value === 'object' && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) freezeData(child);
    Object.freeze(value);
  }
  return value;
}

function makeTree(nodes = [
  { index: 0, parent: -1, depth: 0, name: '(root)', total: 100, self: 0 },
  { index: 1, parent: 0, depth: 1, name: 'sameFunction', total: 60, self: 60 },
  { index: 2, parent: 0, depth: 1, name: 'dispatchWork', total: 40, self: 3.5 },
  { index: 3, parent: 2, depth: 2, name: 'sameFunction', total: 36.5, self: 4.125 },
  { index: 4, parent: 3, depth: 3, name: 'leaf', total: 32.375, self: 32.375 },
], total = 100) {
  const frozenNodes = freezeData(nodes.map(node => ({ ...node })));
  const byIndex = new Map(frozenNodes.map(node => [node.index, node]));
  const children = new Map();
  for (const parent of [-1, ...byIndex.keys()]) {
    children.set(parent, Object.freeze(frozenNodes.filter(node => node.parent === parent).map(node => node.index)));
  }
  const lookup = index => {
    const node = byIndex.get(index);
    if (!node) throw new Error('Unknown upstream call node');
    return node;
  };
  return {
    nodes: frozenNodes,
    getRoots: () => children.get(-1),
    getChildren: index => children.get(index) || Object.freeze([]),
    getParent: index => lookup(index).parent,
    getDepth: index => byIndex.get(index)?.depth,
    hasChildren: index => (children.get(index)?.length || 0) > 0,
    getTotal: () => total,
    getNodeData(index) {
      const node = lookup(index);
      return Object.freeze({
        funcName: node.name,
        total: node.total,
        self: node.self,
        totalRelative: node.total / total,
        selfRelative: node.self / total,
      });
    },
    getDisplayData(index) {
      const node = lookup(index);
      // Formatting must never become the source of numeric evidence.
      return Object.freeze({
        name: 'formatted display name',
        total: '9,999,999',
        self: '0.000 ms',
        totalPercent: '99.99%',
        lib: node.library || '/private/source/path/libfixture.so',
        categoryName: node.category || 'Native',
      });
    },
  };
}

function makeHarness(options = {}) {
  const calls = { getState: 0, tree: 0, dispatch: 0, actions: 0, transport: [] };
  const profile = freezeData({ threads: options.threads || [
    { name: 'RenderThread', pid: 42, tid: 101 },
    { name: 'Worker', pid: 42, tid: 102 },
    { name: 'Main', pid: 84, tid: 201 },
  ] });
  let state = Object.freeze({
    profile,
    profileUrl: PROFILE_URL,
    dataSource: 'from-url',
    tree: options.tree || makeTree(),
    selectedThreads: Object.freeze(new Set([0, 2])),
    selectedNode: 3,
    range: freezeData({ start: 2, end: 8 }),
    committedRange: freezeData({ start: 0, end: 10 }),
    tab: 'calltree',
    inverted: false,
    search: 'sameFunction',
    weightType: 'samples',
    filteredThread: freezeData({ name: 'Merged selected threads' }),
  });
  const selectors = {
    profile: {
      getProfileOrNull: s => s.profile,
      getPreviewSelectionRange: s => s.range,
      getCommittedRange: s => s.committedRange,
    },
    urlState: {
      getProfileUrl: s => s.profileUrl,
      getDataSource: s => s.dataSource,
      getSelectedThreadIndexes: s => s.selectedThreads,
      getSelectedTab: s => s.tab,
      getInvertCallstack: s => s.inverted,
      getCurrentSearchString: s => s.search,
    },
    selectedThread: {
      getCallTree(s) { calls.tree += 1; return s.tree; },
      getSelectedCallNodeIndex: s => s.selectedNode,
      getWeightTypeForCallTree: s => s.weightType,
      getFilteredThread: s => s.filteredThread,
    },
  };
  const forbiddenTransport = name => () => {
    calls.transport.push(name);
    throw new Error(`Unexpected ${name} without a page ID`);
  };
  const root = {
    document: { currentScript: { dataset: { apsProfileUrl: options.scriptProfileUrl || PROFILE_URL } } },
    TextEncoder,
    selectors,
    getState() { calls.getState += 1; return state; },
    dispatch() { calls.dispatch += 1; throw new Error('Read-only API dispatched'); },
    actions: new Proxy({}, { get() { calls.actions += 1; throw new Error('Read-only API accessed an action'); } }),
    fetch: forbiddenTransport('fetch'),
    setTimeout: forbiddenTransport('setTimeout'),
    clearTimeout: forbiddenTransport('clearTimeout'),
    addEventListener: forbiddenTransport('addEventListener'),
  };
  if (options.documentId) root.crypto = { randomUUID: () => options.documentId };
  root.globalThis = root;
  root.window = root;
  const context = vm.createContext(root);
  vm.runInContext(SOURCE, context, { filename: SCRIPT_PATH, timeout: 1000 });
  const api = root.APSProfilerQuery;
  // Normalize VM prototypes without sharing mutable response objects with tests.
  const request = value => JSON.parse(JSON.stringify(api.query(value)));
  return {
    root, selectors, calls, api, context, request,
    get state() { return state; },
    update(patch) { state = Object.freeze({ ...state, ...patch }); },
    query(method, params, extra = {}) { return request({ protocolVersion: 1, method, params, ...extra }); },
  };
}

function success(result) {
  assert.equal(result.ok, true, JSON.stringify(result));
  assert.ok(Number.isSafeInteger(result.revision) && result.revision >= 1);
  return result.data;
}

function failure(result, code) {
  assert.equal(result.ok, false, JSON.stringify(result));
  assert.equal(result.code, code);
  assert.equal(typeof result.message, 'string');
  assert.equal(Object.hasOwn(result, 'data'), false);
}

test('installation is lazy, immutable, idempotent, and starts no transport without pageId', () => {
  const h = makeHarness();
  assert.deepEqual(Object.keys(h.root.document.currentScript.dataset), ['apsProfileUrl']);
  assert.equal(Object.hasOwn(h.root, 'crypto'), false);
  assert.equal(h.calls.getState, 0);
  assert.equal(Object.isFrozen(h.api), true);
  assert.equal(h.api.protocolVersion, 1);
  const descriptor = Object.getOwnPropertyDescriptor(h.root, 'APSProfilerQuery');
  assert.equal(descriptor.writable, false);
  assert.equal(descriptor.configurable, false);
  vm.runInContext(SOURCE, h.context, { filename: SCRIPT_PATH, timeout: 1000 });
  assert.equal(h.root.APSProfilerQuery, h.api);
  assert.equal(h.calls.getState, 0);
  assert.deepEqual(h.calls.transport, []);
});

test('capabilities describes exactly the read-only protocol without requiring upstream', () => {
  const h = makeHarness();
  delete h.root.getState;
  delete h.root.selectors;
  assert.deepEqual(success(h.query('capabilities')), {
    protocolVersion: 1, methods: METHODS, readOnly: true, maximumRows: 100, maximumDepth: 64,
  });
  assert.equal(h.calls.getState, 0);
});

test('context captures selected thread identities, effective and committed ranges, and numeric units', () => {
  const h = makeHarness();
  const first = h.query('context');
  assert.deepEqual(success(first), {
    threads: [
      { index: 0, name: 'RenderThread', pid: '42', tid: '101' },
      { index: 2, name: 'Main', pid: '84', tid: '201' },
    ],
    range: { start: 2, end: 8, unit: 'ms' },
    committedRange: { start: 0, end: 10, unit: 'ms' },
    tab: 'calltree', inverted: false, search: 'sameFunction', weightType: 'samples',
    totalWeight: 100, selectedCallNodeIndex: 3, documentId: 'standalone',
    semantics: 'Sample weights are not exact method duration or scheduler evidence.',
  });
  assert.equal(first.revision, 1);
  assert.equal(h.query('context').revision, first.revision);
});

test('threads enumerates profile threads independently of the current selection and honors limits', () => {
  const h = makeHarness();
  const all = success(h.query('threads'));
  assert.deepEqual(all.threads.map(thread => thread.index), [0, 1, 2]);
  assert.equal(all.truncated, false);
  const limited = success(h.query('threads', { limit: 1 }));
  assert.deepEqual(limited.threads, [all.threads[0]]);
  assert.equal(limited.truncated, true);
  assert.deepEqual([...h.state.selectedThreads], [0, 2]);
});

test('selection exposes numeric metrics, ordered ancestors, direct children, and scoped IDs', () => {
  const h = makeHarness();
  const result = h.query('selection');
  const { context, selection } = success(result);
  assert.equal(context.selectedCallNodeIndex, 3);
  assert.deepEqual(selection.ancestors.map(node => node.nodeId), ['fp:standalone:1:0', 'fp:standalone:1:2']);
  assert.deepEqual(selection.children.map(node => node.nodeId), ['fp:standalone:1:4']);
  assert.equal(selection.ancestryTruncated, false);
  assert.equal(selection.childrenTruncated, false);
  assert.deepEqual(selection.node, {
    nodeId: 'fp:standalone:1:3', parentId: 'fp:standalone:1:2', depth: 2, name: 'sameFunction',
    library: 'libfixture.so', category: 'Native', totalWeight: 36.5, selfWeight: 4.125,
    totalShare: 0.365, selfShare: 0.04125, weightType: 'samples', hasChildren: true,
  });
});

test('selection distinguishes call-node zero from no selection', () => {
  const h = makeHarness();
  h.update({ selectedNode: 0 });
  const root = success(h.query('selection')).selection;
  assert.equal(root.node.nodeId, 'fp:standalone:1:0');
  assert.equal(root.node.parentId, null);
  assert.deepEqual(root.ancestors, []);
  h.update({ selectedNode: null });
  assert.equal(success(h.query('selection')).selection, null);
  assert.equal(success(h.query('function')).selection, null);
});

test('callTree returns bounded depth-first rows with numeric values, not display strings', () => {
  const h = makeHarness();
  const result = success(h.query('callTree'));
  assert.deepEqual(result.rows.map(row => row.nodeId), ['fp:standalone:1:0', 'fp:standalone:1:1', 'fp:standalone:1:2', 'fp:standalone:1:3', 'fp:standalone:1:4']);
  assert.equal(result.truncated, false);
  for (const row of result.rows) {
    const index = Number(row.nodeId.split(':')[3]);
    const raw = h.state.tree.getNodeData(index);
    assert.equal(row.totalWeight, raw.total);
    assert.equal(row.selfWeight, raw.self);
    assert.equal(row.totalShare, raw.totalRelative);
    assert.equal(row.selfShare, raw.selfRelative);
    assert.equal(row.name, raw.funcName);
  }
  const limited = success(h.query('callTree', { limit: 2 }));
  assert.equal(limited.rows.length, 2);
  assert.equal(limited.truncated, true);
  const shallow = success(h.query('callTree', { maxDepth: 1 }));
  assert.deepEqual(shallow.rows.map(row => row.nodeId), ['fp:standalone:1:0']);
  assert.equal(shallow.truncated, true);
  const subtree = success(h.query('callTree', { rootNodeId: 'fp:standalone:1:3' }));
  assert.deepEqual(subtree.rows.map(row => row.nodeId), ['fp:standalone:1:3', 'fp:standalone:1:4']);
  assert.equal(subtree.truncated, false);
});

test('function uses call-node identity, keeping identical function names on different paths distinct', () => {
  const h = makeHarness();
  const duplicates = success(h.query('callTree')).rows.filter(row => row.name === 'sameFunction');
  assert.equal(duplicates.length, 2);
  assert.notEqual(duplicates[0].nodeId, duplicates[1].nodeId);
  const first = success(h.query('function', { nodeId: duplicates[0].nodeId })).selection;
  const second = success(h.query('function', { nodeId: duplicates[1].nodeId })).selection;
  assert.equal(first.node.totalWeight, 60);
  assert.equal(second.node.totalWeight, 36.5);
  assert.deepEqual(first.ancestors.map(node => node.nodeId), ['fp:standalone:1:0']);
  assert.deepEqual(second.ancestors.map(node => node.nodeId), ['fp:standalone:1:0', 'fp:standalone:1:2']);
  assert.equal(success(h.query('function')).selection.node.nodeId, duplicates[1].nodeId);
});

test('function limits ancestors and children with explicit truncation markers', () => {
  const h = makeHarness();
  const ancestorLimited = success(h.query('function', { maxDepth: 1 })).selection;
  assert.deepEqual(ancestorLimited.ancestors.map(node => node.nodeId), ['fp:standalone:1:2']);
  assert.equal(ancestorLimited.ancestryTruncated, true);
  const childLimited = success(h.query('function', { nodeId: 'fp:standalone:1:0', limit: 1 })).selection;
  assert.equal(childLimited.children.length, 1);
  assert.equal(childLimited.childrenTruncated, true);
});

test('analysis combines selection, tree, and context from the same revision', () => {
  const h = makeHarness();
  const result = h.query('analysis', { limit: 2, maxDepth: 2 });
  const analysis = success(result);
  assert.deepEqual(analysis.context, success(h.query('context')));
  assert.deepEqual(analysis.selection, success(h.query('selection', { limit: 2, maxDepth: 2 })).selection);
  assert.deepEqual(analysis.callTree.rows, success(h.query('callTree', { limit: 2, maxDepth: 2 })).rows);
  assert.equal(analysis.callTree.truncated, true);
  assert.ok(analysis.callTree.rows.every(row => row.nodeId.startsWith(`fp:standalone:${result.revision}:`)));
});

test('all supported weight types preserve raw numeric metrics without duration conversion', () => {
  const h = makeHarness();
  for (const weightType of ['samples', 'tracing-ms', 'bytes']) {
    h.update({ weightType });
    const data = success(h.query('selection'));
    assert.equal(data.context.weightType, weightType);
    assert.equal(data.selection.node.weightType, weightType);
    assert.equal(data.selection.node.totalWeight, 36.5);
    assert.equal(data.selection.node.selfWeight, 4.125);
  }
});

test('all methods are read-only and never access dispatch, actions, or transport', () => {
  const h = makeHarness();
  const initialState = h.state;
  const initialProfile = JSON.stringify(h.state.profile);
  const initialNodes = JSON.stringify(h.state.tree.nodes);
  for (const method of METHODS) success(h.query(method));
  failure(h.query('dispatch', { type: 'CHANGE_SELECTED_THREAD' }), 'E_PROTOCOL');
  assert.equal(h.state, initialState);
  assert.equal(JSON.stringify(h.state.profile), initialProfile);
  assert.equal(JSON.stringify(h.state.tree.nodes), initialNodes);
  assert.deepEqual([...h.state.selectedThreads], [0, 2]);
  assert.equal(h.calls.dispatch, 0);
  assert.equal(h.calls.actions, 0);
  assert.deepEqual(h.calls.transport, []);
});

for (const [name, change] of [
  ['selection', h => h.update({ selectedNode: 1 })],
  ['preview range', h => h.update({ range: freezeData({ start: 3, end: 7 }) })],
  ['committed range', h => h.update({ committedRange: freezeData({ start: 1, end: 9 }) })],
  ['tree identity with identical metrics', h => h.update({ tree: makeTree() })],
  ['profile identity', h => h.update({ profile: freezeData({ ...h.state.profile }) })],
  ['selected threads', h => h.update({ selectedThreads: Object.freeze(new Set([1])) })],
  ['search', h => h.update({ search: 'leaf' })],
  ['inversion', h => h.update({ inverted: true })],
  ['supported tab', h => h.update({ tab: 'flame-graph' })],
]) {
  test(`${name} changes advance revisions and reject old expectations and node IDs`, () => {
    const h = makeHarness();
    const before = h.query('selection');
    const oldId = success(before).selection.node.nodeId;
    change(h);
    failure(h.query('context', undefined, { expectedRevision: before.revision }), 'E_STALE');
    failure(h.query('function', { nodeId: oldId }), 'E_STALE');
    failure(h.query('callTree', { rootNodeId: oldId }), 'E_STALE');
    const after = h.query('selection');
    const selection = success(after).selection;
    assert.equal(after.revision, before.revision + 1);
    assert.ok(selection.node.nodeId.startsWith(`fp:standalone:${after.revision}:`));
    assert.equal(h.query('context').revision, after.revision);
    success(h.query('context', { expectedDocumentId: 'standalone' }, { expectedRevision: after.revision }));
  });
}

test('document UUIDs prevent reloaded pages with equal revisions from accepting old node IDs', () => {
  const previousDocument = '11111111-1111-4111-8111-111111111111';
  const currentDocument = '22222222-2222-4222-8222-222222222222';
  const before = makeHarness({ documentId: previousDocument });
  const after = makeHarness({ documentId: currentDocument });
  const previous = before.query('selection');
  const current = after.query('selection');
  const oldSelection = success(previous);
  const newSelection = success(current);
  assert.equal(previous.revision, current.revision);
  assert.equal(oldSelection.context.documentId, previousDocument);
  assert.equal(newSelection.context.documentId, currentDocument);
  assert.equal(oldSelection.selection.node.nodeId, `fp:${previousDocument}:1:3`);
  assert.equal(newSelection.selection.node.nodeId, `fp:${currentDocument}:1:3`);
  const staleId = oldSelection.selection.node.nodeId;
  failure(after.query('function', { nodeId: staleId }), 'E_STALE');
  failure(after.query('selection', { nodeId: staleId }), 'E_STALE');
  failure(after.query('analysis', { nodeId: staleId }), 'E_STALE');
  failure(after.query('callTree', { rootNodeId: staleId }), 'E_STALE');
  success(after.query('function', { nodeId: newSelection.selection.node.nodeId }));
  failure(after.query('analysis', undefined, { expectedRevision: previous.revision }), 'E_PROTOCOL');
  failure(after.query('analysis', { expectedDocumentId: previousDocument }, { expectedRevision: previous.revision }), 'E_STALE');
  success(after.query('analysis', { expectedDocumentId: currentDocument }, { expectedRevision: current.revision }));
  assert.deepEqual(before.calls.transport, []);
  assert.deepEqual(after.calls.transport, []);
});

test('deep selected nodes keep reusable IDs while traversal remains bounded', () => {
  const nodes = Array.from({ length: 514 }, (_, index) => ({
    index, parent: index - 1, depth: index, name: `node-${index}`, total: 100, self: 1,
  }));
  const h = makeHarness({ tree: makeTree(nodes) });
  h.update({ selectedNode: 513 });
  const first = h.query('selection', { maxDepth: 8 });
  const selection = success(first).selection;
  assert.equal(selection.node.depth, 513);
  assert.equal(selection.ancestors.length, 8);
  assert.equal(selection.ancestryTruncated, true);
  const next = success(h.query('function', { nodeId: selection.node.nodeId, maxDepth: 8 }, { expectedRevision: first.revision }));
  assert.equal(next.selection.node.nodeId, selection.node.nodeId);
});

test('missing required upstream namespaces and methods fail closed', async t => {
  const removals = [
    ['getState', h => delete h.root.getState],
    ['selectors', h => delete h.root.selectors],
    ...['profile', 'urlState', 'selectedThread'].map(namespace =>
      [namespace, h => delete h.selectors[namespace]]),
    ...Object.entries({
      profile: ['getProfileOrNull', 'getPreviewSelectionRange', 'getCommittedRange'],
      urlState: ['getProfileUrl', 'getDataSource', 'getSelectedThreadIndexes', 'getSelectedTab', 'getInvertCallstack', 'getCurrentSearchString'],
      selectedThread: ['getCallTree', 'getSelectedCallNodeIndex', 'getWeightTypeForCallTree', 'getFilteredThread'],
    }).flatMap(([namespace, methods]) => methods.map(method =>
      [`${namespace}.${method}`, h => delete h.selectors[namespace][method]])),
    ...['getRoots', 'getChildren', 'getParent', 'getDepth', 'getNodeData', 'getDisplayData', 'getTotal', 'hasChildren'].map(method =>
      [`tree.${method}`, h => delete h.state.tree[method]]),
  ];
  for (const [name, remove] of removals) {
    await t.test(name, () => {
      const h = makeHarness();
      remove(h);
      failure(h.query('context'), 'E_UPSTREAM_INCOMPATIBLE');
      success(h.query('capabilities'));
    });
  }
});

test('missing profile and unavailable numeric context fail closed', () => {
  const h = makeHarness();
  h.update({ profile: null });
  failure(h.query('context'), 'E_NOT_READY');
  assert.equal(h.calls.tree, 0);
  const range = makeHarness();
  range.update({ range: { start: NaN, end: 8 } });
  failure(range.query('context'), 'E_NOT_READY');
  const total = makeHarness();
  total.state.tree.getTotal = () => Infinity;
  failure(total.query('context'), 'E_NOT_READY');
  const unit = makeHarness();
  unit.update({ weightType: 'event_count' });
  failure(unit.query('context'), 'E_UPSTREAM_INCOMPATIBLE');
});

test('profile URL mismatches are rejected before reading the tree', () => {
  const h = makeHarness();
  h.update({ profileUrl: `${PROFILE_URL}?different-profile=1` });
  for (const method of METHODS.filter(method => method !== 'capabilities')) {
    failure(h.query(method), 'E_PROFILE_CHANGED');
  }
  assert.equal(h.calls.tree, 0);
  assert.deepEqual(h.calls.transport, []);
  const wrongScript = makeHarness({ scriptProfileUrl: 'http://127.0.0.1:8765/profiles/other.json' });
  failure(wrongScript.query('context'), 'E_PROFILE_CHANGED');
});

test('a file import retaining the APS profile URL is rejected before reading the tree', () => {
  const h = makeHarness();
  h.update({ dataSource: 'from-file' });
  assert.equal(h.state.profileUrl, h.root.document.currentScript.dataset.apsProfileUrl);
  assert.ok(h.state.profile);
  for (const method of METHODS.filter(method => method !== 'capabilities')) {
    failure(h.query(method), 'E_PROFILE_CHANGED');
  }
  assert.equal(h.calls.tree, 0);
  assert.deepEqual(h.calls.transport, []);
});

test('bounds reject invalid limits and depths before reading a profile', () => {
  const h = makeHarness();
  for (const limit of [0, -1, 101, 1.5, NaN, Infinity, '2', null]) {
    failure(h.query('callTree', { limit }), 'E_LIMIT');
  }
  for (const maxDepth of [0, -1, 65, 1.5, NaN, Infinity, '2', null]) {
    failure(h.query('function', { maxDepth }), 'E_LIMIT');
  }
  assert.equal(h.calls.getState, 0);
  success(h.query('callTree', { limit: 100, maxDepth: 64 }));
});

test('zero or more than eight selected threads are rejected', () => {
  for (const indexes of [[], [0, 1, 2, 3, 4, 5, 6, 7, 8]]) {
    const h = makeHarness();
    h.update({ selectedThreads: new Set(indexes) });
    failure(h.query('context'), 'E_LIMIT');
  }
});

test('unavailable node IDs and non-finite raw node metrics are rejected', () => {
  const h = makeHarness();
  success(h.query('context'));
  failure(h.query('function', { nodeId: 'not-a-node' }), 'E_STALE');
  failure(h.query('function', { nodeId: 'fp:1:3' }), 'E_STALE');
  failure(h.query('function', { nodeId: 'fp:standalone:1:9007199254740992' }), 'E_NODE');
  failure(h.query('function', { nodeId: 'fp:standalone:1:999' }), 'E_NODE');
  for (const key of ['total', 'self', 'totalRelative', 'selfRelative']) {
    const invalid = makeHarness();
    const original = invalid.state.tree.getNodeData;
    invalid.state.tree.getNodeData = index => ({ ...original(index), [key]: NaN });
    failure(invalid.query('selection'), 'E_NODE');
  }
});

test('response budgets count UTF-8 bytes and fail without partial evidence', () => {
  const nodes = Array.from({ length: 100 }, (_, index) => ({
    index, parent: -1, depth: 0, name: '\u754c'.repeat(512), total: 1, self: 1,
    library: '\u754c'.repeat(512), category: '\u754c'.repeat(120),
  }));
  const h = makeHarness({ tree: makeTree(nodes) });
  failure(h.query('callTree', { limit: 100 }), 'E_LIMIT');
  const limited = h.query('callTree', { limit: 5 });
  assert.equal(success(limited).rows.length, 5);
  assert.equal(limited.data.truncated, true);
  assert.ok(new TextEncoder().encode(JSON.stringify(limited)).length <= MAX_BYTES);
});

test('output truncates long text and removes Unix and Windows library directories', () => {
  const h = makeHarness();
  const original = h.state.tree.getNodeData;
  h.state.tree.getNodeData = index => ({ ...original(index), funcName: 'N'.repeat(700) });
  h.state.tree.getDisplayData = () => ({ lib: 'C:\\private\\sources\\fixture.dll', categoryName: 'C'.repeat(150) });
  const node = success(h.query('selection')).selection.node;
  assert.equal(node.name.length, 512);
  assert.equal(node.category.length, 120);
  assert.equal(node.library, 'fixture.dll');
  h.update({ search: 'S'.repeat(700) });
  assert.equal(success(h.query('context')).search.length, 512);
});

test('call-node methods reject unsupported views while context, threads, and capabilities remain available', () => {
  const h = makeHarness();
  for (const tab of ['marker-chart', 'marker-table', 'network-chart', 'js-tracer']) {
    h.update({ tab });
    for (const method of ['selection', 'function', 'callTree', 'analysis']) {
      failure(h.query(method), 'E_VIEW_UNSUPPORTED');
    }
    assert.equal(success(h.query('context')).tab, tab);
    success(h.query('threads'));
    success(h.query('capabilities'));
  }
  for (const tab of ['calltree', 'flame-graph', 'stack-chart']) {
    h.update({ tab });
    for (const method of ['selection', 'function', 'callTree', 'analysis']) success(h.query(method));
  }
});

test('unsupported methods, protocol versions, and parameter shapes fail closed', () => {
  const h = makeHarness();
  for (const method of ['dispatch', 'actions', 'constructor', '__proto__', 'saveToDisk', 'unknown']) {
    failure(h.query(method), 'E_PROTOCOL');
  }
  for (const request of [null, {}, { protocolVersion: 2, method: 'context' }, { method: 'context' }]) {
    failure(h.request(request), 'E_PROTOCOL');
  }
  for (const params of [null, [], 'text', 1, true]) failure(h.query('context', params), 'E_PROTOCOL');
  assert.equal(h.calls.getState, 0);
  assert.equal(h.calls.dispatch, 0);
  assert.deepEqual(h.calls.transport, []);
});
