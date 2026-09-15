import { describe, expect, it, vi } from 'vitest';
import { IPC_CHANNELS } from './ipc.js';
import {
  MAX_OPAQUE_RECORD_ID_LENGTH,
  requireAiAnalyzeRequest,
  requireAiConfigurationInput,
  requireAiSourceCandidateOpenRequest,
  requireAiCredential,
  requireAppDestination,
  requireApplicationUiSettingsPatch,
  requireAndroidAppTarget,
  requireBatteryCaptureInput,
  requireBenchmarkCompareInput,
  requireBitmapImageRequest,
  requireCpuCaptureInput,
  requireCpuSnapshotRequest,
  requireLayoutCaptureInput,
  requireMemoryDiffRequest,
  requireMemoryInstanceDetailRequest,
  requireMemoryInstanceRequest,
  requireMethodCaptureInput,
  requireMethodSnapshotRequest,
  requireOpaqueRecordId,
  requireSourceAiUploadRequest,
  requireSourceReadRequest,
  requireSourceResolveRequest,
  requireSourceSearchRequest,
  requireStartupCaptureInput,
  requireTraceCaptureInput,
  requireTraceImportPath,
  requireUiLanguage,
  requireViewerMenuState,
} from './ipc-validation.js';

const destructiveChannels = [
  IPC_CHANNELS.sourceRemove,
  IPC_CHANNELS.methodRemove,
  IPC_CHANNELS.cpuRemove,
  IPC_CHANNELS.bitmapRemove,
  IPC_CHANNELS.nativeHeapRemove,
] as const;

describe('requireOpaqueRecordId', () => {
  it('accepts opaque IDs emitted by the application stores', () => {
    expect(requireOpaqueRecordId('1726315829000', IPC_CHANNELS.methodRemove)).toBe('1726315829000');
    expect(requireOpaqueRecordId('native-heap-0f3c9b2a', IPC_CHANNELS.nativeHeapRemove)).toBe('native-heap-0f3c9b2a');
    expect(requireOpaqueRecordId('a'.repeat(MAX_OPAQUE_RECORD_ID_LENGTH), IPC_CHANNELS.bitmapRemove)).toHaveLength(
      MAX_OPAQUE_RECORD_ID_LENGTH,
    );
  });

  it.each([undefined, null, 42, true, [], {}])('rejects a non-string ID: %p', (value) => {
    expect(() => requireOpaqueRecordId(value, IPC_CHANNELS.sourceRemove)).toThrow(
      'source:remove: record id must be a string',
    );
  });

  it.each([
    '',
    ' record',
    'record ',
    '.',
    '..',
    '../record',
    'record/child',
    'record\\child',
    'record\0child',
    'a'.repeat(MAX_OPAQUE_RECORD_ID_LENGTH + 1),
  ])('rejects unsafe record ID %j for every destructive channel', (value) => {
    for (const channel of destructiveChannels) {
      expect(() => requireOpaqueRecordId(value, channel)).toThrow(channel);
    }
  });

  it('keeps a valid but missing record distinct from malformed IPC input', () => {
    const removeMissingRecord = vi.fn<(id: string) => boolean>(() => false);
    const id = requireOpaqueRecordId('missing-record', IPC_CHANNELS.cpuRemove);

    expect(removeMissingRecord(id)).toBe(false);
    expect(removeMissingRecord).toHaveBeenCalledWith('missing-record');
  });
});

describe('requireAiSourceCandidateOpenRequest', () => {
  it('accepts opaque session and candidate IDs without a renderer path', () => {
    expect(requireAiSourceCandidateOpenRequest(
      { sessionId: 'ai-session-1', candidateId: 'candidate-1' },
      IPC_CHANNELS.aiSourceCandidate,
    )).toEqual({ sessionId: 'ai-session-1', candidateId: 'candidate-1' });
  });

  it.each([
    { sessionId: '../session', candidateId: 'candidate-1' },
    { sessionId: 'ai-session-1', candidateId: '../candidate' },
    { sessionId: 'ai-session-1', candidateId: '' },
    { sessionId: 'ai-session-1' },
  ])('rejects an unsafe AI source-candidate request: %j', (input) => {
    expect(() => requireAiSourceCandidateOpenRequest(input, IPC_CHANNELS.aiSourceCandidate)).toThrow(
      IPC_CHANNELS.aiSourceCandidate,
    );
  });
});

