import type {
  AiAnalyzeRequest,
  AiConfigurationInput,
  ApplicationUiSettingsPatch,
  AiSourceCandidateOpenRequest,
  BatteryCaptureInput,
  BenchmarkCompareInput,
  BitmapImageRequest,
  CpuCaptureRequest,
  FrameCaptureInput,
  LayoutCaptureOptions,
  MemoryDiffRequest,
  MemoryInstanceDetailRequest,
  MemoryInstanceRequest,
  CpuSnapshotRequest,
  MethodCaptureRequest,
  MethodSnapshotRequest,
  SourceAiUploadRequest,
  SourceReadRequest,
  SourceResolveRequest,
  SourceSearchRequest,
  StartupCaptureInput,
  TraceCaptureInput,
} from './ipc.js';
import { DESTINATIONS, type AppDestination } from './destinations.js';
import { ACCENT_COLOR_PRESETS, parseArgbColor } from './settings-contract.js';
import type { ViewerMenuState } from './viewer-menu.js';
import type { SourceResolutionEvidence } from '@aps/source-workspace';
import type { CpuTransformRequest } from '@aps/simpleperf-profiler';

/**
 * Runtime validation for renderer-controlled identifiers that select a stored
 * record for deletion. TypeScript types disappear at Electron's IPC boundary.
 */
export const MAX_OPAQUE_RECORD_ID_LENGTH = 128;

export function requireOpaqueRecordId(value: unknown, channel: string): string {
  if (typeof value !== 'string') {
    throw new TypeError(`${channel}: record id must be a string`);
  }
  if (value.length === 0) {
    throw new TypeError(`${channel}: record id must not be empty`);
  }
  if (value.trim() !== value) {
    throw new TypeError(`${channel}: record id must not have leading or trailing whitespace`);
  }
  if (value.length > MAX_OPAQUE_RECORD_ID_LENGTH) {
    throw new TypeError(`${channel}: record id exceeds ${String(MAX_OPAQUE_RECORD_ID_LENGTH)} characters`);
  }
  if (value === '.' || value === '..' || /[\\/\0]/.test(value)) {
    throw new TypeError(`${channel}: record id must not contain a path component or separator`);
  }
  return value;
}


export function requireUiLanguage(value: unknown, channel: string): 'en' | 'zh' {
  if (value !== 'en' && value !== 'zh') throw new TypeError(`${channel}: language has invalid format`);
  return value;
}

/** Validates the closed navigation union before it can change native window state. */
export function requireAppDestination(value: unknown, channel: string): AppDestination {
  if (typeof value !== 'string' || !DESTINATIONS.includes(value as AppDestination)) {
    throw new TypeError(`${channel}: destination has invalid format`);
  }
  return value as AppDestination;
}

/**
 * Validates a layout capture before it can select a device, issue ADB commands,
 * or add a stored capture. Empty serial deliberately means “Auto device”.
 */
export function requireLayoutCaptureInput(
  serial: unknown,
  options: unknown,
  channel: string,
): { readonly requestedSerial: string; readonly options: LayoutCaptureOptions } {
  const requestedSerial = serial === '' ? '' : requireAdbSerial(serial, channel);
  if (options === undefined) return { requestedSerial, options: {} };
  const request = requireRecord(options, channel);
  requireOnlyKeys(request, ['archive', 'target'], channel, 'layout capture options');
  const archive = optionalBoolean(request['archive'], 'archive', channel);
  const target = optionalEnum(request['target'], 'target', ['foregroundApp', 'systemUi'] as const, channel);
  return {
    requestedSerial,
    options: {
      ...(archive === undefined ? {} : { archive }),
      ...(target === undefined ? {} : { target }),
    },
  };
}

/**
 * The settings store normalizes historic on-disk data. Renderer IPC is a trust
 * boundary instead: reject unknown fields and malformed values before merge.
 */
export function requireApplicationUiSettingsPatch(value: unknown, channel: string): ApplicationUiSettingsPatch {
  const patch = requireRecord(value, channel);
  requireOnlyKeys(
    patch,
    ['theme', 'language', 'accentColor', 'displayScalePercent', 'androidSdkPath', 'layoutInspector', 'simpleperf'],
    channel,
    'settings patch',
  );
  const theme = optionalEnum(patch['theme'], 'theme', ['system', 'light', 'dark'] as const, channel);
  const language = optionalEnum(
    patch['language'],
    'language',
    ['system', 'simplified_chinese', 'english'] as const,
    channel,
  );
  const accentColor = optionalEnum(
    patch['accentColor'],
    'accent color',
    ACCENT_COLOR_PRESETS.map((preset) => preset.key),
    channel,
  );
  const displayScalePercent = optionalBoundedInteger(patch['displayScalePercent'], 'display scale', 75, 200, channel);
  const androidSdkPath = optionalAndroidSdkPath(patch['androidSdkPath'], channel);
  const layoutInspector =
    patch['layoutInspector'] === undefined
      ? undefined
      : requireLayoutInspectorSettingsPatch(patch['layoutInspector'], channel);
  const simpleperf =
    patch['simpleperf'] === undefined ? undefined : requireSimpleperfSettingsPatch(patch['simpleperf'], channel);
  return {
    ...(theme === undefined ? {} : { theme }),
    ...(language === undefined ? {} : { language }),
    ...(accentColor === undefined ? {} : { accentColor }),
    ...(displayScalePercent === undefined ? {} : { displayScalePercent }),
    ...(androidSdkPath === undefined ? {} : { androidSdkPath }),
    ...(layoutInspector === undefined ? {} : { layoutInspector }),
    ...(simpleperf === undefined ? {} : { simpleperf }),
  };
}

