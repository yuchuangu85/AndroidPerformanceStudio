# AI Platform Release Qualification

Evidence date: **2026-10-31**. This is a gate implementation, not a release certificate.
An unsigned `createDistributable`, local fixture pass, mocked provider, skipped test, or
historical hosted run never establishes release qualification for a new source revision.

## Evidence and Publication Boundary

- `ai-qualification.yml` is nonpublishing: read-only token, native macOS/Linux/Windows
  deterministic checks, and mandatory strict Linux production-sandbox qualification.
  PRs receive no provider or signing credentials. No raw provider output/XML is uploaded.
- Real-provider acceptance requires explicit `run_real_provider=true`, a dedicated
  `aps-ai-provider-acceptance` environment, and dedicated credential references. This can
  incur charges. Disabling the input means **not qualified**, not a successful live test.
- macOS/Windows native sandbox inputs default to false because those production backends
  currently report unavailable. Enable only with a real implemented native backend;
  the requested gate then fails on missing implementation, unavailable, zero tests or skips.
  A green deterministic Windows/macOS job is not a native sandbox qualification.
- `release.yml` retains the pre-existing dirty AI composite check. Publication now requires
  manual `release_qualified=true` and `run_real_provider=true`. A tag push alone fails before
  creating a draft. Qualification gates the test job; tests gate packaging and candidate
  receipts; the final publisher alone creates a draft and uploads verified candidates. Existing tags must match the dispatch
  SHA, so a successful run against an older tag cannot certify newly dispatched code.
- Linux DEB/RPM remain unsigned installer artifacts. They depend on strict Linux sandbox and
  the same-source AI matrix, not a fabricated Linux signing certificate. Windows gates below
  qualify outer MSI/EXE Authenticode, not every embedded DLL or installed-app behavior.

The reusable workflow checks the caller's exact source. Gate scripts rerun selected tests
with no build cache/configuration cache and require fresh exact-suite JUnit XML, nonzero
testcases, zero failures/errors/skips, and no skipped/failing testcase elements. Missing
suites fail before Gradle. Plain fixture suites cannot substitute for these contracts:

- `desktop-app`: `com.androidperformancestudio.desktop.AiReleaseQualificationTest.nativeSandboxQualification`
- `ai-platform-app`: `com.androidperformancestudio.aiplatform.app.LiveProviderAcceptanceTest.liveProviderAcceptance`

The native method is implemented in this slice's owned desktop test. Only the strict gate
sets `APS_REQUIRE_NATIVE_SANDBOX=true` (and Linux `APS_REQUIRE_LINUX_SANDBOX=true`): it
executes Python and Node through the production runtime, asserts discovery, forbidden host
reads/writes, socket/child denial, environment clearing, permission rejection, output/memory/CPU/wall
limits and scratch cleanup. The hosted job compiles the production C helper with the runner's
existing compiler and installs it at the runtime's required `/usr/libexec/aps/aps-script-sandbox`;
it does not disable AppArmor/user-namespace policy or substitute a fake helper. The exact
method is filtered, so default no-op native test invocations cannot qualify a platform.
The live method was discovered in the current dirty tree and is owned by the app lane, not
this slice. The workflow runs the shared `scripts/ai-provider-acceptance.sh --check` preflight
and the gate selects **only** its live method, not its offline fixture methods. Dispatch inputs
`live_provider` and `live_model` supply explicit `APS_LIVE_PROVIDER` / `APS_LIVE_MODEL`;
the requested job sets `APS_RUN_LIVE_PROVIDER=true` and requires the selected environment key.
It calls the production gateway/explicit trusted Claude runtime with canonical
synthetic evidence, asserts a real structured result/reference/model, and fails on absent
credentials, unsupported capability, cancellation or service errors.
No prompt, response, authorization header or credential may be printed or put in test names.
The environment contract below is intentionally distinct from personal provider profiles.
`openai` and **Codex (Responses API)** use `OPENAI_API_KEY`, never Codex CLI login or
cached authentication. `claude` requires `ANTHROPIC_API_KEY` and an already-installed trusted
Claude executable supported by the production adapter; no new CLI dependency is downloaded.
Missing Claude runtime fails, not skips. Codex CLI remains unavailable for this no-tools
contract and is never a fallback. This does not certify OAuth/login or token/HTTP metrics
not exposed by the Claude driver; its transport-attempt count remains null.

The shared harness owns selection/preflight semantics; the qualification wrapper additionally
requires fresh zero-skip XML and an allowlisted success receipt proving one broker invocation,
one started event, one result and (API paths) one observed transport attempt. It uses a native
temporary receipt filename on each OS, deletes it after validation, withholds raw Gradle output,
removes JVM debug flags, and never retries. Each opted-in native matrix host makes at most
one synthetic call, so requesting all three hosts authorizes up to three calls, not one total.
The real-provider job depends only on the native unit matrix, not on Linux script sandbox
qualification. An unrelated unavailable Linux Node sandbox must not prevent collecting
separately opted-in provider evidence. The overall result and release still require strict
Linux sandbox evidence; unrequested or unavailable sandboxes never become qualified.

