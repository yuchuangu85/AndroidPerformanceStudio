import { CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION } from '@aps/contracts';

export interface AppInfo {
  readonly name: string;
  readonly version: string;
  readonly contractVersion: number;
  readonly platform: string;
}

export const APPLICATION_NAME = 'Android Performance Studio';

/** The narrow API exposed to the renderer through the preload context bridge. */
export interface ApsApi {
  readonly getAppInfo: () => Promise<AppInfo>;
}

export function buildAppInfo(rawVersion: string, platform: string): AppInfo {
  const version = rawVersion.trim().length > 0 ? rawVersion : 'development';
  return {
    name: APPLICATION_NAME,
    version,
    contractVersion: CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION,
    platform,
  };
}
