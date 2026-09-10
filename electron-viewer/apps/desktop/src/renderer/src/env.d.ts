/// <reference types="vite/client" />

import type { ApsApi } from '../../shared/ipc';

declare global {
  interface Window {
    readonly aps: ApsApi;
  }
}

export {};
