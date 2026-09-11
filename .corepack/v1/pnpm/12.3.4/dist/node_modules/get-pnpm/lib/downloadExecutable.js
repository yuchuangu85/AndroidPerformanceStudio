import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { extractTarballMember } from './extractTarballMember.js';
import { isMusl, platformPackageName } from './platformPackageName.js';
import { downloadTarball, fetchVersionMeta, normalizeRegistry } from './registry.js';
import { majorVersion } from './resolveVersion.js';
import { sameFileContents } from './sameFileContents.js';
import { verifyRegistrySignature } from './verifySignature.js';
/** Name the executable is published under inside its platform package. */
function executableName() {
    return process.platform === 'win32' ? 'pnpm.exe' : 'pnpm';
}
/**
 * Places the pnpm executable for this host at `destPath`, and nothing else.
 *
 * The narrow half of {@link downloadPnpm}, for a caller that already knows the
 * exact version and already has whatever else it needs: no dist-tag lookup (so
 * no packument download), no `dist/` tree, no directory to assemble. Corepack
 * is the case it exists for — it unpacks the `pnpm` package itself but installs
 * none of its dependencies, so the executable has to arrive separately.
 *
 * The download is checked against the checksum the registry published for it,
 * and that checksum against npm's signature, exactly as {@link downloadPnpm}
 * does; nothing is written to `destPath` until both pass. See
 * {@link DownloadExecutableOptions.verifySignature} for the one waiver.
 *
 * Placement is atomic and tolerates losing a race: a concurrent call that got
 * there first keeps its copy, since both placed the same verified bytes.
 *
 * @returns the package the executable came from.
 */
export async function downloadPnpmExecutable(opts) {
    const { version, destPath } = opts;
    const registry = normalizeRegistry(opts.registry);
    const packageName = platformPackageName({
        major: majorVersion(version),
        platform: process.platform,
        arch: process.arch,
        musl: isMusl(),
    });
    const meta = await fetchVersionMeta(registry, packageName, version, opts.headers);
    if (!meta.dist.integrity) {
        throw new Error(`The npm registry published no checksum for ${packageName}@${version}, so it cannot be verified.`);
    }
    if (opts.verifySignature !== false) {
        verifyRegistrySignature({
            name: packageName,
            version,
            integrity: meta.dist.integrity,
            signatures: meta.dist.signatures,
            keys: opts.keys,
        });
    }
    const scratch = `${destPath}.${randomBytes(6).toString('hex')}`;
    const tarball = `${scratch}.tgz`;
    const staged = `${scratch}.tmp`;
    fs.mkdirSync(path.dirname(destPath), { recursive: true });
    try {
        await downloadTarball(meta, tarball, { registry, headers: opts.headers });
        const member = `package/${executableName()}`;
        if (!await extractTarballMember(tarball, member, staged, 0o755)) {
            throw new Error(`The ${packageName}@${version} tarball contains no ${member}.`);
        }
        place(staged, destPath);
    }
    finally {
        fs.rmSync(tarball, { force: true });
        fs.rmSync(staged, { force: true });
    }
    return { packageName };
}
/**
 * The codes Windows fails a rename with when the destination is held open —
 * which is what a concurrent call executing the copy it just placed looks like.
 * POSIX replaces the destination instead, so a failure there is a real one.
 */
const DESTINATION_IN_USE = new Set(['EPERM', 'EACCES', 'EBUSY']);
/**
 * Moves the verified executable into place, keeping the copy a concurrent call
 * placed if that is what stops the rename.
 *
 * A lost race is not assumed from the failure alone: the copy already there has
 * to hold the same bytes as the one just verified, or this call has no idea
 * what it would be reporting success for. Anything else — no permission on the
 * directory, a directory or an unrelated file at `destPath` — is a failure.
 */
function place(staged, destPath) {
    try {
        fs.renameSync(staged, destPath);
    }
    catch (err) {
        const code = err.code ?? '';
        if (!DESTINATION_IN_USE.has(code))
            throw err;
        if (!sameFileContents(staged, destPath))
            throw err;
    }
}
