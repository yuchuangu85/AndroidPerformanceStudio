import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { extractTarball } from './extractTarball.js';
import { isMusl, platformPackageName } from './platformPackageName.js';
import { downloadTarball, fetchPackument, fetchVersionMeta, registryFromEnv } from './registry.js';
import { majorVersion, resolveVersion } from './resolveVersion.js';
import { verifyRegistrySignature } from './verifySignature.js';
export { downloadPnpmExecutable } from './downloadExecutable.js';
export { extractTarballMember } from './extractTarballMember.js';
export { isMusl, platformPackageName } from './platformPackageName.js';
export { DEFAULT_REGISTRY, registryFromEnv } from './registry.js';
export { majorVersion, resolveVersion } from './resolveVersion.js';
export { verifyRegistrySignature } from './verifySignature.js';
/**
 * The package whose dist-tags name every pnpm release. `@pnpm/exe` carries the
 * same versions today, but only `pnpm` is published from v12 onward, so its
 * tags are the ones that cannot go stale.
 */
const CLI_PKG_NAME = 'pnpm';
/** Holds the unpacked tarballs while the installation is assembled beside it. */
const UNPACK_DIR = '.unpack';
/** Where the `dist/` tree that ships beside the executable is published. */
function wrapperPackageName(major) {
    return major >= 12 ? CLI_PKG_NAME : '@pnpm/exe';
}
const USAGE = `Usage: npx get-pnpm [version]

Installs pnpm as a standalone executable and adds it to your PATH.

Arguments:
  version            An exact version (11.20.0), a major (12), or a dist-tag
                     (latest, next-12). Defaults to $PNPM_VERSION, then "latest".

Environment variables:
  PNPM_VERSION           Version to install when no argument is given.
  PNPM_HOME              Directory to install pnpm into.
  npm_config_registry    Registry to download pnpm from.
`;
export async function runCli(argv) {
    const positional = [];
    for (const arg of argv) {
        if (arg === '--help' || arg === '-h') {
            console.log(USAGE);
            return 0;
        }
        if (arg.startsWith('-')) {
            throw new Error(`Unknown option "${arg}".\n\n${USAGE}`);
        }
        positional.push(arg);
    }
    if (positional.length > 1) {
        throw new Error(`Expected at most one version, got ${positional.length}.\n\n${USAGE}`);
    }
    return installPnpm({
        versionSpec: positional[0] ?? process.env.PNPM_VERSION ?? 'latest',
        registry: registryFromEnv(),
    });
}
/**
 * Downloads the pnpm executable and hands over to `pnpm setup`, which installs
 * it globally and puts it on the PATH.
 *
 * Every download is checked against the checksum the registry published for it,
 * and that checksum against npm's signature — see `verifyRegistrySignature`.
 *
 * The temporary directory is assembled to look like the release tarball that
 * https://get.pnpm.io/install.sh downloads — the executable next to its `dist/`
 * tree — because `pnpm setup` installs that directory as-is.
 *
 * @returns the exit code of `pnpm setup`.
 */
export async function installPnpm(opts) {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pnpm-install-'));
    const removeTmpDir = () => {
        fs.rmSync(tmpDir, { recursive: true, force: true });
    };
    // Registered for the duration of the call and no longer: this is also a
    // library function, and handlers left behind would accumulate and outlive the
    // directory they exist to clean up.
    const signals = ['SIGINT', 'SIGTERM', 'SIGHUP'];
    const onSignal = () => {
        removeTmpDir();
        process.exit(1);
    };
    for (const signal of signals) {
        process.once(signal, onSignal);
    }
    try {
        const { binPath } = await downloadPnpm({ ...opts, dest: tmpDir });
        const { error, status } = spawnSync(binPath, ['setup', '--force'], { stdio: 'inherit' });
        if (error != null)
            throw error;
        return status ?? 1;
    }
    finally {
        for (const signal of signals) {
            process.off(signal, onSignal);
        }
        removeTmpDir();
    }
}
/**
 * Downloads the pnpm executable into `dest`, laid out the way the release
 * tarball lays it out — the executable next to the `dist/` tree it loads.
 *
 * Every download is checked against the checksum the registry published for it,
 * and that checksum against npm's signature; see `verifyRegistrySignature`.
 * Nothing outside `dest` is touched, so a caller that manages its own PATH — a
 * CI action, say — can use this without the global install `installPnpm` does.
 *
 * @returns the version installed and the path to the executable.
 */