/** Validates renderer-reported state before it controls native menu rendering. */
export function requireViewerMenuState(value: unknown, channel: string): ViewerMenuState {
  const state = requireRecord(value, channel);
  requireOnlyKeys(
    state,
    ['language', 'available', 'hasSnapshot', 'hasSelection', 'autoScan', 'archiveOperationInProgress', 'panels', 'view'],
    channel,
    'viewer menu state',
  );
  const panels = requireRecord(state['panels'], channel);
  requireOnlyKeys(panels, ['hierarchy', 'details', 'findings'], channel, 'viewer menu panels');
  const view = requireRecord(state['view'], channel);
  requireOnlyKeys(
    view,
    [
      'hideInvisibleHierarchyViews',
      'hideInvisibleFindings',
      'hideHierarchyIndices',
      'showHierarchyLayerVisibilityButtons',
      'showVisibleViewBounds',
      'showHierarchyIds',
    ],
    channel,
    'viewer menu view',
  );
  return {
    language: requireUiLanguage(state['language'], channel),
    available: requireBoolean(state['available'], 'available', channel),
    hasSnapshot: requireBoolean(state['hasSnapshot'], 'has snapshot', channel),
    hasSelection: requireBoolean(state['hasSelection'], 'has selection', channel),
    autoScan: requireBoolean(state['autoScan'], 'auto scan', channel),
    archiveOperationInProgress: requireBoolean(state['archiveOperationInProgress'], 'archive operation in progress', channel),
    panels: {
      hierarchy: requireBoolean(panels['hierarchy'], 'hierarchy panel', channel),
      details: requireBoolean(panels['details'], 'details panel', channel),
      findings: requireBoolean(panels['findings'], 'findings panel', channel),
    },
    view: {
      hideInvisibleHierarchyViews: requireBoolean(
        view['hideInvisibleHierarchyViews'],
        'hide invisible hierarchy views',
        channel,
      ),
      hideInvisibleFindings: requireBoolean(view['hideInvisibleFindings'], 'hide invisible findings', channel),
      hideHierarchyIndices: requireBoolean(view['hideHierarchyIndices'], 'hide hierarchy indices', channel),
      showHierarchyLayerVisibilityButtons: requireBoolean(
        view['showHierarchyLayerVisibilityButtons'],
        'show hierarchy layer visibility buttons',
        channel,
      ),
      showVisibleViewBounds: requireBoolean(view['showVisibleViewBounds'], 'show visible view bounds', channel),
      showHierarchyIds: requireBoolean(view['showHierarchyIds'], 'show hierarchy ids', channel),
    },
  };
}



/** Limits requested bitmap positions to the parser's own bounded record space. */
export const MAX_BITMAP_IMAGE_RECORD_INDEX = 1_000_000;

/** Validates a main-process-owned session/image lookup without accepting a path. */
export function requireBitmapImageRequest(value: unknown, channel: string): BitmapImageRequest {
  const request = requireRecord(value, channel);
  return {
    sessionId: requireOpaqueRecordId(request['sessionId'], channel),
    recordIndex: requireBoundedInteger(request['recordIndex'], 'record index', 0, MAX_BITMAP_IMAGE_RECORD_INDEX, channel),
  };
}

/** Validates an AI finding citation before it can select persisted source metadata. */
export function requireAiSourceCandidateOpenRequest(value: unknown, channel: string): AiSourceCandidateOpenRequest {
  const request = requireRecord(value, channel);
  return {
    sessionId: requireOpaqueRecordId(request['sessionId'], channel),
    candidateId: requireOpaqueRecordId(request['candidateId'], channel),
  };
}

/** Validates benchmark record selectors and optional threshold overrides. */
export function requireBenchmarkCompareInput(value: unknown, channel: string): BenchmarkCompareInput {
  const request = requireRecord(value, channel);
  const relativeThresholdPercent = requireOptionalNonNegativeNumber(
    request['relativeThresholdPercent'],
    'relative threshold percent',
    channel,
  );
  const absoluteThreshold = requireOptionalNonNegativeNumber(request['absoluteThreshold'], 'absolute threshold', channel);
  return {
    baselineId: requireOpaqueRecordId(request['baselineId'], channel),
    currentId: requireOpaqueRecordId(request['currentId'], channel),
    ...(relativeThresholdPercent === undefined ? {} : { relativeThresholdPercent }),
    ...(absoluteThreshold === undefined ? {} : { absoluteThreshold }),
  };
}

export function requireMemoryDiffRequest(value: unknown, channel: string): MemoryDiffRequest {
  const request = requireRecord(value, channel);
  const beforeSessionId = requireOpaqueRecordId(request['beforeSessionId'], channel);
  const afterSessionId = requireOpaqueRecordId(request['afterSessionId'], channel);
  if (beforeSessionId === afterSessionId) {
    throw new TypeError(`${channel}: baseline and current sessions must differ`);
  }
  const matchMode = request['matchMode'];
  if (matchMode !== undefined && matchMode !== 'CLASS_NAME' && matchMode !== 'CLASS_NAME_AND_HIERARCHY') {
    throw new TypeError(`${channel}: heap diff match mode has invalid format`);
  }
  return {
    beforeSessionId,
    afterSessionId,
    ...(matchMode !== undefined ? { matchMode } : {}),
  };
}

const MAX_PROFILE_QUERY_TEXT_LENGTH = 512;
const MAX_PROFILE_THREAD_KEY_LENGTH = 1_024;
const MAX_PROFILE_TRANSFORMS = 64;
const MAX_PROFILE_TRANSFORM_PATH_LENGTH = 128;
const MAX_PROFILE_RESOURCE_LENGTH = 4_096;
const MAX_FUNCTION_ID_LENGTH = 128;
const MAX_MEMORY_CLASS_NAME_LENGTH = 1_024;
const MAX_MEMORY_HEAP_NAME_LENGTH = 256;
const MAX_MEMORY_INSTANCE_LIMIT = 10_000;

/** Validates an ART trace snapshot query before it can load a persisted session. */
export function requireMethodSnapshotRequest(value: unknown, channel: string): MethodSnapshotRequest {
  const request = requireRecord(value, channel);
  const direction = request['direction'];
  if (direction !== 'FORWARD' && direction !== 'INVERTED') {
    throw new TypeError(`${channel}: direction has invalid format`);
  }
  const rankBy = request['rankBy'];
  if (rankBy !== 'SELF_MICROS' && rankBy !== 'TOTAL_MICROS' && rankBy !== 'CALL_COUNT' && rankBy !== 'SYMBOL') {
    throw new TypeError(`${channel}: rank by has invalid format`);
  }
  return {
    id: requireOpaqueRecordId(request['id'], channel),
    ...(request['threadKey'] === undefined
      ? {}
      : { threadKey: requireOptionalProfileText(request['threadKey'], 'thread key', MAX_PROFILE_THREAD_KEY_LENGTH, channel) }),
    searchText: requireProfileText(request['searchText'], 'search text', MAX_PROFILE_QUERY_TEXT_LENGTH, channel),
    direction,
    transforms: requireCpuTransforms(request['transforms'], channel),
    rankBy,
  };
}

