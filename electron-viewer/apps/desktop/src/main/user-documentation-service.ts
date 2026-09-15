import { createReadStream, existsSync } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer, type Server } from 'node:http';
import { extname, join, normalize, relative, resolve } from 'node:path';
import type { UiLanguage } from '../shared/i18n.js';

const HOST = '127.0.0.1';
const DIRECTORY_BY_LANGUAGE: Record<UiLanguage, string> = { en: 'docs-user', zh: 'docs-user-zh' };

const CONTENT_TYPES: Record<string, string> = {
  '.css': 'text/css; charset=utf-8',
  '.gif': 'image/gif',
  '.html': 'text/html; charset=utf-8',
  '.ico': 'image/x-icon',
  '.jpeg': 'image/jpeg',
  '.jpg': 'image/jpeg',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
};

export interface UserDocumentationServiceOptions {
  readonly root: string;
  readonly openExternal: (url: string) => Promise<void>;
}

/**
 * Serves the bundled Docsify sites from loopback rather than file:// so that
 * their search index and browser fetches retain the same origin semantics as
 * the Compose reference implementation.
 */
export class UserDocumentationService {
  private readonly root: string;
  private readonly openExternal: (url: string) => Promise<void>;
  private server: Server | undefined;
  private origin: string | undefined;

  constructor({ root, openExternal }: UserDocumentationServiceOptions) {
    this.root = resolve(root);
    this.openExternal = openExternal;
  }

  async open(language: UiLanguage): Promise<void> {
    const directory = DIRECTORY_BY_LANGUAGE[language];
    if (!existsSync(join(this.root, directory, 'index.html'))) {
      throw new Error('Bundled user documentation is unavailable: ' + directory);
    }
    await this.ensureStarted();
    await this.openExternal((this.origin as string) + directory + '/');
  }

  async close(): Promise<void> {
    const server = this.server;
    this.server = undefined;
    this.origin = undefined;
    if (server === undefined) return;
    await new Promise<void>((resolveClose, rejectClose) => {
      server.close((error) => (error === undefined ? resolveClose() : rejectClose(error)));
    });
  }

  private async ensureStarted(): Promise<void> {
    if (this.origin !== undefined) return;
    const server = createServer((request, response) => {
      void this.serve(request.url ?? '/', response).catch(() => {
        if (!response.headersSent) response.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
        response.end('Internal server error');
      });
    });
    await new Promise<void>((resolveListen, rejectListen) => {
      server.once('error', rejectListen);
      server.listen(0, HOST, () => {
        server.off('error', rejectListen);
        resolveListen();
      });
    });
    const address = server.address();
    if (address === null || typeof address === 'string') {
      server.close();
      throw new Error('Bundled user documentation could not bind a loopback port');
    }
    this.server = server;
    this.origin = 'http://' + HOST + ':' + String(address.port) + '/';
  }

  private async serve(url: string, response: import('node:http').ServerResponse): Promise<void> {
    const pathname = decodeURIComponent(new URL(url, 'http://' + HOST).pathname);
    const requested = resolve(this.root, '.' + pathname);
    if (relative(this.root, requested).startsWith('..') || !requested.startsWith(this.root)) {
      response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      response.end('Not found');
      return;
    }
    const file = (await stat(requested).catch(() => undefined))?.isDirectory() ? join(requested, 'index.html') : requested;
    const fileInfo = await stat(file).catch(() => undefined);
    if (fileInfo === undefined || !fileInfo.isFile()) {
      response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      response.end('Not found');
      return;
    }
    response.writeHead(200, {
      'content-length': String(fileInfo.size),
      'content-type': CONTENT_TYPES[extname(file).toLowerCase()] ?? 'application/octet-stream',
    });
    createReadStream(normalize(file)).pipe(response);
  }
}