describe('shell, menu, and layout capture IPC validation', () => {
  const validMenuState = {
    language: 'en' as const,
    available: true,
    hasSnapshot: true,
    hasSelection: true,
    autoScan: false,
    archiveOperationInProgress: false,
    panels: { hierarchy: true, details: false, findings: true },
    view: {
      hideInvisibleHierarchyViews: false,
      hideInvisibleFindings: true,
      hideHierarchyIndices: false,
      showHierarchyLayerVisibilityButtons: true,
      showVisibleViewBounds: true,
      showHierarchyIds: false,
    },
  };

  it('preserves the valid auto-device and target-aware layout capture contracts', () => {
    expect(requireLayoutCaptureInput('', undefined, IPC_CHANNELS.layoutCapture)).toEqual({
      requestedSerial: '',
      options: {},
    });
    expect(requireLayoutCaptureInput('emulator-5554', {
      archive: false,
      target: 'systemUi',
    }, IPC_CHANNELS.layoutCapture)).toEqual({
      requestedSerial: 'emulator-5554',
      options: { archive: false, target: 'systemUi' },
    });
  });

  it.each([
    [undefined, {}],
    [' serial ', {}],
    ['emulator-5554', null],
    ['emulator-5554', []],
    ['emulator-5554', { archive: 'false' }],
    ['emulator-5554', { target: 'other' }],
    ['emulator-5554', { archive: true, unexpected: true }],
  ])('rejects malformed layout capture input before ADB: %j', (serial, options) => {
    expect(() => requireLayoutCaptureInput(serial, options, IPC_CHANNELS.layoutCapture)).toThrow(IPC_CHANNELS.layoutCapture);
  });

  it('accepts only known destinations and documentation languages', () => {
    expect(requireAppDestination('LAYOUT_INSPECTOR', IPC_CHANNELS.openDestination)).toBe('LAYOUT_INSPECTOR');
    expect(requireUiLanguage('zh', IPC_CHANNELS.openUserGuide)).toBe('zh');
    expect(() => requireAppDestination('UNKNOWN', IPC_CHANNELS.openDestination)).toThrow(IPC_CHANNELS.openDestination);
    expect(() => requireUiLanguage('english', IPC_CHANNELS.openUserGuide)).toThrow(IPC_CHANNELS.openUserGuide);
  });

  it('validates every persisted settings patch field before JSON storage', () => {
    expect(requireApplicationUiSettingsPatch({
      theme: 'dark',
      language: 'english',
      accentColor: 'azure',
      displayScalePercent: 125,
      androidSdkPath: '/Library/Android/sdk',
      layoutInspector: {
        hideInvisibleHierarchyViews: true,
        hideInvisibleFindings: false,
        hideHierarchyIndices: true,
        showHierarchyIds: false,
        showHierarchyLayerVisibilityButtons: true,
        showVisibleViewBounds: false,
        snapshotSizeMultiplier: 4,
        canvasHitTestOrder: 'z-order',
        canvasBorderColors: { normal: '#7dd3fc', hovered: '#FFF59E0B', selected: '#ffef4444' },
      },
      simpleperf: {
        flameTooltipMode: 'fixed',
        engine: 'firefox-local',
        captureDefaults: {
          template: 'UI_THREAD_FOCUS',
          target: 'APP',
          event: 'cpu-clock',
          frequencyHertz: 1000,
          periodEvents: 100,
          rateMode: 'FREQUENCY',
          durationSeconds: 10,
          callGraph: 'DWARF',
          scope: 'USER',
        },
      },
    }, IPC_CHANNELS.updateSettings)).toEqual({
      theme: 'dark',
      language: 'english',
      accentColor: 'azure',
      displayScalePercent: 125,
      androidSdkPath: '/Library/Android/sdk',
      layoutInspector: {
        hideInvisibleHierarchyViews: true,
        hideInvisibleFindings: false,
        hideHierarchyIndices: true,
        showHierarchyIds: false,
        showHierarchyLayerVisibilityButtons: true,
        showVisibleViewBounds: false,
        snapshotSizeMultiplier: 4,
        canvasHitTestOrder: 'z-order',
        canvasBorderColors: { normal: '#FF7DD3FC', hovered: '#FFF59E0B', selected: '#FFEF4444' },
      },
      simpleperf: {
        flameTooltipMode: 'fixed',
        engine: 'firefox-local',
        captureDefaults: {
          template: 'UI_THREAD_FOCUS',
          target: 'APP',
          event: 'cpu-clock',
          frequencyHertz: 1000,
          periodEvents: 100,
          rateMode: 'FREQUENCY',
          durationSeconds: 10,
          callGraph: 'DWARF',
          scope: 'USER',
        },
      },
    });
  });

  it.each([
    null,
    { theme: 'neon' },
    { displayScalePercent: 50 },
    { androidSdkPath: 'relative/android-sdk' },
    { layoutInspector: { snapshotSizeMultiplier: 11 } },
    { layoutInspector: { canvasBorderColors: { normal: 'red' } } },
    { simpleperf: { captureDefaults: { event: 'cpu clock' } } },
    { simpleperf: { captureDefaults: { frequencyHertz: 1.5 } } },
    { unknown: true },
  ])('rejects malformed settings patch: %j', (patch) => {
    expect(() => requireApplicationUiSettingsPatch(patch, IPC_CHANNELS.updateSettings)).toThrow(IPC_CHANNELS.updateSettings);
  });

  it('accepts complete menu state and rejects malformed structural state', () => {
    expect(requireViewerMenuState(validMenuState, IPC_CHANNELS.viewerMenuState)).toEqual(validMenuState);
    expect(() => requireViewerMenuState({ ...validMenuState, panels: [] }, IPC_CHANNELS.viewerMenuState)).toThrow(
      IPC_CHANNELS.viewerMenuState,
    );
    expect(() => requireViewerMenuState({ ...validMenuState, view: { ...validMenuState.view, showHierarchyIds: 'yes' } }, IPC_CHANNELS.viewerMenuState)).toThrow(
      IPC_CHANNELS.viewerMenuState,
    );
    expect(() => requireViewerMenuState({ ...validMenuState, extra: true }, IPC_CHANNELS.viewerMenuState)).toThrow(
      IPC_CHANNELS.viewerMenuState,
    );
  });
});