/** Validates a Simpleperf snapshot query before it can load a persisted session. */
export function requireCpuSnapshotRequest(value: unknown, channel: string): CpuSnapshotRequest {
  const request = requireRecord(value, channel);
  const implementation = request['implementation'];
  if (implementation !== 'ALL' && implementation !== 'SCRIPT' && implementation !== 'NATIVE') {
    throw new TypeError(`${channel}: implementation has invalid format`);
  }
  const direction = request['direction'];
  if (direction !== 'FORWARD' && direction !== 'INVERTED') {
    throw new TypeError(`${channel}: direction has invalid format`);
  }
  return {
    id: requireOpaqueRecordId(request['id'], channel),
    ...(request['threadKey'] === undefined
      ? {}
      : { threadKey: requireOptionalProfileText(request['threadKey'], 'thread key', MAX_PROFILE_THREAD_KEY_LENGTH, channel) }),
    searchText: requireProfileText(request['searchText'], 'search text', MAX_PROFILE_QUERY_TEXT_LENGTH, channel),
    implementation,
    direction,
    transforms: requireCpuTransforms(request['transforms'], channel),
  };
}

/** Validates a memory class query before it reaches the parsed heap graph. */
export function requireMemoryInstanceRequest(value: unknown, channel: string): MemoryInstanceRequest {
  const request = requireRecord(value, channel);
  const heap = request['heap'];
  if (heap !== undefined && typeof heap !== 'string') {
    throw new TypeError(`${channel}: heap must be text`);
  }
  const limit = request['limit'];
  const validatedLimit =
    limit === undefined ? undefined : requireBoundedInteger(limit, 'limit', 1, MAX_MEMORY_INSTANCE_LIMIT, channel);
  return {
    sessionId: requireOpaqueRecordId(request['sessionId'], channel),
    className: requireBoundedText(request['className'], 'class name', 1, MAX_MEMORY_CLASS_NAME_LENGTH, channel),
    ...(heap === undefined
      ? {}
      : { heap: requireBoundedText(heap, 'heap', 1, MAX_MEMORY_HEAP_NAME_LENGTH, channel) }),
    ...(validatedLimit === undefined ? {} : { limit: validatedLimit }),
  };
}

/** Validates a memory object lookup before it reaches the parsed heap graph. */
export function requireMemoryInstanceDetailRequest(
  value: unknown,
  channel: string,
): MemoryInstanceDetailRequest {
  const request = requireRecord(value, channel);
  return {
    sessionId: requireOpaqueRecordId(request['sessionId'], channel),
    objectId: requireObjectId(request['objectId'], channel),
  };
}

function requireCpuTransforms(value: unknown, channel: string): readonly CpuTransformRequest[] {
  if (!Array.isArray(value)) throw new TypeError(`${channel}: transforms must be an array`);
  if (value.length > MAX_PROFILE_TRANSFORMS) {
    throw new TypeError(`${channel}: transforms exceed ${String(MAX_PROFILE_TRANSFORMS)} entries`);
  }
  const transforms: CpuTransformRequest[] = [];
  for (let index = 0; index < value.length; index += 1) {
    transforms.push(requireCpuTransform(value[index], channel));
  }
  return transforms;
}

function requireCpuTransform(value: unknown, channel: string): CpuTransformRequest {
  const transform = requireRecord(value, channel);
  const kind = transform['kind'];
  switch (kind) {
    case 'FOCUS_CALL_NODE': {
      const path = transform['path'];
      if (!Array.isArray(path) || path.length === 0 || path.length > MAX_PROFILE_TRANSFORM_PATH_LENGTH) {
        throw new TypeError(`${channel}: focus path has invalid format`);
      }
      return {
        kind,
        path: Array.from(path, (functionId) => requireFunctionId(functionId, channel)),
      };
    }
    case 'FOCUS_FUNCTION':
    case 'DROP_FUNCTION':
    case 'COLLAPSE_RECURSION':
      return { kind, functionId: requireFunctionId(transform['functionId'], channel) };
    case 'COLLAPSE_RESOURCE':
      return {
        kind,
        resource: requireBoundedText(transform['resource'], 'resource', 1, MAX_PROFILE_RESOURCE_LENGTH, channel),
      };
    default:
      throw new TypeError(`${channel}: transform kind has invalid format`);
  }
}

function requireFunctionId(value: unknown, channel: string): string {
  if (
    typeof value !== 'string' ||
    value.length === 0 ||
    value.length > MAX_FUNCTION_ID_LENGTH ||
    !/^\d+$/.test(value)
  ) {
    throw new TypeError(`${channel}: function id must be a decimal bigint-like string`);
  }
  return value;
}

function requireObjectId(value: unknown, channel: string): string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_FUNCTION_ID_LENGTH || !/^(?:0x)?[0-9a-fA-F]+$/i.test(value)) {
    throw new TypeError(`${channel}: object id must be a hexadecimal identifier`);
  }
  return value;
}

function requireProfileText(value: unknown, label: string, maximumLength: number, channel: string): string {
  return requireBoundedText(value, label, 0, maximumLength, channel);
}

function requireOptionalProfileText(value: unknown, label: string, maximumLength: number, channel: string): string {
  return requireBoundedText(value, label, 0, maximumLength, channel);
}

function requireBoundedText(value: unknown, label: string, minimumLength: number, maximumLength: number, channel: string): string {
  if (typeof value !== 'string' || value.length < minimumLength || value.length > maximumLength || value.includes('\0')) {
    throw new TypeError(`${channel}: ${label} must be text from ${String(minimumLength)} to ${String(maximumLength)} characters`);
  }
  return value;
}

