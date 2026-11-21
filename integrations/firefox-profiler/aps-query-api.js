/* APS-owned read-only extension. Keep this file outside the upstream submodule. */
(function installAPSProfilerQuery(root) {
  'use strict';
  if (root.APSProfilerQuery) return;
  const VERSION = 1;
  const METHODS = ['capabilities', 'context', 'threads', 'selection', 'callTree', 'function', 'analysis'];
  const script = root.document && root.document.currentScript;
  const pageId = script && script.dataset.apsPageId;
  const profileUrl = script && script.dataset.apsProfileUrl;
  const issuedDocumentId = script && script.dataset.apsDocumentId;
  const documentId = issuedDocumentId || (root.crypto && typeof root.crypto.randomUUID === 'function' ? root.crypto.randomUUID() : 'standalone');
  const MAX_BYTES = 60 * 1024;
  let revision = 0, previousProfile, previousTree, previousContext;
  const fail = (code, message) => { throw Object.assign(new Error(message), { code }); };
  const text = (value, max = 512) => typeof value === 'string' ? value.slice(0, max) : '';
  const finite = value => typeof value === 'number' && Number.isFinite(value);
  function boundedInteger(value, fallback, maximum) {
    if (value === undefined) return fallback;
    if (!Number.isSafeInteger(value) || value < 1 || value > maximum) fail('E_LIMIT', 'Query bounds are outside the supported range.');
    return value;
  }
  function required(object, names) {
    if (!object || names.some(name => typeof object[name] !== 'function')) fail('E_UPSTREAM_INCOMPATIBLE', 'This Firefox Profiler build does not expose the required read-only selectors.');
  }
  function capture() {
    required(root, ['getState']);
    const selectors = root.selectors;
    if (!selectors) fail('E_UPSTREAM_INCOMPATIBLE', 'Firefox Profiler selectors are unavailable.');
    const { profile: p, urlState: u, selectedThread: s } = selectors;
    required(p, ['getProfileOrNull', 'getPreviewSelectionRange', 'getCommittedRange']);
    required(u, ['getDataSource', 'getProfileUrl', 'getSelectedThreadIndexes', 'getSelectedTab', 'getInvertCallstack', 'getCurrentSearchString']);
    required(s, ['getCallTree', 'getSelectedCallNodeIndex', 'getWeightTypeForCallTree', 'getFilteredThread']);
    const state = root.getState();
    const profile = p.getProfileOrNull(state);
    if (!profile) fail('E_NOT_READY', 'The profile is not loaded.');
    if (!profileUrl || u.getDataSource(state) !== 'from-url' || u.getProfileUrl(state) !== profileUrl) fail('E_PROFILE_CHANGED', 'This page no longer contains the APS-owned profile.');
    const tree = s.getCallTree(state);
    required(tree, ['getRoots', 'getChildren', 'getParent', 'getDepth', 'getNodeData', 'getDisplayData', 'getTotal', 'hasChildren']);
    const indexes = Array.from(u.getSelectedThreadIndexes(state));
    if (indexes.length === 0 || indexes.length > 8) fail('E_LIMIT', 'Select between one and eight threads.');
    const range = p.getPreviewSelectionRange(state);
    const committed = p.getCommittedRange(state);
    if (![range.start, range.end, committed.start, committed.end].every(finite)) fail('E_NOT_READY', 'The profile time range is unavailable.');
    const selected = s.getSelectedCallNodeIndex(state);
    const weightType = s.getWeightTypeForCallTree(state);
    if (!['samples', 'tracing-ms', 'bytes'].includes(weightType)) fail('E_UPSTREAM_INCOMPATIBLE', 'Unsupported metric unit.');
    const context = {
      documentId,
      threads: indexes.map(index => threadSummary(profile.threads[index], index)),
      range: { start: range.start, end: range.end, unit: 'ms' },
      committedRange: { start: committed.start, end: committed.end, unit: 'ms' },
      tab: text(u.getSelectedTab(state), 40),
      inverted: u.getInvertCallstack(state),
      search: text(u.getCurrentSearchString(state)),
      weightType,
      totalWeight: tree.getTotal(),
      selectedCallNodeIndex: selected,
      semantics: 'Sample weights are not exact method duration or scheduler evidence.',
    };
    if (!finite(context.totalWeight)) fail('E_NOT_READY', 'The current call tree has no finite total.');
    const fingerprint = JSON.stringify(context);
    if (profile !== previousProfile || tree !== previousTree || fingerprint !== previousContext) {
      revision += 1;
      previousProfile = profile;
      previousTree = tree;
      previousContext = fingerprint;
    }
    return { profile, tree, context, selected, revision, thread: s.getFilteredThread(state) };
  }
  function threadSummary(thread, index) {
    if (!thread || !Number.isSafeInteger(index) || index < 0) fail('E_UPSTREAM_INCOMPATIBLE', 'Thread identity is unavailable.');
    return { index, name: text(thread.name), pid: text(String(thread.pid), 80), tid: text(String(thread.tid), 80) };
  }
  function nodeId(snapshot, index) { return `fp:${documentId}:${snapshot.revision}:${index}`; }
  function parseNode(snapshot, id) {
    const match = typeof id === 'string' && /^fp:([a-zA-Z0-9-]{1,64}):([1-9][0-9]*):([0-9]+)$/.exec(id);
    if (!match || match[1] !== documentId || Number(match[2]) !== snapshot.revision) fail('E_STALE', 'The node belongs to another document or view revision.');
    const index = Number(match[3]);
    if (!Number.isSafeInteger(index)) fail('E_NODE', 'Invalid call-node identity.');
    const depth = snapshot.tree.getDepth(index);
    if (!Number.isSafeInteger(depth) || depth < 0) fail('E_NODE', 'The call node is not available.');
    return index;
  }
  function row(snapshot, index) {
    const raw = snapshot.tree.getNodeData(index);
    if (![raw.total, raw.self, raw.totalRelative, raw.selfRelative].every(finite)) fail('E_NODE', 'Call-node metrics are unavailable.');
    const display = snapshot.tree.getDisplayData(index);
    // Resource names are useful evidence; absolute source or library paths are not.
    const library = text(display.lib).split(/[\\/]/).pop();
    const depth = snapshot.tree.getDepth(index);
    if (!Number.isSafeInteger(depth) || depth < 0) fail('E_NODE', 'The call node depth is unavailable.');
    return {
      nodeId: nodeId(snapshot, index),
      parentId: snapshot.tree.getParent(index) === -1 ? null : nodeId(snapshot, snapshot.tree.getParent(index)),
      depth,
      name: text(raw.funcName), library,
      category: text(display.categoryName, 120),
      totalWeight: raw.total, selfWeight: raw.self,
      totalShare: raw.totalRelative, selfShare: raw.selfRelative,
      weightType: snapshot.context.weightType,
      hasChildren: snapshot.tree.hasChildren(index),
    };
  }
  function callTree(snapshot, params) {
    const limit = boundedInteger(params.limit, 20, 100);
    const maxDepth = boundedInteger(params.maxDepth, 32, 64);
    const roots = params.rootNodeId ? [parseNode(snapshot, params.rootNodeId)] : snapshot.tree.getRoots();
    const stack = [{ values: roots, offset: 0, depth: 0 }];
    const rows = [];
    let truncated = false;
    while (stack.length && rows.length < limit) {
      const frame = stack[stack.length - 1];
      if (frame.offset === frame.values.length) { stack.pop(); continue; }
      const index = frame.values[frame.offset++];
      rows.push(row(snapshot, index));
      if (snapshot.tree.hasChildren(index)) {
        if (frame.depth + 1 < maxDepth) stack.push({ values: snapshot.tree.getChildren(index), offset: 0, depth: frame.depth + 1 });
        else truncated = true;
      }
    }
    return { rows, truncated: truncated || stack.some(frame => frame.offset < frame.values.length) };
  }
  function functionInfo(snapshot, params) {
    const index = params.nodeId ? parseNode(snapshot, params.nodeId) : snapshot.selected;
    if (index === null || index === undefined) return null;
    const limit = boundedInteger(params.limit, 12, 100);
    const maxDepth = boundedInteger(params.maxDepth, 32, 64);
    const ancestors = [];
    let parent = snapshot.tree.getParent(index);
    while (parent !== -1 && ancestors.length < maxDepth) {
      ancestors.push(row(snapshot, parent));
      parent = snapshot.tree.getParent(parent);
    }
    const children = snapshot.tree.getChildren(index);
    return {
      node: row(snapshot, index),
      ancestors: ancestors.reverse(),
      ancestryTruncated: parent !== -1,
      children: children.slice(0, limit).map(child => row(snapshot, child)),
      childrenTruncated: children.length > limit,
    };
  }
  function query(request) {
    try {
      if (!request || request.protocolVersion !== VERSION || !METHODS.includes(request.method)) fail('E_PROTOCOL', 'Unsupported read-only query.');
      if (request.params !== undefined && (!request.params || typeof request.params !== 'object' || Array.isArray(request.params))) fail('E_PROTOCOL', 'Query parameters must be an object.');
      const params = request.params || {};
      boundedInteger(params.limit, 20, 100);
      boundedInteger(params.maxDepth, 32, 64);
      if (request.method === 'capabilities') return { ok: true, revision: Math.max(1, revision), data: { protocolVersion: VERSION, methods: METHODS.slice(), readOnly: true, maximumRows: 100, maximumDepth: 64 } };
      const snapshot = capture();
      let expectedDocument = params.expectedDocumentId;
      if (expectedDocument === undefined) {
        const scopedId = params.nodeId || params.rootNodeId;
        const match = typeof scopedId === 'string' && /^fp:([a-zA-Z0-9-]{1,64}):/.exec(scopedId);
        if (match) expectedDocument = match[1];
      }
      if (expectedDocument !== undefined && (typeof expectedDocument !== 'string' || !/^[a-zA-Z0-9-]{1,64}$/.test(expectedDocument))) fail('E_PROTOCOL', 'Invalid expected document identity.');
      if (expectedDocument !== undefined && expectedDocument !== documentId) fail('E_STALE', 'The query belongs to another browser document.');
      if (request.expectedRevision !== undefined && request.expectedRevision !== snapshot.revision) fail('E_STALE', 'The selection or view changed before the query completed.');
      if (request.expectedRevision !== undefined && expectedDocument === undefined) fail('E_PROTOCOL', 'Revision checks also require params.expectedDocumentId or a document-scoped node ID.');
      if (['selection', 'function', 'callTree', 'analysis'].includes(request.method) && !['calltree', 'flame-graph', 'stack-chart'].includes(snapshot.context.tab)) fail('E_VIEW_UNSUPPORTED', 'Select Call Tree, Flame Graph, or Stack Chart before querying call-node evidence.');
      let data;
      switch (request.method) {
        case 'context': data = snapshot.context; break;
        case 'threads': {
          const limit = boundedInteger(params.limit, 20, 100);
          data = { threads: snapshot.profile.threads.slice(0, limit).map(threadSummary), truncated: snapshot.profile.threads.length > limit };
          break;
        }
        case 'selection':
        case 'function': data = { context: snapshot.context, selection: functionInfo(snapshot, params) }; break;
        case 'callTree': data = { context: snapshot.context, ...callTree(snapshot, params) }; break;
        case 'analysis': data = { context: snapshot.context, selection: functionInfo(snapshot, params), callTree: callTree(snapshot, params) }; break;
      }
      const result = { ok: true, revision: snapshot.revision, data };
      if (new TextEncoder().encode(JSON.stringify(result)).length > MAX_BYTES) fail('E_LIMIT', 'The requested data exceeds the response budget; reduce the row/depth limits.');
      return result;
    } catch (error) {
      return { ok: false, code: error.code || 'E_UPSTREAM_INCOMPATIBLE', message: error.code ? error.message : 'Firefox Profiler query interfaces changed or the requested node is unavailable.' };
    }
  }
  Object.defineProperty(root, 'APSProfilerQuery', { value: Object.freeze({ protocolVersion: VERSION, query }), configurable: false, writable: false });
  // No profile is read until the trusted APS host enqueues a request.
  if (!pageId || !/^[0-9a-f]{64}$/.test(pageId) || !profileUrl || !root.fetch) return;
  let stopped = false;
  let attached = false;
  let timer;
  if (!issuedDocumentId || !/^[a-zA-Z0-9-]{1,64}$/.test(issuedDocumentId)) return;
  const endpoint = `/aps-query/${pageId}`;
  const headers = { 'X-APS-Query-Token': pageId, 'X-APS-Query-Document': documentId, 'Content-Type': 'application/json' };
  async function poll() {
    try {
      if (!attached) {
        const response = await root.fetch(`${endpoint}/attach`, { method: 'POST', headers, credentials: 'same-origin', body: JSON.stringify({ documentId }) });
        if ([403, 404, 409, 410].includes(response.status)) { stopped = true; return; }
        if (!response.ok) throw new Error('unavailable');
        attached = true;
      }
      const response = await root.fetch(`${endpoint}/poll`, { headers, cache: 'no-store', credentials: 'same-origin' });
      if (response.status === 404 || response.status === 410 || response.status === 403) { stopped = true; return; }
      if (!response.ok) throw new Error('unavailable');
      const batch = await response.json();
      if (batch.protocolVersion !== VERSION || batch.pageId !== pageId || !Array.isArray(batch.requests) || batch.requests.length > 8) { stopped = true; return; }
      for (const request of batch.requests) {
        const result = query(request);
        await root.fetch(`${endpoint}/result`, { method: 'POST', headers, credentials: 'same-origin', body: JSON.stringify({ protocolVersion: VERSION, pageId, documentId, requestId: request.requestId, result }) });
      }
    } catch (_) { /* Closing APS makes this interface unavailable, never an online fallback. */ }
    finally { if (!stopped) timer = root.setTimeout(poll, 300); }
  }
  root.addEventListener('pagehide', () => { stopped = true; root.clearTimeout(timer); }, { once: true });
  poll();
})(globalThis);
