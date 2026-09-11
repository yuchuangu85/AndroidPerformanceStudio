/**
 * Turns what the user asked for into a concrete pnpm version.
 *
 * `spec` may be a dist-tag (`latest`, `next-12`), an exact version (`11.20.0`,
 * with an optional leading `v`), or a bare major (`12`), which picks that
 * major's stable release and falls back to its prerelease lane.
 *
 * Dist-tags win over exact versions, matching `install.ps1`.
 *
 * @throws if `spec` matches neither a dist-tag nor a published version.
 */
export function resolveVersion(packument, spec) {
    const distTags = packument['dist-tags'];
    if (distTags[spec])
        return distTags[spec];
    const version = spec.startsWith('v') ? spec.slice(1) : spec;
    if (packument.versions[version])
        return version;
    if (/^\d+$/.test(version)) {
        const majorTag = distTags[`latest-${version}`] ?? distTags[`next-${version}`];
        if (majorTag)
            return majorTag;
    }
    throw new Error(`Sorry! pnpm version "${spec}" could not be found. Available tags: ${Object.keys(distTags).sort().join(', ')}`);
}
/** The major of an exact version, as the package layout depends on it. */
export function majorVersion(version) {
    const field = version.split('.')[0] ?? '';
    // Digits only: `Number` reads '' as 0 and '0x10' as 16, and a caller-supplied
    // version that means neither should say so here rather than fail later as a
    // package name nobody publishes.
    if (!/^\d+$/.test(field)) {
        throw new Error(`Could not read a major version from "${version}".`);
    }
    return Number(field);
}