const MAX_SOURCE_SEARCH_QUERY_LENGTH = 512;
const MAX_SOURCE_RELATIVE_PATH_LENGTH = 4_096;
const MAX_SOURCE_RESOLUTION_EVIDENCE = 100;
const MAX_SOURCE_EVIDENCE_TEXT_LENGTH = 4_096;
const MAX_AI_MODEL_LENGTH = 256;
const MAX_AI_ENDPOINT_LENGTH = 2_048;
const MAX_AI_CREDENTIAL_LENGTH = 16 * 1_024;
const MAX_LAYOUT_NODE_ID_LENGTH = 1_024;

/** Validates a source-workspace symbol search before it reaches the shared index. */
export function requireSourceSearchRequest(value: unknown, channel: string): SourceSearchRequest {
  const request = requireRecord(value, channel);
  return {
    workspaceId: requireOpaqueRecordId(request['workspaceId'], channel),
    query: requireText(request['query'], 'query', 0, MAX_SOURCE_SEARCH_QUERY_LENGTH, channel),
    limit: requireBoundedInteger(request['limit'], 'limit', 1, 100, channel),
  };
}

/** Validates a source path as an index-relative POSIX path, never a host path. */
export function requireSourceReadRequest(value: unknown, channel: string): SourceReadRequest {
  const request = requireRecord(value, channel);
  return {
    workspaceId: requireOpaqueRecordId(request['workspaceId'], channel),
    relativePath: requireRelativeSourcePath(request['relativePath'], channel),
  };
}

/** Validates an explicit source-upload consent switch before storing it. */
export function requireSourceAiUploadRequest(value: unknown, channel: string): SourceAiUploadRequest {
  const request = requireRecord(value, channel);
  if (typeof request['allowed'] !== 'boolean') throw new TypeError(`${channel}: allowed must be a boolean`);
  return { workspaceId: requireOpaqueRecordId(request['workspaceId'], channel), allowed: request['allowed'] };
}

/** Validates source-resolution evidence without treating renderer input as trusted model data. */
export function requireSourceResolveRequest(value: unknown, channel: string): SourceResolveRequest {
  const request = requireRecord(value, channel);
  const evidence = request['evidence'];
  if (!Array.isArray(evidence) || evidence.length > MAX_SOURCE_RESOLUTION_EVIDENCE) {
    throw new TypeError(`${channel}: evidence must be an array of at most ${String(MAX_SOURCE_RESOLUTION_EVIDENCE)} entries`);
  }
  const buildIdentityMatch = request['buildIdentityMatch'];
  if (buildIdentityMatch !== 'VERIFIED' && buildIdentityMatch !== 'UNVERIFIED') {
    throw new TypeError(`${channel}: build identity match has invalid format`);
  }
  return {
    workspaceId: requireOpaqueRecordId(request['workspaceId'], channel),
    evidence: evidence.map((entry) => requireSourceResolutionEvidence(entry, channel)),
    buildIdentityMatch,
  };
}

/** Validates the persisted AI model/endpoint configuration before credentials storage is touched. */
export function requireAiConfigurationInput(value: unknown, channel: string): AiConfigurationInput {
  const request = requireRecord(value, channel);
  const endpoint = requireText(request['endpoint'], 'endpoint', 0, MAX_AI_ENDPOINT_LENGTH, channel);
  if (endpoint.length > 0) {
    let parsed: URL;
    try {
      parsed = new URL(endpoint);
    } catch {
      throw new TypeError(`${channel}: endpoint must be an absolute http(s) URL`);
    }
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
      throw new TypeError(`${channel}: endpoint must use http or https`);
    }
  }
  return {
    model: requireText(request['model'], 'model', 1, MAX_AI_MODEL_LENGTH, channel),
    endpoint,
  };
}

/** Validates an API credential's type and size without logging or transforming its secret value. */
export function requireAiCredential(value: unknown, channel: string): string {
  return requireText(value, 'credential', 1, MAX_AI_CREDENTIAL_LENGTH, channel);
}

/** Validates an AI analysis request before persisted layout/source data or network services are used. */
export function requireAiAnalyzeRequest(value: unknown, channel: string): AiAnalyzeRequest {
  const request = requireRecord(value, channel);
  const workspaceId = request['workspaceId'];
  const buildIdentityMatch = request['buildIdentityMatch'];
  if (workspaceId === undefined && buildIdentityMatch !== undefined) {
    throw new TypeError(`${channel}: build identity match requires a source workspace`);
  }
  if (buildIdentityMatch !== undefined && buildIdentityMatch !== 'VERIFIED' && buildIdentityMatch !== 'UNVERIFIED') {
    throw new TypeError(`${channel}: build identity match has invalid format`);
  }
  return {
    captureId: requireOpaqueRecordId(request['captureId'], channel),
    ...(request['selectedNodeId'] === undefined
      ? {}
      : { selectedNodeId: requireText(request['selectedNodeId'], 'selected node id', 1, MAX_LAYOUT_NODE_ID_LENGTH, channel) }),
    ...(request['model'] === undefined
      ? {}
      : { model: requireText(request['model'], 'model', 1, MAX_AI_MODEL_LENGTH, channel) }),
    ...(workspaceId === undefined ? {} : { workspaceId: requireOpaqueRecordId(workspaceId, channel) }),
    ...(buildIdentityMatch === undefined ? {} : { buildIdentityMatch }),
  };
}

