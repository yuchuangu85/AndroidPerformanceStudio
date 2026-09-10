export type GpuArtifactKind =
  | 'AGI_SYSTEM_PROFILE'
  | 'AGI_FRAME_PROFILE'
  | 'PERFETTO_TRACE'
  | 'SCREENSHOT'
  | 'EXTERNAL_REPORT'
  | 'UNKNOWN';

export type GraphicsApi = 'VULKAN' | 'OPENGL_ES' | 'WEBGPU' | 'UNKNOWN';
export type ArtifactOpenRoute = 'AGI' | 'PERFETTO' | 'DESKTOP' | 'NONE';
export type ArtifactLocationStatus = 'AVAILABLE' | 'MISSING' | 'SIZE_CHANGED';
export type AgiLaunchMode = 'VERIFIED_CLI' | 'GUI_ONLY' | 'UNSUPPORTED';

export interface AgiCapability {
  readonly executable?: string;
  readonly version?: string;
  readonly launchSupported: boolean;
  readonly artifactOpenSupported: boolean;
  readonly launchMode: AgiLaunchMode;
  readonly supportedArguments: readonly string[];
  readonly warnings: readonly string[];
}

export interface GpuDeviceContext {
  readonly serial?: string;
  readonly model?: string;
  readonly apiLevel?: number;
  readonly gpuVendor?: string;
  readonly gpuRenderer?: string;
  readonly driverVersion?: string;
  readonly evidenceSources: Readonly<Record<string, string>>;
}

export interface GraphicsImplementationContext {
  readonly name?: string;
  readonly version?: string;
  readonly backendApi?: GraphicsApi;
  readonly evidenceSource?: string;
}

export interface GpuCaptureContext {
  readonly id: string;
  readonly device?: GpuDeviceContext;
  readonly packageName?: string;
  readonly graphicsApi: GraphicsApi;
  readonly graphicsImplementation?: GraphicsImplementationContext;
  readonly frameCapture: boolean;
  readonly warnings: readonly string[];
}

export interface GpuArtifact {
  readonly id: string;
  readonly kind: GpuArtifactKind;
  readonly path: string;
  readonly sha256: string;
  readonly sizeBytes: number;
  readonly openRoute: ArtifactOpenRoute;
  readonly alternativePaths: readonly string[];
  readonly warnings: readonly string[];
  readonly agiVersion?: string;
  readonly device?: GpuDeviceContext;
  readonly packageName?: string;
  readonly graphicsApi?: GraphicsApi;
  readonly graphicsImplementation?: GraphicsImplementationContext;
  readonly capturedAtEpochMillis?: number;
  readonly importedAtEpochMillis: number;
  readonly notes?: string;
}

/** GPU artifact content is identified by digest; locations are just pointers. */
export function artifactLocations(artifact: GpuArtifact): string[] {
  return [...new Set([artifact.path, ...artifact.alternativePaths])];
}

export function openRouteFor(kind: GpuArtifactKind): ArtifactOpenRoute {
  if (kind === 'PERFETTO_TRACE') return 'PERFETTO';
  if (kind === 'AGI_FRAME_PROFILE' || kind === 'AGI_SYSTEM_PROFILE') return 'AGI';
  if (kind === 'SCREENSHOT' || kind === 'EXTERNAL_REPORT') return 'DESKTOP';
  return 'NONE';
}

/** Evidence sources are labelled so a decoded renderer is never assumed. */
export function graphicsApiFor(context: GpuCaptureContext | undefined): GraphicsApi {
  return context?.graphicsApi ?? 'UNKNOWN';
}