describe('requireBitmapImageRequest', () => {
  it('accepts a session-owned bitmap image lookup without a filesystem path', () => {
    expect(requireBitmapImageRequest({ sessionId: 'bitmap-session-1', recordIndex: 0 }, IPC_CHANNELS.bitmapImage)).toEqual({
      sessionId: 'bitmap-session-1',
      recordIndex: 0,
    });
  });

  it.each([
    undefined,
    { sessionId: '../escape', recordIndex: 0 },
    { sessionId: 'bitmap-session-1', recordIndex: -1 },
    { sessionId: 'bitmap-session-1', recordIndex: 1.5 },
    { sessionId: 'bitmap-session-1', recordIndex: 1_000_001 },
    { sessionId: 'bitmap-session-1', recordIndex: '0' },
  ])('rejects an unsafe bitmap image request: %j', (input) => {
    expect(() => requireBitmapImageRequest(input, IPC_CHANNELS.bitmapImage)).toThrow(IPC_CHANNELS.bitmapImage);
  });
});

describe('profile snapshot request validation', () => {
  const methodInput = {
    id: 'method-session-1',
    threadKey: 'main (tid 7)',
    searchText: 'render',
    direction: 'INVERTED' as const,
    transforms: [
      { kind: 'FOCUS_CALL_NODE' as const, path: ['1', '42'] },
      { kind: 'FOCUS_FUNCTION' as const, functionId: '99' },
      { kind: 'DROP_FUNCTION' as const, functionId: '100' },
      { kind: 'COLLAPSE_RECURSION' as const, functionId: '101' },
      { kind: 'COLLAPSE_RESOURCE' as const, resource: '/system/lib/libart.so' },
    ],
    rankBy: 'TOTAL_MICROS' as const,
  };

  it('accepts method snapshot queries and preserves JSON-safe transform fields', () => {
    expect(requireMethodSnapshotRequest(methodInput, IPC_CHANNELS.methodSnapshot)).toEqual(methodInput);
  });

  it('accepts CPU snapshot filters with an empty optional thread key', () => {
    expect(requireCpuSnapshotRequest({
      id: 'cpu-session-1',
      threadKey: '',
      searchText: '',
      implementation: 'NATIVE',
      direction: 'FORWARD',
      transforms: [],
    }, IPC_CHANNELS.cpuSnapshot)).toEqual({
      id: 'cpu-session-1',
      threadKey: '',
      searchText: '',
      implementation: 'NATIVE',
      direction: 'FORWARD',
      transforms: [],
    });
  });

  it.each([
    { ...methodInput, id: '../method' },
    { ...methodInput, searchText: 'x'.repeat(513) },
    { ...methodInput, threadKey: 'x'.repeat(1_025) },
    { ...methodInput, direction: 'SIDEWAYS' },
    { ...methodInput, rankBy: 'WEIGHT' },
    { ...methodInput, transforms: undefined },
    { ...methodInput, transforms: [{ kind: 'UNKNOWN' }] },
    { ...methodInput, transforms: [{ kind: 'FOCUS_CALL_NODE', path: [] }] },
    { ...methodInput, transforms: [{ kind: 'FOCUS_CALL_NODE', path: ['not-a-number'] }] },
    { ...methodInput, transforms: Object.assign([], { length: 1 }) },
    { ...methodInput, transforms: [{ kind: 'FOCUS_CALL_NODE', path: Object.assign([], { length: 1 }) }] },
    { ...methodInput, transforms: [{ kind: 'FOCUS_FUNCTION', functionId: '1.0' }] },
    { ...methodInput, transforms: [{ kind: 'COLLAPSE_RESOURCE', resource: '' }] },
  ])('rejects malformed method snapshot input before session loading: %j', (input) => {
    expect(() => requireMethodSnapshotRequest(input, IPC_CHANNELS.methodSnapshot)).toThrow(IPC_CHANNELS.methodSnapshot);
  });

  it.each([
    { id: '../cpu', searchText: '', implementation: 'ALL', direction: 'FORWARD', transforms: [] },
    { id: 'cpu-session-1', searchText: '', implementation: 'SCRIPT', direction: 'SIDEWAYS', transforms: [] },
    { id: 'cpu-session-1', searchText: '', implementation: 'ALL', direction: 'FORWARD', transforms: [{ kind: 'FOCUS_FUNCTION', functionId: '0x10' }] },
    { id: 'cpu-session-1', searchText: '', implementation: 'ALL', direction: 'FORWARD', transforms: [{ kind: 'COLLAPSE_RESOURCE', resource: 'x\0y' }] },
  ])('rejects malformed CPU snapshot input before session loading: %j', (input) => {
    expect(() => requireCpuSnapshotRequest(input, IPC_CHANNELS.cpuSnapshot)).toThrow(IPC_CHANNELS.cpuSnapshot);
  });
});