function requireSourceResolutionEvidence(value: unknown, channel: string): SourceResolutionEvidence {
  const evidence = requireRecord(value, channel);
  const id = requireText(evidence['id'], 'evidence id', 1, MAX_SOURCE_EVIDENCE_TEXT_LENGTH, channel);
  switch (evidence['kind']) {
    case 'MANAGED_SYMBOL':
      return {
        kind: 'MANAGED_SYMBOL',
        id,
        methodName: requireText(evidence['methodName'], 'method name', 1, MAX_SOURCE_EVIDENCE_TEXT_LENGTH, channel),
        ...(optionalText(evidence['className'], 'class name', channel) !== undefined
          ? { className: optionalText(evidence['className'], 'class name', channel) }
          : {}),
        ...(optionalText(evidence['signature'], 'signature', channel) !== undefined
          ? { signature: optionalText(evidence['signature'], 'signature', channel) }
          : {}),
        ...(optionalText(evidence['resourcePath'], 'resource path', channel) !== undefined
          ? { resourcePath: optionalText(evidence['resourcePath'], 'resource path', channel) }
          : {}),
      };
    case 'NATIVE_SYMBOL':
      return {
        kind: 'NATIVE_SYMBOL',
        id,
        symbolName: requireText(evidence['symbolName'], 'symbol name', 1, MAX_SOURCE_EVIDENCE_TEXT_LENGTH, channel),
        ...(optionalText(evidence['libraryPath'], 'library path', channel) !== undefined
          ? { libraryPath: optionalText(evidence['libraryPath'], 'library path', channel) }
          : {}),
        ...(optionalText(evidence['buildId'], 'build id', channel) !== undefined
          ? { buildId: optionalText(evidence['buildId'], 'build id', channel) }
          : {}),
        ...(optionalText(evidence['sourcePath'], 'source path', channel) !== undefined
          ? { sourcePath: optionalText(evidence['sourcePath'], 'source path', channel) }
          : {}),
        ...(evidence['sourceLine'] === undefined
          ? {}
          : { sourceLine: requireBoundedInteger(evidence['sourceLine'], 'source line', 1, MAX_ANDROID_SIGNED_INTEGER, channel) }),
      };
    case 'ANDROID_RESOURCE':
      return {
        kind: 'ANDROID_RESOURCE',
        id,
        resourceType: requireText(evidence['resourceType'], 'resource type', 1, MAX_SOURCE_EVIDENCE_TEXT_LENGTH, channel),
        resourceName: requireText(evidence['resourceName'], 'resource name', 1, MAX_SOURCE_EVIDENCE_TEXT_LENGTH, channel),
      };
    case 'TYPE_NAME':
      return {
        kind: 'TYPE_NAME',
        id,
        qualifiedName: requireText(evidence['qualifiedName'], 'qualified name', 1, MAX_SOURCE_EVIDENCE_TEXT_LENGTH, channel),
      };
    case 'SOURCE_FILE_LINE':
      return {
        kind: 'SOURCE_FILE_LINE',
        id,
        fileName: requireText(evidence['fileName'], 'file name', 1, MAX_SOURCE_EVIDENCE_TEXT_LENGTH, channel),
        packageHash: requireBoundedInteger(
          evidence['packageHash'],
          'package hash',
          -MAX_ANDROID_SIGNED_INTEGER - 1,
          MAX_ANDROID_SIGNED_INTEGER,
          channel,
        ),
        line: requireBoundedInteger(evidence['line'], 'line', 1, MAX_ANDROID_SIGNED_INTEGER, channel),
      };
    default:
      throw new TypeError(`${channel}: evidence kind has invalid format`);
  }
}

function optionalText(value: unknown, label: string, channel: string): string | undefined {
  return value === undefined ? undefined : requireText(value, label, 1, MAX_SOURCE_EVIDENCE_TEXT_LENGTH, channel);
}

function requireRelativeSourcePath(value: unknown, channel: string): string {
  const path = requireText(value, 'relative path', 1, MAX_SOURCE_RELATIVE_PATH_LENGTH, channel);
  if (path.startsWith('/') || path.startsWith('\\') || /^[A-Za-z]:/.test(path) || path.includes('\\')) {
    throw new TypeError(`${channel}: relative path must use index-relative POSIX form`);
  }
  if (path.split('/').some((part) => part.length === 0 || part === '.' || part === '..')) {
    throw new TypeError(`${channel}: relative path must not contain empty or traversal segments`);
  }
  return path;
}

function requireText(value: unknown, label: string, minimumLength: number, maximumLength: number, channel: string): string {
  if (typeof value !== 'string' || value.trim() !== value || value.length < minimumLength || value.length > maximumLength || value.includes('\0')) {
    throw new TypeError(`${channel}: ${label} must be trimmed text from ${String(minimumLength)} to ${String(maximumLength)} characters`);
  }
  return value;
}

/** Validates a user-selected trace path before any hashing or file-system access. */
export function requireTraceImportPath(value: unknown, channel: string): string {
  if (typeof value !== 'string') throw new TypeError(`${channel}: trace path must be a string`);
  if (value.length === 0 || value.trim() !== value) {
    throw new TypeError(`${channel}: trace path must not be empty or padded`);
  }
  if (value.includes('\0')) throw new TypeError(`${channel}: trace path must not contain NUL`);
  if (!isAbsolutePath(value)) throw new TypeError(`${channel}: trace path must be absolute`);
  return value;
}

function isAbsolutePath(value: string): boolean {
  return value.startsWith('/') || /^[A-Za-z]:[\\/]/.test(value) || value.startsWith('\\\\');
}

const ADB_SERIAL_PATTERN = /^[A-Za-z0-9_.:[\]-]+$/;
const ANDROID_PACKAGE_NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$/;
const JAVA_CLASS_NAME_PATTERN = /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$/;
const PERFETTO_DATA_SOURCE_NAME_PATTERN = /^[A-Za-z0-9_.-]+$/;
const MAX_ANDROID_PACKAGE_NAME_LENGTH = 255;
const MAX_ANDROID_SIGNED_INTEGER = 2_147_483_647;

// Match the Kotlin Perfetto capture UI: 1–600 seconds and 1 MiB–1 GiB.
export const MINIMUM_TRACE_DURATION_MILLIS = 1_000;
export const MAXIMUM_TRACE_DURATION_MILLIS = 600_000;
export const MINIMUM_TRACE_BUFFER_SIZE_KB = 1_024;
export const MAXIMUM_TRACE_BUFFER_SIZE_KB = 1_048_576;