export async function downloadPnpm(opts) {
    const packument = await fetchPackument(opts.registry, CLI_PKG_NAME);
    const version = resolveVersion(packument, opts.versionSpec);
    const major = majorVersion(version);
    const platformPkgName = platformPackageName({
        major,
        platform: process.platform,
        arch: process.arch,
        musl: isMusl(),
    });
    const { dest } = opts;
    fs.mkdirSync(dest, { recursive: true });
    try {
        console.log(`==> Downloading pnpm ${version}`);
        const executable = process.platform === 'win32' ? 'pnpm.exe' : 'pnpm';
        const fetchPackage = verifiedPackageFetcher({ dir: dest, registry: opts.registry, version, keys: opts.keys });
        // Settled, not `all`: a rejection there would leave the other fetch writing
        // into the directory the `finally` below is about to remove.
        const [platformResult, wrapperResult] = await Promise.allSettled([
            fetchPackage(platformPkgName),
            // v11 was the first release to keep files next to the executable; up to
            // v10 the executable is self-contained and ships no `dist/`.
            major >= 11 ? fetchPackage(wrapperPackageName(major)) : Promise.resolve(undefined),
        ]);
        if (platformResult.status === 'rejected')
            throw platformResult.reason;
        if (wrapperResult.status === 'rejected')
            throw wrapperResult.reason;
        const platformPkg = platformResult.value;
        const wrapperPkg = wrapperResult.value;
        const binPath = path.join(dest, executable);
        fs.renameSync(path.join(platformPkg.dir, executable), binPath);
        fs.chmodSync(binPath, 0o755);
        if (wrapperPkg != null) {
            fs.rmSync(path.join(dest, 'dist'), { recursive: true, force: true });
            fs.renameSync(path.join(wrapperPkg.dir, 'dist'), path.join(dest, 'dist'));
            writeManifest({ dest, executable, version, wrapperDir: wrapperPkg.dir, major });
        }
        return { version, binPath };
    }
    finally {
        // `pnpm setup` installs this directory as a package, so nothing may be left
        // in it that does not belong in the installation.
        fs.rmSync(path.join(dest, UNPACK_DIR), { recursive: true, force: true });
    }
}
/**
 * `pnpm setup` installs the directory as a package, writing a minimal manifest
 * when there is none — which is what the release tarball relies on. That
 * tarball bundles the runtime dependencies inside `dist/`; the registry copy
 * declares them instead, so up to v11 they have to be declared here or the
 * install silently loses them (`@reflink/reflink`, and with it copy-on-write
 * cloning). From v12 the `dist/` tree is self-contained again, so the manifest
 * `setup` writes is left to it.
 */
function writeManifest(opts) {
    if (opts.major >= 12)
        return;
    const wrapper = JSON.parse(fs.readFileSync(path.join(opts.wrapperDir, 'package.json'), 'utf8'));
    if (wrapper.dependencies == null)
        return;
    fs.writeFileSync(path.join(opts.dest, 'package.json'), JSON.stringify({
        name: '@pnpm/exe',
        version: opts.version,
        type: 'module',
        bin: { pnpm: opts.executable, pn: opts.executable },
        dependencies: wrapper.dependencies,
    }));
}
/** Downloads packages of one version into one directory, verifying each. */
function verifiedPackageFetcher(opts) {
    return async function fetchPackage(pkgName) {
        const meta = await fetchVersionMeta(opts.registry, pkgName, opts.version);
        if (!meta.dist.integrity) {
            throw new Error(`The npm registry published no checksum for ${pkgName}@${opts.version}, so it cannot be verified.`);
        }
        verifyRegistrySignature({
            name: pkgName,
            version: opts.version,
            integrity: meta.dist.integrity,
            signatures: meta.dist.signatures,
            keys: opts.keys,
        });
        // Unpack away from the directory being assembled: `pnpm` is both a package
        // name and the name of the executable that ends up beside it.
        const unpackDir = path.join(opts.dir, UNPACK_DIR, pkgName.replaceAll('/', '-'));
        const tarball = `${unpackDir}.tgz`;
        fs.mkdirSync(path.dirname(tarball), { recursive: true });
        await downloadTarball(meta, tarball, { registry: opts.registry });
        extractTarball(tarball, unpackDir);
        fs.rmSync(tarball);
        return { dir: path.join(unpackDir, 'package') };
    };
}
