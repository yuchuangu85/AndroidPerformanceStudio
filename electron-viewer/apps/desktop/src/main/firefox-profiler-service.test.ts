import { gzipSync } from 'node:zlib';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { describe, expect, it, afterEach } from 'vitest';
import {
  FirefoxProfilerService,
  firefoxProfilerSiteDirectory,
  geckoProfileForCpuSession,
  officialFirefoxProfilerUrl,
} from './firefox-profiler-service.js';
import type { CpuProfileSessionRecord } from '@aps/simpleperf-profiler';

const services: FirefoxProfilerService[] = [];
afterEach(async () => { await Promise.all(services.splice(0).map((service) => service.close())); });

function record(sourceFormat?: CpuProfileSessionRecord['sourceFormat']): CpuProfileSessionRecord {
  return {
    id: 'cpu-1', capturedAtEpochMillis: 1, serial: 'serial', reportFile: 'report.pb',
    ...(sourceFormat === undefined ? {} : { sourceFormat }),
    perfDataBytes: 1, sampleCount: 1, lostCount: 0, eventTypes: [], threadKeys: [],
    metadata: { traceOffCpu: false },
    parameters: { target: 'system wide', event: 'cpu-clock', rate: '100 Hz', callGraph: 'DWARF', scope: 'BOTH' },
  };
}

describe('FirefoxProfilerService', () => {
  it('retains imported Gecko gzip bytes exactly', async () => {
    const bytes = gzipSync('{"threads":[]}');
    const result = await geckoProfileForCpuSession({ readReport: async () => bytes }, record('GECKO_PROFILE_JSON_GZIP'));
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect([...result.value]).toEqual([...bytes]);
  });

  it('serves local app assets and a no-store profile from the same loopback origin', async () => {
    const site = await mkdtemp(join(tmpdir(), 'aps-firefox-site-'));
    await mkdir(join(site, 'assets'));
    await writeFile(join(site, 'index.html'), '<!doctype html><script src="/assets/app.js"></script>');
    await writeFile(join(site, 'assets', 'app.js'), 'console.log("ok")');
    const opened: string[] = [];
    const service = new FirefoxProfilerService({ localSiteDirectory: site, openExternal: async (url) => { opened.push(url); } });
    services.push(service);
    await service.openLocal(new Uint8Array([1, 2, 3]));
    const openedUrl = new URL(opened[0] as string);
    const profileUrl = decodeURIComponent(openedUrl.pathname.split('/')[2] as string);
    const index = await fetch(openedUrl.origin + '/from-url/anything/flame-graph/');
    expect(index.status).toBe(200);
    expect(index.headers.get('content-type')).toContain('text/html');
    expect(await index.text()).toContain('app.js');
    const asset = await fetch(openedUrl.origin + '/assets/app.js');
    expect(await asset.text()).toContain('console.log');
    const profile = await fetch(profileUrl);
    expect(profile.headers.get('cache-control')).toBe('no-store');
    expect(profile.headers.get('content-type')).toContain('application/gzip');
    expect(new Uint8Array(await profile.arrayBuffer())).toEqual(new Uint8Array([1, 2, 3]));
    expect((await fetch(openedUrl.origin + '/%2e%2e/secret')).status).toBe(404);
  });

  it('hands official Firefox a CORS/private-network enabled one-shot transfer URL', async () => {
    const opened: string[] = [];
    const service = new FirefoxProfilerService({ openExternal: async (url) => { opened.push(url); }, transferLifetimeMillis: 1_000 });
    services.push(service);
    await service.openOfficial(new Uint8Array([4, 5]));
    expect(opened[0]).toMatch(/^https:\/\/profiler\.firefox\.com\/from-url\//);
    const profileUrl = decodeURIComponent((opened[0] as string).split('/from-url/')[1]?.split('/flame-graph/')[0] as string);
    const preflight = await fetch(profileUrl, { method: 'OPTIONS' });
    expect(preflight.status).toBe(204);
    expect(preflight.headers.get('access-control-allow-private-network')).toBe('true');
    const profile = await fetch(profileUrl);
    expect(profile.status).toBe(200);
    expect(new Uint8Array(await profile.arrayBuffer())).toEqual(new Uint8Array([4, 5]));
  });

  it('locates only a complete Firefox Profiler site and encodes official URLs', async () => {
    const root = await mkdtemp(join(tmpdir(), 'aps-firefox-root-'));
    expect(firefoxProfilerSiteDirectory({ repositoryRoot: root })).toBeUndefined();
    const site = join(root, 'third_party', 'firefox-profiler', 'dist');
    await mkdir(site, { recursive: true });
    await writeFile(join(site, 'index.html'), 'ok');
    expect(firefoxProfilerSiteDirectory({ repositoryRoot: root })).toBe(site);
    expect(officialFirefoxProfilerUrl('http://127.0.0.1:1234/perf_data.json.gz')).toContain('%3A%2F%2F');
  });
});