function requireLayoutInspectorSettingsPatch(
  value: unknown,
  channel: string,
): NonNullable<ApplicationUiSettingsPatch['layoutInspector']> {
  const patch = requireRecord(value, channel);
  requireOnlyKeys(
    patch,
    [
      'hideInvisibleHierarchyViews',
      'hideInvisibleFindings',
      'hideHierarchyIndices',
      'showHierarchyIds',
      'showHierarchyLayerVisibilityButtons',
      'showVisibleViewBounds',
      'snapshotSizeMultiplier',
      'canvasHitTestOrder',
      'canvasBorderColors',
    ],
    channel,
    'layout inspector settings',
  );
  const colors =
    patch['canvasBorderColors'] === undefined
      ? undefined
      : requireCanvasBorderColorsPatch(patch['canvasBorderColors'], channel);
  const hideInvisibleHierarchyViews = optionalBoolean(
    patch['hideInvisibleHierarchyViews'],
    'hide invisible hierarchy views',
    channel,
  );
  const hideInvisibleFindings = optionalBoolean(patch['hideInvisibleFindings'], 'hide invisible findings', channel);
  const hideHierarchyIndices = optionalBoolean(patch['hideHierarchyIndices'], 'hide hierarchy indices', channel);
  const showHierarchyIds = optionalBoolean(patch['showHierarchyIds'], 'show hierarchy ids', channel);
  const showHierarchyLayerVisibilityButtons = optionalBoolean(
    patch['showHierarchyLayerVisibilityButtons'],
    'show hierarchy layer visibility buttons',
    channel,
  );
  const showVisibleViewBounds = optionalBoolean(patch['showVisibleViewBounds'], 'show visible view bounds', channel);
  const snapshotSizeMultiplier = optionalBoundedInteger(
    patch['snapshotSizeMultiplier'],
    'snapshot size multiplier',
    1,
    10,
    channel,
  );
  const canvasHitTestOrder = optionalEnum(
    patch['canvasHitTestOrder'],
    'canvas hit test order',
    ['smallest-area', 'z-order'] as const,
    channel,
  );
  return {
    ...(hideInvisibleHierarchyViews === undefined ? {} : { hideInvisibleHierarchyViews }),
    ...(hideInvisibleFindings === undefined ? {} : { hideInvisibleFindings }),
    ...(hideHierarchyIndices === undefined ? {} : { hideHierarchyIndices }),
    ...(showHierarchyIds === undefined ? {} : { showHierarchyIds }),
    ...(showHierarchyLayerVisibilityButtons === undefined ? {} : { showHierarchyLayerVisibilityButtons }),
    ...(showVisibleViewBounds === undefined ? {} : { showVisibleViewBounds }),
    ...(snapshotSizeMultiplier === undefined ? {} : { snapshotSizeMultiplier }),
    ...(canvasHitTestOrder === undefined ? {} : { canvasHitTestOrder }),
    ...(colors === undefined ? {} : { canvasBorderColors: colors }),
  };
}

function requireCanvasBorderColorsPatch(
  value: unknown,
  channel: string,
): NonNullable<NonNullable<ApplicationUiSettingsPatch['layoutInspector']>['canvasBorderColors']> {
  const colors = requireRecord(value, channel);
  requireOnlyKeys(colors, ['normal', 'hovered', 'selected'], channel, 'canvas border colors');
  const normal = optionalArgbColor(colors['normal'], 'normal border color', channel);
  const hovered = optionalArgbColor(colors['hovered'], 'hovered border color', channel);
  const selected = optionalArgbColor(colors['selected'], 'selected border color', channel);
  return {
    ...(normal === undefined ? {} : { normal }),
    ...(hovered === undefined ? {} : { hovered }),
    ...(selected === undefined ? {} : { selected }),
  };
}

function requireSimpleperfSettingsPatch(
  value: unknown,
  channel: string,
): NonNullable<ApplicationUiSettingsPatch['simpleperf']> {
  const patch = requireRecord(value, channel);
  requireOnlyKeys(patch, ['flameTooltipMode', 'engine', 'captureDefaults'], channel, 'simpleperf settings');
  const flameTooltipMode = optionalEnum(
    patch['flameTooltipMode'],
    'flame tooltip mode',
    ['follow-mouse', 'fixed'] as const,
    channel,
  );
  const engine = optionalEnum(patch['engine'], 'simpleperf engine', ['local', 'firefox-local', 'firefox'] as const, channel);
  const captureDefaults =
    patch['captureDefaults'] === undefined
      ? undefined
      : requireSimpleperfCaptureDefaultsPatch(patch['captureDefaults'], channel);
  return {
    ...(flameTooltipMode === undefined ? {} : { flameTooltipMode }),
    ...(engine === undefined ? {} : { engine }),
    ...(captureDefaults === undefined ? {} : { captureDefaults }),
  };
}

function requireSimpleperfCaptureDefaultsPatch(
  value: unknown,
  channel: string,
): NonNullable<NonNullable<ApplicationUiSettingsPatch['simpleperf']>['captureDefaults']> {
  const patch = requireRecord(value, channel);
  requireOnlyKeys(
    patch,
    ['template', 'target', 'event', 'frequencyHertz', 'periodEvents', 'rateMode', 'durationSeconds', 'callGraph', 'scope'],
    channel,
    'simpleperf capture defaults',
  );
  const template = optionalEnum(
    patch['template'],
    'sampling template',
    ['APP_CPU_BASIC', 'UI_THREAD_FOCUS', 'NATIVE_HOTSPOT', 'LOW_OVERHEAD', 'SYSTEM_PROCESS'] as const,
    channel,
  );
  const target = optionalEnum(patch['target'], 'simpleperf target', ['APP', 'SYSTEM_WIDE'] as const, channel);
  const event = optionalCommandToken(patch['event'], 'event', channel);
  const frequencyHertz = optionalBoundedInteger(patch['frequencyHertz'], 'frequency', 1, 100000, channel);
  const periodEvents = optionalBoundedInteger(patch['periodEvents'], 'period', 1, 1_000_000_000, channel);
  const rateMode = optionalEnum(patch['rateMode'], 'rate mode', ['FREQUENCY', 'PERIOD'] as const, channel);
  const durationSeconds = optionalBoundedInteger(patch['durationSeconds'], 'duration', 1, 3600, channel);
  const callGraph = optionalEnum(patch['callGraph'], 'call graph', ['DWARF', 'FRAME_POINTER', 'NONE'] as const, channel);
  const scope = optionalEnum(patch['scope'], 'simpleperf scope', ['BOTH', 'USER', 'KERNEL'] as const, channel);
  return {
    ...(template === undefined ? {} : { template }),
    ...(target === undefined ? {} : { target }),
    ...(event === undefined ? {} : { event }),
    ...(frequencyHertz === undefined ? {} : { frequencyHertz }),
    ...(periodEvents === undefined ? {} : { periodEvents }),
    ...(rateMode === undefined ? {} : { rateMode }),
    ...(durationSeconds === undefined ? {} : { durationSeconds }),
    ...(callGraph === undefined ? {} : { callGraph }),
    ...(scope === undefined ? {} : { scope }),
  };
}