describe('memory instance request validation', () => {
  it('accepts a bounded class query and hexadecimal object lookup', () => {
    expect(requireMemoryInstanceRequest({
      sessionId: 'memory-session-1',
      className: '[Ljava.lang.String;',
      heap: 'App',
      limit: 200,
    }, IPC_CHANNELS.memoryInstances)).toEqual({
      sessionId: 'memory-session-1',
      className: '[Ljava.lang.String;',
      heap: 'App',
      limit: 200,
    });
    expect(requireMemoryInstanceDetailRequest({
      sessionId: 'memory-session-1',
      objectId: '0xdeadbeef',
    }, IPC_CHANNELS.memoryInstanceDetail)).toEqual({
      sessionId: 'memory-session-1',
      objectId: '0xdeadbeef',
    });
  });

  it.each([
    undefined,
    { sessionId: '../memory', className: 'com.example.Item' },
    { sessionId: 'memory-session-1', className: '' },
    { sessionId: 'memory-session-1', className: 'x'.repeat(1_025) },
    { sessionId: 'memory-session-1', className: 'com.example.Item', heap: 1 },
    { sessionId: 'memory-session-1', className: 'com.example.Item', heap: '' },
    { sessionId: 'memory-session-1', className: 'com.example.Item', limit: 0 },
    { sessionId: 'memory-session-1', className: 'com.example.Item', limit: 10_001 },
    { sessionId: 'memory-session-1', className: 'com.example.Item', limit: 1.5 },
  ])('rejects malformed class queries before heap lookup: %j', (input) => {
    expect(() => requireMemoryInstanceRequest(input, IPC_CHANNELS.memoryInstances)).toThrow(IPC_CHANNELS.memoryInstances);
  });

  it.each([
    undefined,
    { sessionId: '../memory', objectId: '0x1' },
    { sessionId: 'memory-session-1', objectId: '' },
    { sessionId: 'memory-session-1', objectId: 'not-hex' },
    { sessionId: 'memory-session-1', objectId: '0x1\0' },
  ])('rejects malformed object lookups before heap lookup: %j', (input) => {
    expect(() => requireMemoryInstanceDetailRequest(input, IPC_CHANNELS.memoryInstanceDetail)).toThrow(IPC_CHANNELS.memoryInstanceDetail);
  });
});

