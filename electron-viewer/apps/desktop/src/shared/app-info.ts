import { CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION } from '@aps/contracts';
import type { AppInfo } from './ipc.js';

export const APPLICATION_NAME = 'Android Performance Studio';

export function buildAppInfo(rawVersion: string, platform: string): AppInfo {
  const version = rawVersion.trim().length > 0 ? rawVersion : 'development';
  return {
    name: APPLICATION_NAME,
    version,
    contractVersion: CURRENT_CAPTURE_ARTIFACT_CONTRACT_VERSION,
    platform,
  };
}