function requireOnlyKeys(
  value: Record<string, unknown>,
  allowed: readonly string[],
  channel: string,
  label: string,
): void {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) throw new TypeError(`${channel}: ${label} has unsupported field ${key}`);
  }
}

function requireBoolean(value: unknown, label: string, channel: string): boolean {
  if (typeof value !== 'boolean') throw new TypeError(`${channel}: ${label} must be a boolean`);
  return value;
}

function optionalBoolean(value: unknown, label: string, channel: string): boolean | undefined {
  return value === undefined ? undefined : requireBoolean(value, label, channel);
}

function optionalBoundedInteger(
  value: unknown,
  label: string,
  minimum: number,
  maximum: number,
  channel: string,
): number | undefined {
  return value === undefined ? undefined : requireBoundedInteger(value, label, minimum, maximum, channel);
}

function optionalEnum<T extends string>(
  value: unknown,
  label: string,
  allowed: readonly T[],
  channel: string,
): T | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || !allowed.includes(value as T)) {
    throw new TypeError(`${channel}: ${label} has invalid format`);
  }
  return value as T;
}

function optionalAndroidSdkPath(value: unknown, channel: string): string | null | undefined {
  if (value === undefined || value === null) return value;
  if (
    typeof value !== 'string' ||
    value.trim() !== value ||
    value.length === 0 ||
    value.length > 4096 ||
    value.includes('\0')
  ) {
    throw new TypeError(`${channel}: Android SDK path must be bounded, trimmed text`);
  }
  if (!value.startsWith('/') && !/^[A-Za-z]:[\\/]/.test(value) && !/^\\\\[^\\/]+[\\/]/.test(value)) {
    throw new TypeError(`${channel}: Android SDK path must be absolute`);
  }
  return value;
}

function optionalArgbColor(value: unknown, label: string, channel: string): string | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || value.trim() !== value) {
    throw new TypeError(`${channel}: ${label} must be an ARGB color`);
  }
  const parsed = parseArgbColor(value);
  if (parsed === undefined) throw new TypeError(`${channel}: ${label} must be an ARGB color`);
  return parsed;
}

function optionalCommandToken(value: unknown, label: string, channel: string): string | undefined {
  if (value === undefined) return undefined;
  const token = requireCommandToken(value, label, channel);
  if (token.length > 256) throw new TypeError(`${channel}: ${label} exceeds 256 characters`);
  return token;
}