describe('requireBenchmarkCompareInput', () => {
  it('accepts opaque run IDs and finite threshold overrides', () => {
    expect(requireBenchmarkCompareInput({
      baselineId: 'run-baseline',
      currentId: 'run-current',
      relativeThresholdPercent: 5,
      absoluteThreshold: 1.5,
    }, IPC_CHANNELS.benchmarkCompare)).toEqual({
      baselineId: 'run-baseline',
      currentId: 'run-current',
      relativeThresholdPercent: 5,
      absoluteThreshold: 1.5,
    });
  });

  it.each([
    undefined,
    { baselineId: '../baseline', currentId: 'run-current' },
    { baselineId: 'run-baseline', currentId: 'run/current' },
    { baselineId: 'run-baseline', currentId: 'run-current', relativeThresholdPercent: -1 },
    { baselineId: 'run-baseline', currentId: 'run-current', absoluteThreshold: Number.NaN },
    { baselineId: 'run-baseline', currentId: 'run-current', absoluteThreshold: Number.POSITIVE_INFINITY },
    { baselineId: 'run-baseline', currentId: 'run-current', relativeThresholdPercent: '5' },
  ])('rejects malformed benchmark comparison before reading run files: %j', (input) => {
    expect(() => requireBenchmarkCompareInput(input, IPC_CHANNELS.benchmarkCompare)).toThrow(IPC_CHANNELS.benchmarkCompare);
  });
});

describe('requireAndroidAppTarget', () => {
  it.each([IPC_CHANNELS.memoryCapture, IPC_CHANNELS.bitmapCapture, IPC_CHANNELS.nativeHeapCapture] as const)(
    'accepts a normal app target for %s',
    (channel) => {
      expect(requireAndroidAppTarget({ serial: 'emulator-5554', packageName: 'com.example.app' }, channel)).toEqual({
        serial: 'emulator-5554',
        packageName: 'com.example.app',
      });
    },
  );

  it.each([IPC_CHANNELS.memoryCapture, IPC_CHANNELS.bitmapCapture, IPC_CHANNELS.nativeHeapCapture] as const)(
    'rejects malformed app targets before %s opens ADB or a temporary directory',
    (channel) => {
      expect(() => requireAndroidAppTarget({ serial: '-bad', packageName: 'com.example.app' }, channel)).toThrow(channel);
      expect(() => requireAndroidAppTarget({ serial: 'emulator-5554', packageName: 'com/example.app' }, channel)).toThrow(channel);
    },
  );
});

describe('requireFrameCaptureInput', () => {
  it('accepts normal Android targets and ADB serials without rewriting them', async () => {
    const { requireFrameCaptureInput } = await import('./ipc-validation.js');
    expect(requireFrameCaptureInput({ serial: 'emulator-5554', packageName: 'com.example.app' }, IPC_CHANNELS.frameCapture)).toEqual({
      serial: 'emulator-5554',
      packageName: 'com.example.app',
    });
    expect(requireFrameCaptureInput({ serial: '192.168.1.10:5555', packageName: 'com.android.settings' }, IPC_CHANNELS.frameCapture)).toEqual({
      serial: '192.168.1.10:5555',
      packageName: 'com.android.settings',
    });
  });

  it.each([
    undefined,
    null,
    {},
    { serial: 'emulator-5554' },
    { packageName: 'com.example.app' },
    { serial: 42, packageName: 'com.example.app' },
    { serial: 'emulator-5554', packageName: 42 },
    { serial: ' emulator-5554', packageName: 'com.example.app' },
    { serial: '-emulator', packageName: 'com.example.app' },
    { serial: 'emulator\n5554', packageName: 'com.example.app' },
    { serial: 'emulator-5554', packageName: ' com.example.app' },
    { serial: 'emulator-5554', packageName: 'com.example.app ' },
    { serial: 'emulator-5554', packageName: 'com/example.app' },
    { serial: 'emulator-5554', packageName: 'com.example..app' },
    { serial: 'emulator-5554', packageName: 'com.example.app\0suffix' },
  ])('rejects malformed frame-capture input %j before ADB use', async (input) => {
    const { requireFrameCaptureInput } = await import('./ipc-validation.js');
    expect(() => requireFrameCaptureInput(input, IPC_CHANNELS.frameCapture)).toThrow('frame:capture');
  });
});


describe('requireTraceImportPath', () => {
  it('accepts a normal absolute trace path without rewriting it', () => {
    expect(requireTraceImportPath('/tmp/capture.perfetto-trace', IPC_CHANNELS.traceImportFromPath)).toBe(
      '/tmp/capture.perfetto-trace',
    );
  });

  it.each([undefined, null, 42, '', ' /tmp/trace', '/tmp/trace ', 'relative.trace', '../trace', '/tmp/trace' + String.fromCharCode(0) + 'suffix'])(
    'rejects malformed renderer-controlled trace path: %p',
    (value) => {
      expect(() => requireTraceImportPath(value, IPC_CHANNELS.traceImportFromPath)).toThrow('trace:importFromPath');
    },
  );
});