## Credential References

All names below refer to environment variables populated by GitHub secret/variable
references, never checked-in values. Do not copy credentials into manifests, argv-building
shell snippets, logs, receipts or artifacts. Native Apple tools require password arguments;
the Python wrapper passes those directly to the tool without a shell and withholds all
native stdout/stderr/exception argv. Use ephemeral dedicated hosted runners, not shared hosts
where another process can inspect argv. Never enable shell tracing or verbose secret dumps.

`aps-ai-provider-acceptance`:

- Secret `OPENAI_API_KEY` for `openai` or `codex`; secret `ANTHROPIC_API_KEY` for `claude`.
  Only the selected provider's secret is injected. `live_model` is an explicit dispatch/caller
  input, not a guessed/default model or secret. Codex additionally requires a Codex-named model.
- `APS_RUN_LIVE_PROVIDER=true` is set only in the explicitly requested live gate. API tests
  fix the real Responses endpoint; no configurable fixture/loopback endpoint is accepted.
  Unrequested gates never produce a qualification receipt. Desktop app keychain references
  are not CI environment credentials and are never read or reused by this workflow.

`aps-release-signing`, macOS secrets:

- `APS_MACOS_CERTIFICATE_BASE64`: base64 PKCS#12 containing Developer ID Application and
  Developer ID Installer identities/private keys for the same team.
- `APS_MACOS_CERTIFICATE_PASSWORD`: PKCS#12 password.
- `APS_MACOS_SIGNING_IDENTITY`: complete `Developer ID Application: ... (TEAMID)` name.
- `APS_MACOS_INSTALLER_IDENTITY`: complete `Developer ID Installer: ... (TEAMID)` name.
- `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_SPECIFIC_PASSWORD`: notarization account, ten-character
  team ID, and app-specific password. A normal Apple account password is not accepted here.

`aps-release-signing`, Windows secrets/variable:

- Secrets `APS_WINDOWS_CERTIFICATE_BASE64`, `APS_WINDOWS_CERTIFICATE_PASSWORD`,
  `APS_WINDOWS_CERTIFICATE_THUMBPRINT` (40 hex characters).
- A **leaf-only** PFX with a valid code-signing private key; no chain certificates are imported.
  The script refuses an existing matching user-store identity and deletes only its temporary
  certificate/private key in `finally`. No exportable private key or persistent PFX is created.
- Variable `APS_WINDOWS_TIMESTAMP_URL`: approved HTTPS RFC3161 service without credentials.
  SignTool must already exist in the native Windows SDK. No package/download dependency is
  added. Hardware-only/EV/cloud signing services are not implemented by this PFX gate.

Provision these environments with required reviewers, restricted trusted release refs,
separate credentials/billing limits and allowed runner policies **before** enabling release.
Merely naming an environment in YAML does not establish its protection configuration.
Keep fork/PR revisions out of credential-bearing runs. Secret metadata availability is not
proof the certificate/password/account works; only a real gate can supply that evidence.

## Signing and Notarization

`sign.py preflight <platform>` is read-only and fails on missing references or lack of
`APS_ENABLE_RELEASE_SIGNING=true`. It does not claim the references are valid credentials.
`sign.py sign` is an explicitly requested side effect, never part of default fixture tests.
`sign.py verify` checks the same source/version/architecture and exact final artifact hashes;
the receipt is local gate evidence, not an independently trusted digital attestation.

macOS flow:

1. Copy the unsigned development `.app` into a private temporary directory; never sign or
   overwrite the development app. Check the app's actual bundle version and native architecture,
   then snapshot exact user keychain search/default state.
2. Create/unlock a randomly passworded temporary keychain, import certificates with limited
   trusted tools, configure key partitions, then delete the temporary PKCS#12 immediately.
3. Validate the unsigned HPROF sidecar against its original `.sha256` and every packaged
   Trace Processor against the unchanged repository pin **before any signing**. A mismatch
   fails before keychain creation. Inspect JAR paths, duplicates, symlinks, CRCs and bounded
   sizes before signing; no unverified bytes can be adopted as new provenance.
4. Require the expected Developer ID identity. Use the existing Compose signing pattern:
   extract each Mach-O `.dylib`/`.jnilib` from JARs (including both sqlite-jdbc Mac variants),
   sign and verify it with the same Developer ID, and atomically repack under the same member
   names. Preserve all non-native members, archive metadata and classpath filename. Signed
   JARs with cryptographic signature metadata fail instead of silently stripping signatures.
   No new dependency and no disabled library validation is introduced.
   The reference is Compose 1.12.0's `MacSigningHelper` and
   `MacJarSignFileCopyingProcessor`, inspected from the existing local source JAR cache.
