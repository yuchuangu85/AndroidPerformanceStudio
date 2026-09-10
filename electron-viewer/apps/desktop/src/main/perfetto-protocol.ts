import { readFile, stat } from 'node:fs/promises';
import { protocol } from 'electron';
import { contentTypeForPath, resolveAssetRequest } from '@aps/platform-perfetto';

export const PERFETTO_UI_SCHEME = 'aps-perfetto';
export const TRACE_SCHEME = 'aps-trace';

/**
 * Must run before app ready. The schemes are standard and secure so the bundled
 * Perfetto UI can fetch trace bytes from a same-origin-style custom protocol.
 */
export function registerPerfettoSchemes(): void {
  protocol.registerSchemesAsPrivileged([
    {
      scheme: PERFETTO_UI_SCHEME,
      privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true },
    },
    {
      scheme: TRACE_SCHEME,
      privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true },
    },
  ]);
}

export interface PerfettoProtocolOptions {
  readonly uiDirectory: string | undefined;
  readonly readTrace: (id: string) => Promise<Buffer | undefined>;
}

export function installPerfettoProtocolHandlers(options: PerfettoProtocolOptions): void {
  protocol.handle(PERFETTO_UI_SCHEME, async (request) => {
    if (options.uiDirectory === undefined) {
      return new Response('Perfetto UI assets are not available', { status: 503 });
    }
    const url = new URL(request.url);
    const resolved = resolveAssetRequest(options.uiDirectory, url.pathname);
    if (resolved === undefined) {
      return new Response('Not found', { status: 404 });
    }
    try {
      const info = await stat(resolved);
      if (!info.isFile()) return new Response('Not found', { status: 404 });
      const body = await readFile(resolved);
      return new Response(new Uint8Array(body), {
        status: 200,
        headers: { 'content-type': contentTypeForPath(resolved) },
      });
    } catch {
      return new Response('Not found', { status: 404 });
    }
  });

  protocol.handle(TRACE_SCHEME, async (request) => {
    const url = new URL(request.url);
    const id = url.pathname.replace(/^\/+/, '');
    const bytes = await options.readTrace(id);
    if (bytes === undefined) return new Response('Not found', { status: 404 });
    return new Response(new Uint8Array(bytes), {
      status: 200,
      headers: { 'content-type': 'application/octet-stream' },
    });
  });
}