function requireRecord(value: unknown, channel: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${channel}: request must be an object`);
  }
  return value as Record<string, unknown>;
}

export function requireAdbSerial(value: unknown, channel: string): string {
  if (typeof value !== 'string') throw new TypeError(`${channel}: serial must be a string`);
  if (!ADB_SERIAL_PATTERN.test(value) || value.startsWith('-')) {
    throw new TypeError(`${channel}: serial has invalid format`);
  }
  return value;
}

function requireAndroidPackageName(value: unknown, channel: string): string {
  if (typeof value !== 'string') throw new TypeError(`${channel}: package name must be a string`);
  if (value.trim() !== value || value.length === 0) {
    throw new TypeError(`${channel}: package name must not be empty or padded`);
  }
  if (value.length > MAX_ANDROID_PACKAGE_NAME_LENGTH || !ANDROID_PACKAGE_NAME_PATTERN.test(value)) {
    throw new TypeError(`${channel}: package name has invalid format`);
  }
  return value;
}

function requireAndroidComponentName(value: unknown, packageName: string, channel: string): string {
  if (typeof value !== 'string' || value.trim() !== value || value.length === 0 || /\s|\0/.test(value)) {
    throw new TypeError(`${channel}: component name must be a non-blank Android component`);
  }
  const separator = value.indexOf('/');
  if (separator <= 0 || separator !== value.lastIndexOf('/')) {
    throw new TypeError(`${channel}: component name must contain one package/class separator`);
  }
  const componentPackage = requireAndroidPackageName(value.slice(0, separator), channel);
  const activityName = value.slice(separator + 1);
  const qualifiedActivity = activityName.startsWith('.') ? packageName + activityName : activityName;
  if (
    componentPackage !== packageName ||
    !JAVA_CLASS_NAME_PATTERN.test(qualifiedActivity) ||
    !qualifiedActivity.startsWith(packageName + '.')
  ) {
    throw new TypeError(`${channel}: component name must identify an activity in the requested package`);
  }
  return value;
}

/** Validates a renderer-controlled Startup request before ADB is opened. */
export function requireStartupCaptureInput(value: unknown, channel: string): StartupCaptureInput {
  const request = requireRecord(value, channel);
  const packageName = requireAndroidPackageName(request['packageName'], channel);
  const componentName = request['componentName'];
  const requestedType = request['requestedType'];
  if (requestedType !== 'COLD' && requestedType !== 'WARM' && requestedType !== 'HOT') {
    throw new TypeError(`${channel}: requested startup type has invalid format`);
  }
  return {
    serial: requireAdbSerial(request['serial'], channel),
    packageName,
    ...(componentName === undefined ? {} : { componentName: requireAndroidComponentName(componentName, packageName, channel) }),
    requestedType,
    warmupRuns: requireBoundedInteger(request['warmupRuns'], 'warmup runs', 0, 100, channel),
    measuredRuns: requireBoundedInteger(request['measuredRuns'], 'measured runs', 1, 100, channel),
    timeoutSeconds: requireBoundedInteger(request['timeoutSeconds'], 'timeout', 5, 300, channel),
  };
}

/** Validates a renderer-controlled battery request before ADB is opened. */
export function requireBatteryCaptureInput(value: unknown, channel: string): BatteryCaptureInput {
  const request = requireRecord(value, channel);
  const mode = request['mode'];
  if (mode !== 'INTERACTIVE' && mode !== 'TIMED' && mode !== 'REPEATED' && mode !== 'ONLINE') {
    throw new TypeError(`${channel}: battery mode has invalid format`);
  }
  return {
    serial: requireAdbSerial(request['serial'], channel),
    packageName: requireAndroidPackageName(request['packageName'], channel),
    uid: requireBoundedInteger(request['uid'], 'UID', 0, MAX_ANDROID_SIGNED_INTEGER, channel),
    mode,
    durationSeconds: requireBoundedInteger(request['durationSeconds'], 'duration', 5, 3600, channel),
    pollingIntervalSeconds: requireBoundedInteger(request['pollingIntervalSeconds'], 'polling interval', 5, 60, channel),
    measuredRuns: requireBoundedInteger(request['measuredRuns'], 'measured runs', 1, 50, channel),
    cooldownSeconds: requireBoundedInteger(request['cooldownSeconds'], 'cooldown', 0, 300, channel),
  };
}

/** Validates a renderer-controlled method trace request before ADB and temporary files are opened. */
export function requireMethodCaptureInput(value: unknown, channel: string): MethodCaptureRequest {
  const request = requireRecord(value, channel);
  return {
    serial: requireAdbSerial(request['serial'], channel),
    packageName: requireAndroidPackageName(request['packageName'], channel),
    pid: requireBoundedInteger(request['pid'], 'PID', 1, MAX_ANDROID_SIGNED_INTEGER, channel),
    // Match the Electron recording control's one-to-120-second capture window.
    durationSeconds: requireBoundedInteger(request['durationSeconds'], 'duration', 1, 120, channel),
  };
}

/** Validates a renderer-controlled Perfetto capture request before ADB is opened. */
export function requireTraceCaptureInput(value: unknown, channel: string): TraceCaptureInput {
  const request = requireRecord(value, channel);
  const dataSource = request['dataSource'];
  if (typeof dataSource !== 'string' || !PERFETTO_DATA_SOURCE_NAME_PATTERN.test(dataSource)) {
    throw new TypeError(`${channel}: data source must be a Perfetto identifier`);
  }
  return {
    serial: requireAdbSerial(request['serial'], channel),
    durationMillis: requireBoundedInteger(
      request['durationMillis'],
      'duration',
      MINIMUM_TRACE_DURATION_MILLIS,
      MAXIMUM_TRACE_DURATION_MILLIS,
      channel,
    ),
    bufferSizeKb: requireBoundedInteger(
      request['bufferSizeKb'],
      'buffer size',
      MINIMUM_TRACE_BUFFER_SIZE_KB,
      MAXIMUM_TRACE_BUFFER_SIZE_KB,
      channel,
    ),
    dataSource,
  };
}

/** Validates a device-mutating Simpleperf request before ADB is opened. */
export function requireCpuCaptureInput(value: unknown, channel: string): CpuCaptureRequest {
  const request = requireRecord(value, channel);
  const target = request['target'];
  if (target !== 'APP' && target !== 'SYSTEM_WIDE') throw new TypeError(`${channel}: target has invalid format`);
  const event = requireCommandToken(request['event'], 'event', channel);
  const rateMode = request['rateMode'];
  if (rateMode !== 'FREQUENCY' && rateMode !== 'PERIOD') throw new TypeError(`${channel}: rate mode has invalid format`);
  const callGraph = request['callGraph'];
  if (callGraph !== 'DWARF' && callGraph !== 'FRAME_POINTER' && callGraph !== 'NONE') {
    throw new TypeError(`${channel}: call graph has invalid format`);
  }
  const scope = request['scope'];
  if (scope !== 'BOTH' && scope !== 'USER' && scope !== 'KERNEL') throw new TypeError(`${channel}: scope has invalid format`);
  const packageName = request['packageName'];
  if (target === 'APP' && packageName === undefined) throw new TypeError(`${channel}: application target requires package name`);
  return {
    serial: requireAdbSerial(request['serial'], channel),
    ...(packageName === undefined ? {} : { packageName: requireAndroidPackageName(packageName, channel) }),
    target,
    event,
    frequencyHertz: requireBoundedInteger(request['frequencyHertz'], 'frequency', 1, 100000, channel),
    periodEvents: requireBoundedInteger(request['periodEvents'], 'period', 1, 1000000000, channel),
    rateMode,
    durationSeconds: requireBoundedInteger(request['durationSeconds'], 'duration', 1, 3600, channel),
    callGraph,
    scope,
  };
}

function requireCommandToken(value: unknown, label: string, channel: string): string {
  if (typeof value !== 'string' || value.trim() !== value || value.length === 0 || /\s|\0/.test(value)) {
    throw new TypeError(`${channel}: ${label} must be a non-blank command token`);
  }
  return value;
}

function requireOptionalNonNegativeNumber(value: unknown, label: string, channel: string): number | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1_000_000_000) {
    throw new TypeError(`${channel}: ${label} must be a finite number from 0 to 1000000000`);
  }
  return value;
}

function requireBoundedInteger(value: unknown, label: string, minimum: number, maximum: number, channel: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < minimum || value > maximum) {
    throw new TypeError(`${channel}: ${label} must be an integer from ${String(minimum)} to ${String(maximum)}`);
  }
  return value;
}

/** Validates a serial/package target before any ADB-backed app capture starts. */
export function requireAndroidAppTarget(value: unknown, channel: string): FrameCaptureInput {
  const request = requireRecord(value, channel);
  return {
    serial: requireAdbSerial(request['serial'], channel),
    packageName: requireAndroidPackageName(request['packageName'], channel),
  };
}

/** Backward-compatible name for the frame-capture validation entry point. */
export function requireFrameCaptureInput(value: unknown, channel: string): FrameCaptureInput {
  return requireAndroidAppTarget(value, channel);
}
