import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';
const ABBREVIATED_PACKUMENT = 'application/vnd.npm.install-v1+json';
export const DEFAULT_REGISTRY = 'https://registry.npmjs.org/';
/** The registry npx/npm is configured with, so a mirror stays a mirror. */
export function registryFromEnv() {
    return normalizeRegistry(process.env.npm_config_registry ?? process.env.NPM_CONFIG_REGISTRY ?? DEFAULT_REGISTRY);
}
/**
 * A registry URL that can be used as a base for a relative path.
 *
 * `new URL('pkg', 'https://mirror.example.com/npm')` drops the last segment,
 * so a registry served from a subpath needs its trailing slash to survive.
 */
export function normalizeRegistry(registry) {
    return registry.endsWith('/') ? registry : `${registry}/`;
}
// Node's fetch applies no timeout of its own: a registry that accepts the
// connection and then stalls would hang the install with no output. Metadata is
// small and quick; the tarball budget has to survive a slow connection carrying
// a ~150 MB download.
const METADATA_TIMEOUT_MS = 30_000;
const TARBALL_TIMEOUT_MS = 15 * 60_000;
export async function fetchPackument(registry, pkgName, headers) {
    return fetchJson(new URL(pkgName, registry), ABBREVIATED_PACKUMENT, headers);
}
export async function fetchVersionMeta(registry, pkgName, version, headers) {
    return fetchJson(new URL(`${pkgName}/${version}`, registry), 'application/json', headers);
}
/**
 * Streams `meta.dist.tarball` to `dest`, verifying the checksum the registry
 * published for it. A mismatch removes nothing — the caller discards the whole
 * temporary directory.
 *
 * `registry` re-hosts a tarball URL that points at npm onto that registry, so a
 * mirror that answered the metadata request serves the download too; `headers`
 * travel only to the registry's own origin, never to a download host it names.
 */
export async function downloadTarball(meta, dest, opts = {}) {
    const url = tarballUrl(meta, opts.registry);
    const response = await request(url, undefined, TARBALL_TIMEOUT_MS, headersFor(url, opts));
    const [algorithm, expected] = checksum(meta);
    const hash = createHash(algorithm);
    const body = response.body;
    await pipeline(async function* () {
        for await (const chunk of body) {
            hash.update(chunk);
            yield chunk;
        }
    }, createWriteStream(dest));
    const actual = hash.digest('base64');
    if (actual !== expected) {
        throw new Error(`The download from ${url.href} does not match the checksum the npm registry published for it. Refusing to install.`);
    }
}
/**
 * Where to download `meta`'s tarball from.
 *
 * Registries that proxy npm hand back npm's own URL. Following it would leave
 * the mirror the metadata came from — for an air-gapped one, it would not
 * resolve at all — so the path is re-hosted onto `registry`. Matched by origin,
 * so a host that merely starts with npm's is left alone.
 */
export function tarballUrl(meta, registry) {
    const url = new URL(meta.dist.tarball);
    if (registry == null || url.origin !== new URL(DEFAULT_REGISTRY).origin)
        return url;
    return new URL(`${url.pathname.replace(/^\//, '')}${url.search}`, normalizeRegistry(registry));
}
function headersFor(url, opts) {
    if (opts.headers == null || opts.registry == null)
        return undefined;
    return url.origin === new URL(opts.registry).origin ? opts.headers : undefined;
}
/**
 * The algorithm and digest to check a tarball against.
 *
 * `integrity` is an SRI string, which may hold several space-separated entries;
 * the first is used. Only the digest that the registry signature covers is
 * accepted — `shasum` is SHA-1, and a package without `integrity` has already
 * been refused before any download starts.
 */
function checksum(meta) {
    const entry = meta.dist.integrity?.trim().split(/\s+/)[0];
    if (!entry) {
        throw new Error(`The registry published no checksum for ${meta.dist.tarball}, so it cannot be verified.`);
    }
    const separator = entry.indexOf('-');
    if (separator === -1) {
        throw new Error(`The registry published an unreadable checksum for ${meta.dist.tarball}: ${entry}`);
    }
    return [entry.slice(0, separator), entry.slice(separator + 1)];
}
async function fetchJson(url, accept, headers) {
    const response = await request(url, accept, METADATA_TIMEOUT_MS, headers);
    return await response.json();
}
async function request(url, accept, timeoutMs, headers) {
    let response;
    try {
        response = await fetch(url, {
            signal: AbortSignal.timeout(timeoutMs),
            headers: { ...headers, ...(accept ? { accept } : {}) },
        });
    }
    catch (err) {
        const reason = err.name === 'TimeoutError'
            ? `timed out after ${Math.round(timeoutMs / 1000)}s`
            : err.message;
        throw new Error(`Could not reach ${url.href}: ${reason}`, { cause: err });
    }
    if (!response.ok) {
        throw new Error(`Could not download ${url.href}: ${response.status} ${response.statusText}`);
    }
    if (response.body == null) {
        throw new Error(`Empty response from ${url.href}`);
    }
    return response;
}