describe('requireStartupCaptureInput', () => {
  const valid = {
    serial: 'emulator-5554',
    packageName: 'com.example.app',
    componentName: 'com.example.app/.MainActivity',
    requestedType: 'COLD' as const,
    warmupRuns: 0,
    measuredRuns: 5,
    timeoutSeconds: 30,
  };

  it('accepts a Kotlin-compatible bounded startup capture without rewriting it', () => {
    expect(requireStartupCaptureInput(valid, IPC_CHANNELS.startupCapture)).toEqual(valid);
    expect(requireStartupCaptureInput({ ...valid, componentName: undefined }, IPC_CHANNELS.startupCapture)).toEqual({
      serial: valid.serial,
      packageName: valid.packageName,
      requestedType: valid.requestedType,
      warmupRuns: valid.warmupRuns,
      measuredRuns: valid.measuredRuns,
      timeoutSeconds: valid.timeoutSeconds,
    });
  });

  it.each([
    undefined,
    {},
    { ...valid, serial: '-bad' },
    { ...valid, packageName: 'com.example/app' },
    { ...valid, componentName: 'com.example.app/MainActivity' },
    { ...valid, componentName: 'com.other.app/.MainActivity' },
    { ...valid, componentName: 'com.example.app/.Main Activity' },
    { ...valid, requestedType: 'UNKNOWN' },
    { ...valid, warmupRuns: -1 },
    { ...valid, warmupRuns: 101 },
    { ...valid, measuredRuns: 0 },
    { ...valid, measuredRuns: 100.5 },
    { ...valid, timeoutSeconds: 4 },
    { ...valid, timeoutSeconds: 301 },
  ])('rejects malformed startup capture before ADB use: %j', (input) => {
    expect(() => requireStartupCaptureInput(input, IPC_CHANNELS.startupCapture)).toThrow('startup:capture');
  });
});

describe('requireBatteryCaptureInput', () => {
  const valid = {
    serial: 'emulator-5554',
    packageName: 'com.example.app',
    uid: 10123,
    mode: 'INTERACTIVE' as const,
    durationSeconds: 60,
    pollingIntervalSeconds: 10,
    measuredRuns: 1,
    cooldownSeconds: 30,
  };

  it('accepts a bounded battery capture without rewriting it', () => {
    expect(requireBatteryCaptureInput(valid, IPC_CHANNELS.batteryCapture)).toEqual(valid);
  });

  it.each([
    undefined,
    {},
    { ...valid, serial: '-bad' },
    { ...valid, packageName: 'com.example app' },
    { ...valid, uid: -1 },
    { ...valid, uid: 2_147_483_648 },
    { ...valid, uid: 10.5 },
    { ...valid, mode: 'OFFLINE' },
    { ...valid, durationSeconds: 4 },
    { ...valid, durationSeconds: 3601 },
    { ...valid, pollingIntervalSeconds: 4 },
    { ...valid, pollingIntervalSeconds: 61 },
    { ...valid, measuredRuns: 0 },
    { ...valid, measuredRuns: 51 },
    { ...valid, cooldownSeconds: -1 },
    { ...valid, cooldownSeconds: 301 },
  ])('rejects malformed battery capture before ADB use: %j', (input) => {
    expect(() => requireBatteryCaptureInput(input, IPC_CHANNELS.batteryCapture)).toThrow('battery:capture');
  });
});

describe('requireMethodCaptureInput', () => {
  const valid = {
    serial: 'emulator-5554',
    packageName: 'com.example.app',
    pid: 4242,
    durationSeconds: 10,
  };

  it('accepts a bounded method-trace request without rewriting it', () => {
    expect(requireMethodCaptureInput(valid, IPC_CHANNELS.methodCapture)).toEqual(valid);
  });

  it.each([
    undefined,
    {},
    { ...valid, serial: '-bad' },
    { ...valid, packageName: 'com.example app' },
    { ...valid, pid: 0 },
    { ...valid, pid: 2_147_483_648 },
    { ...valid, pid: 1.5 },
    { ...valid, durationSeconds: 0 },
    { ...valid, durationSeconds: 121 },
    { ...valid, durationSeconds: Number.POSITIVE_INFINITY },
  ])('rejects malformed method capture before ADB or temporary files: %j', (input) => {
    expect(() => requireMethodCaptureInput(input, IPC_CHANNELS.methodCapture)).toThrow('method:capture');
  });
});

