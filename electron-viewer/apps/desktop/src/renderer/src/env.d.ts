/// <reference types="vite/client" />

import type { ApsApi } from '../../shared/app-info';

declare global {
  interface Window {
    readonly aps: ApsApi;
  }
}

export {};