5. Sign loose Mach-Os, verify each, then refresh resource hashes only for the verified signed
   files. HPROF's `.sha256` becomes the post-sign hash; its original verified digest is retained
   in `aps-hprof-engine.upstream.sha256`. Write the exact Trace Processor metadata below,
   preserving the classpath/repository pins. Refresh ADB inventories using the existing
   verified-before/sign/refresh contract. All inventories are final before enclosing seals.
6. Sign metadata-discovered bundles inside-out, including the extensionless
   `Contents/runtime` whose `Contents/Info.plist` declares `CFBundlePackageType=BNDL`.
   Verify the runtime/nested seals, ensure inventoried tool bytes did not change, and only
   then seal the outer app. Hardened runtime/timestamps remain mandatory. JVM entitlements
   permit JIT/unsigned executable memory only, not debugging or disabled library validation;
   embedded JAR libraries receive no executable JIT entitlements. Deep strict verification
   fails closed; there is no ad-hoc fallback.
7. Store validated notary credentials only in this temporary keychain. ZIP the signed app,
   `notarytool submit --wait`, require JSON `Accepted` plus submission ID, staple/validate
   the app, and Gatekeeper-assess it.
8. Build final DMG from the signed/stapled app; sign DMG with Developer ID Application and
   build PKG with Developer ID Installer. Verify signatures, submit/wait for each, require
   `Accepted`, staple/validate each and assess Gatekeeper trust before computing final hashes.
   Original Compose DMG/PKG remain unsigned development build outputs and are not uploaded.
9. Restore default and exact search-list keychains and delete the temporary keychain in
   `finally`, including import, signing, notarization or SIGTERM failure. Any failed restoration
   rejects qualification, even when signing passed. SIGKILL/host death cannot run cleanup;
   use disposable hosted runners and do not retry partial output directories/receipts.

### Trace Processor Transform Contract

Packaged Mac only, relative to `compose.application.resources.dir`:
`perfetto-tools/trace-processor-signature.json`, with **exactly** these keys:

```json
{
  "schemaVersion": 1,
  "upstreamVersion": "v59.1",
  "binary": "trace_processor_shell",
  "upstreamSha256": "<original pinned SHA-256 for this Mac architecture>",
  "signedSha256": "<verified post-sign binary SHA-256>",
  "teamId": "<10 uppercase alphanumeric Apple team ID>"
}
```

`upstreamVersion` comes from the immutable pinned manifest and must equal
`TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION`; the version above illustrates the current
pin, not a new independent authority. Metadata is created only after original bytes match
that pin and signed bytes verify, before runtime/enclosing app seals. The separately owned
resolver retains the pinned classpath hash, checks original/version/binary/actual signed hash,
and requires `codesign --verify --deep --strict -R 'anchor apple generic and certificate
leaf[subject.OU] = "TEAMID"'` on the enclosing `.app`. An unsigned on-disk inventory is not
trusted by itself. Installed-tool and non-Mac verification remain unchanged. This signing
slice does not edit the resolver; its matching implementation must be integrated in the
same released source. Signing-script fixtures are not evidence of native resolver acceptance.

## Publication Transaction

The entire workflow is serialized by canonical version, not branch/ref: `1.2.3` and
`v1.2.3` share one lock; leading-zero aliases are rejected. Tag-only publication remains
deliberately disabled. A prepackaging check is read-only and rejects any existing Release;
there is no reset, draft adoption, automatic cleanup, asset overwrite or `--clobber` path.

Each of five packaging jobs records its exact source SHA, version, platform/arch, run ID,
run attempt, signing status, asset sizes and SHA-256 hashes in `receipt.json`, alongside its
two installers in `assets/`. macOS/Windows candidates require matching signing receipts;
Linux candidates explicitly retain `unsigned-linux-artifact` status. Actions artifacts are
named `release-candidate-PLATFORM-ARCH-RUN-ATTEMPT`, with no overwrite. The final job downloads
only the same run/attempt's five candidates using digest-mismatch-as-error, validates all
receipts against the immutable checkout and checks every local byte before any mutation.

Only the final publication job has repository write permission. It resolves lightweight
or annotated tags to the qualified commit, creates a missing tag at that exact commit,
freezes its ref identity, and creates one fresh draft. It uploads ten verified files without
replacement, downloads every uploaded asset **by asset ID**, and independently compares
actual bytes/size with the per-job receipts. It then rechecks tag commit/ref, release ID,
draft state and the complete asset ID set before publishing; final metadata/tag/IDs are
checked again afterward. An upload/hash/tag race fails; no automatic retry or partial-draft
recovery is attempted. Existing or partial releases require a separately authorized human
decision, never implicit reuse by another source revision.