describe('requireTraceCaptureInput', () => {
  const valid = {
    serial: 'emulator-5554',
    durationMillis: 10_000,
    bufferSizeKb: 32_768,
    dataSource: 'linux.ftrace',
  };

  it('accepts a Kotlin-compatible bounded Perfetto capture request without rewriting it', () => {
    expect(requireTraceCaptureInput(valid, IPC_CHANNELS.traceCapture)).toEqual(valid);
  });

  it.each([
    undefined,
    null,
    [],
    {},
    { ...valid, serial: '-bad' },
    { ...valid, durationMillis: 999 },
    { ...valid, durationMillis: 600_001 },
    { ...valid, durationMillis: 1.5 },
    { ...valid, bufferSizeKb: 1023 },
    { ...valid, bufferSizeKb: 1_048_577 },
    { ...valid, bufferSizeKb: Number.NaN },
    { ...valid, dataSource: '' },
    { ...valid, dataSource: 'linux ftrace' },
    { ...valid, dataSource: 'linux.ftrace\0suffix' },
  ])('rejects malformed trace capture before ADB use: %j', (input) => {
    expect(() => requireTraceCaptureInput(input, IPC_CHANNELS.traceCapture)).toThrow('trace:capture');
  });
});

describe('requireCpuCaptureInput', () => {
  const valid = {
    serial: 'emulator-5554', packageName: 'com.example.app', target: 'APP', event: 'cpu-cycles:u',
    frequencyHertz: 1000, periodEvents: 10000, rateMode: 'PERIOD', durationSeconds: 10,
    callGraph: 'DWARF', scope: 'USER',
  };

  it('accepts a bounded CPU capture request without rewriting its sampling semantics', () => {
    expect(requireCpuCaptureInput(valid, IPC_CHANNELS.cpuCapture)).toEqual(valid);
  });

  it.each([
    undefined, {}, { ...valid, packageName: undefined }, { ...valid, serial: '-bad' },
    { ...valid, event: 'two words' }, { ...valid, frequencyHertz: 0 }, { ...valid, periodEvents: 1.5 },
    { ...valid, durationSeconds: 3601 }, { ...valid, rateMode: 'INVALID' }, { ...valid, callGraph: 'oops' },
    { ...valid, scope: 'root' }, { ...valid, target: 'PROCESS' },
  ])('rejects malformed CPU capture before ADB use: %j', (input) => {
    expect(() => requireCpuCaptureInput(input, IPC_CHANNELS.cpuCapture)).toThrow('cpu:capture');
  });
});


describe('requireMemoryDiffRequest', () => {
  const valid = { beforeSessionId: 'before-1', afterSessionId: 'after-1' };

  it('accepts opaque stored-session ids and an explicit supported match mode', () => {
    expect(requireMemoryDiffRequest({ ...valid, matchMode: 'CLASS_NAME_AND_HIERARCHY' }, 'memory:diff')).toEqual({
      ...valid,
      matchMode: 'CLASS_NAME_AND_HIERARCHY',
    });
  });

  it.each([
    undefined,
    null,
    [],
    {},
    { ...valid, beforeSessionId: '../escape' },
    { ...valid, afterSessionId: 'before-1' },
    { ...valid, matchMode: 'CLASS_LOADER' },
  ])('rejects an invalid memory diff request: %j', (input) => {
    expect(() => requireMemoryDiffRequest(input, 'memory:diff')).toThrow('memory:diff');
  });
});