GitHub tag refs and release metadata are separate API resources, not an atomic multi-object
transaction. Version serialization prevents this workflow's competing runs from interleaving;
production governance must also protect release tags from mutation/deletion by other actors
and restrict release writers. The checks detect observed external changes before/after
publication; they cannot promise immutability against an independently privileged writer.

Windows flow signs both final installers using SHA256 and RFC3161 SHA256 timestamping,
requires SignTool `/pa /all` verification, valid Authenticode, exact certificate thumbprint
and a timestamp certificate. Missing SDK, expired/untrusted identity, timestamp failure or
certificate cleanup failure blocks upload. No skipped signing is treated as success.

Installed-package clean/upgrade/rollback acceptance, Linux helper installation by DEB/RPM,
and packaged provider discovery remain separate application/packaging-owner evidence. A
hosted runtime test with a helper installed on the runner cannot prove an installer ships it.

## Local Deterministic Verification

```sh
python3 -B scripts/ai-release/test_contracts.py
actionlint .github/workflows/ai-qualification.yml .github/workflows/release.yml
git diff --check
```

Fixtures mock native tool execution and use isolated temporary fake reports/certificates.
They cover missing/invalid secret references and redaction, opted-out real services, absent
native suites, stale/empty/skipped/failed JUnit, forced reruns, keychain restoration on failure,
notary Accepted/staple/validate ordering, Windows signing contracts and receipt tampering.
On native Windows only, the fixture suite also parses PowerShell AST without executing it.
Fixtures also run on macOS's system Python 3.9. Hashing uses bounded streaming rather than
Python 3.11-only `hashlib.file_digest`; the desktop subprocess regression is covered directly.
Runtime BNDL ordering, HPROF/Trace Processor provenance, JAR-native transformations and final
publication's mixed-source/uploaded-byte/tag-race cases are included in `test_contracts.py`.
`AiReleaseQualificationTest` runs these deterministic fixtures and static workflow contracts;
it never signs, imports keys, calls a provider or launches Gradle itself. Kotlin/Gradle
verification belongs to the main serial verification lane and was not run by this slice.

## Observed Blockers on 2026-10-31

- Read-only `gh` inspection of `yuchuangu85/AndroidPerformanceStudio`: Actions enabled,
  only `Release native installers` active; no repository Actions secret names returned and
  no GitHub environments configured. No secret values fetched. This does not enumerate
  inaccessible organization secrets or prove credentials are usable.
- Latest observed successful run: `40911922870`, source
  `ba0fa4aa0adec6eb7ff8c577c820057968ff9973`, created `2026-10-29T16:28:30Z`.
  It predates these uncommitted files and supplies **no evidence for this work**.
- Local codesigning reports `0 valid identities found`. Apple account/team/app-specific
  password and signing-key environment references are absent. All macOS and Windows
  secret references listed above, the live API key/model and timestamp variable,
  protected environments and actual native/live hosted runs remain
  release dependencies. Windows tool/certificate behavior and macOS app compatibility have
  not been exercised with real identities.
- No commit, push, workflow dispatch, tag, key import, real-provider call, notarization
  submission or publication was performed. These local dirty changes must first be reviewed
  and deliberately committed/pushed together with the owning runtime acceptance suites.
  The new workflow must exist on the remote default branch before manual dispatch becomes
  available. A later authorized run must qualify that exact published source SHA, then pass
  real signing/notarization/installer gates. Current unsigned development artifacts remain
  usable for development only, not release-qualified.
- Main's existing `ReleaseWorkflowTest` still has obsolete expectations for direct per-job
  release uploads, `--clobber`, old release shell snippets and fixed artifact-action counts.
  Those assertions are outside this slice's allowed test ownership and must be updated by
  main to the single-publisher/candidate-receipt contract before serial Gradle qualification.
- The main verification lane's separately authorized small configured-provider calls are not
  performed by this slice and cannot be reported here as hosted/signing evidence. No local
  app keychain secret, credential file or raw response was read/saved while aligning this workflow.

## Primary References

- Apple: [Notarizing macOS software before distribution](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)
- Apple: [Customizing the notarization workflow](https://developer.apple.com/documentation/security/customizing-the-notarization-workflow)
- GitHub: [Installing an Apple certificate on macOS runners](https://docs.github.com/en/actions/how-tos/deploy/deploy-to-third-party-platforms/sign-xcode-applications)
- Microsoft: [SignTool](https://learn.microsoft.com/en-us/windows/win32/seccrypto/signtool)
- Microsoft: [Import-PfxCertificate](https://learn.microsoft.com/en-us/powershell/module/pki/import-pfxcertificate)