describe('source workspace IPC validation', () => {
  it('accepts bounded source requests emitted by the renderer', () => {
    const validSearch = { workspaceId: 'workspace-1', query: 'MainActivity', limit: 50 };
    expect(requireSourceSearchRequest(validSearch, IPC_CHANNELS.sourceSearch)).toEqual(validSearch);
    expect(requireSourceReadRequest({ workspaceId: 'workspace-1', relativePath: 'app/src/main/kotlin/MainActivity.kt' }, IPC_CHANNELS.sourceRead)).toEqual({
      workspaceId: 'workspace-1',
      relativePath: 'app/src/main/kotlin/MainActivity.kt',
    });
    expect(requireSourceAiUploadRequest({ workspaceId: 'workspace-1', allowed: true }, IPC_CHANNELS.sourceSetAiUpload)).toEqual({
      workspaceId: 'workspace-1',
      allowed: true,
    });
  });

  it('validates all source-resolution evidence variants without rewriting their semantic fields', () => {
    const input = {
      workspaceId: 'workspace-1',
      buildIdentityMatch: 'VERIFIED',
      evidence: [
        { kind: 'MANAGED_SYMBOL', id: 'managed:1', className: 'com.example.Main', methodName: 'render', signature: '()V' },
        { kind: 'NATIVE_SYMBOL', id: 'native:2', symbolName: 'nativeRun', libraryPath: 'libexample.so', sourceLine: 42 },
        { kind: 'ANDROID_RESOURCE', id: 'res:3', resourceType: 'layout', resourceName: 'screen_main' },
        { kind: 'TYPE_NAME', id: 'type:4', qualifiedName: 'com.example.MainActivity' },
        { kind: 'SOURCE_FILE_LINE', id: 'line:5', fileName: 'MainActivity.kt', packageHash: -12, line: 9 },
      ],
    } as const;
    expect(requireSourceResolveRequest(input, IPC_CHANNELS.sourceResolve)).toEqual(input);
  });

  it.each([
    [{ workspaceId: '../escape', query: 'x', limit: 1 }, IPC_CHANNELS.sourceSearch],
    [{ workspaceId: 'workspace', query: 'x', limit: 101 }, IPC_CHANNELS.sourceSearch],
    [{ workspaceId: 'workspace', relativePath: '../secret.kt' }, IPC_CHANNELS.sourceRead],
    [{ workspaceId: 'workspace', relativePath: 'C:\\secret.kt' }, IPC_CHANNELS.sourceRead],
    [{ workspaceId: 'workspace', relativePath: 'src//Main.kt' }, IPC_CHANNELS.sourceRead],
    [{ workspaceId: 'workspace', allowed: 'true' }, IPC_CHANNELS.sourceSetAiUpload],
    [{ workspaceId: 'workspace', buildIdentityMatch: 'UNKNOWN', evidence: [] }, IPC_CHANNELS.sourceResolve],
    [{ workspaceId: 'workspace', buildIdentityMatch: 'VERIFIED', evidence: [{ kind: 'TYPE_NAME', id: 'x' }] }, IPC_CHANNELS.sourceResolve],
  ] as const)('rejects malformed source input for %s', (input, channel) => {
    const validator =
      channel === IPC_CHANNELS.sourceSearch
        ? requireSourceSearchRequest
        : channel === IPC_CHANNELS.sourceRead
          ? requireSourceReadRequest
          : channel === IPC_CHANNELS.sourceSetAiUpload
            ? requireSourceAiUploadRequest
            : requireSourceResolveRequest;
    expect(() => validator(input, channel)).toThrow(channel);
  });
});

describe('AI IPC validation', () => {
  it('accepts an HTTP endpoint, preserves an empty endpoint clear, and keeps secret bytes opaque', () => {
    expect(requireAiConfigurationInput({ model: 'gpt-5.2', endpoint: 'https://api.example.test/v1/responses' }, IPC_CHANNELS.aiSaveConfiguration)).toEqual({
      model: 'gpt-5.2', endpoint: 'https://api.example.test/v1/responses',
    });
    expect(requireAiConfigurationInput({ model: 'gpt-5.2', endpoint: '' }, IPC_CHANNELS.aiSaveConfiguration)).toEqual({
      model: 'gpt-5.2', endpoint: '',
    });
    expect(requireAiCredential('sk-example-secret', IPC_CHANNELS.aiSaveCredential)).toBe('sk-example-secret');
    expect(requireAiAnalyzeRequest({ captureId: 'layout-capture-1', workspaceId: 'workspace-1', buildIdentityMatch: 'VERIFIED' }, IPC_CHANNELS.aiAnalyze)).toEqual({
      captureId: 'layout-capture-1', workspaceId: 'workspace-1', buildIdentityMatch: 'VERIFIED',
    });
  });

  it.each([
    [{ model: '', endpoint: 'https://api.example.test/v1/responses' }, IPC_CHANNELS.aiSaveConfiguration, requireAiConfigurationInput],
    [{ model: 'gpt-5.2', endpoint: 'file:///tmp/endpoint' }, IPC_CHANNELS.aiSaveConfiguration, requireAiConfigurationInput],
    ['  sk-secret', IPC_CHANNELS.aiSaveCredential, requireAiCredential],
    [{ captureId: '../capture' }, IPC_CHANNELS.aiAnalyze, requireAiAnalyzeRequest],
    [{ captureId: 'capture', buildIdentityMatch: 'VERIFIED' }, IPC_CHANNELS.aiAnalyze, requireAiAnalyzeRequest],
    [{ captureId: 'capture', selectedNodeId: 'node\0id' }, IPC_CHANNELS.aiAnalyze, requireAiAnalyzeRequest],
  ] as const)('rejects malformed AI input for %s', (input, channel, validator) => {
    expect(() => validator(input, channel)).toThrow(channel);
  });
});
