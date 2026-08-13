# Package upstream Winscope as an optional browser viewer

Status: Accepted (2026-08-13)

## Context

Android Performance Studio already provides the native Compose **Winscope Workspace** described by [ADR 0032](0032-build-winscope-as-a-native-compose-workspace.md). The upstream AOSP Winscope web application still provides a useful compatibility and advanced-inspection path, but embedding it would add a second UI runtime and a platform-specific browser dependency.

The inspected `winscope/dist/prod` directory is about 389 MiB because its offline preparation retains multiple historical hashed bundle sets for stale browser caches. A clean production build initially produced an approximately 114 MiB current-entry closure; removing production-only coverage instrumentation and source maps reduces the packaged closure to 45.2 MiB without changing runtime behavior. The inspected checkout is at AOSP commit `f41a8085fa0166967dd5ece55dce0796fd079e93`, but its generated `index.html` and source tree contain uncommitted changes and are not a reproducible distribution input.

## Decision

- Keep the native Compose Winscope Workspace as the primary experience. Add **Open in Upstream Winscope** to an eligible inspection session's toolbar; do not add a separate home-page workspace and do not embed a browser in the application window.
- Launch the packaged web viewer in the system browser through an application-owned `jdk.httpserver` loopback server, following the existing Perfetto UI server pattern. The application chooses and manages the port, so users do not install or run `http-server`. A minimal maintained upstream patch automatically loads the current Winscope Evidence Package from a same-origin, tokenized endpoint; the user is not asked to select the same files again.
- The upstream viewer is view-only. Android Performance Studio remains the sole owner of ADB connection and capture. The packaged build removes the collection UI and its automatic proxy/WDP connection path; `winscope_proxy.py` is not packaged or started, and the viewer does not probe ports 5544 or 9167.
- Enable the entry only after parsing confirms at least one WindowManager or SurfaceFlinger core source contains evidence. A recording, non-empty file, registered producer, requested source, or sensitive-evidence flag is not sufficient. A video-only session keeps the entry disabled and explains that no Winscope core evidence was captured.
- Before the first external-browser open of a sensitive session, require explicit confirmation that the evidence will cross into the browser process. Bind the server only to loopback; use an unguessable, short-lived session token; allow only declared resources; disable directory listing and caching of evidence; and invalidate the endpoint when the session or application closes.
- Ship an offline build with all runtime resources local. Remove analytics and other telemetry, remote fonts, remote icons, and any runtime dependency on third-party network services.
- Build from a clean, pinned AOSP commit plus a small, reviewable patch series for offline operation and automatic evidence loading. The initial baseline is `f41a8085fa0166967dd5ece55dce0796fd079e93`; unrelated working-tree modifications are excluded.
- Synchronize only the resource closure referenced by the generated entry point, record file checksums and the source commit, and package that closure in the macOS, Windows, and Linux distributions. Do not copy the whole 389 MiB `dist/prod`, and do not run the AOSP npm build from ordinary Gradle builds.
- Upgrade the pinned commit manually. An upgrade must reapply the patch series, regenerate the resource manifest and checksums, refresh required license/notice material, and pass compatibility tests before it is accepted.

## Rejected alternatives

- **Replace the Compose workspace:** loses the integrated native workflow and contradicts ADR 0032.
- **Embed JCEF or another web runtime:** adds a large cross-platform native dependency and duplicate lifecycle, security, accessibility, and rendering concerns for no required in-window behavior.
- **Open an empty viewer and require manual file selection:** avoids a small upstream patch but makes a session-scoped action repeat work and obscures which evidence is being inspected.
- **Copy all of `dist/prod`:** packages obsolete hashed bundles and proxy code that the product does not use.
- **Build upstream Winscope during every Gradle build:** couples normal desktop builds to AOSP, Node, npm, and Perfetto web build inputs.
- **Let upstream Winscope capture devices:** creates a second ADB ownership, permission, capability, and failure-reporting path.
- **Automatically track upstream HEAD:** the web application has no stable embedding API, so silent updates could break the maintained loader patch or packaged evidence contract.

## Consequences and risks

- Each platform distribution grows by roughly 45.2 MiB before installer compression. This estimate must be regenerated rather than treated as a permanent budget.
- The automatic loader patch is a maintained compatibility seam and may need adjustment on each upstream upgrade.
- Sensitive evidence becomes visible to the user's browser process and its extensions after confirmation, even though it is not uploaded to a remote service.
- System-browser behavior varies. Release validation must cover the supported browser/OS matrix and fail clearly when required WebAssembly, file, media, or WebGL capabilities are unavailable.
- Redistributing bundled JavaScript, WASM, fonts, and other assets requires a generated third-party license inventory and all applicable license and notice text. Missing attribution blocks release.

## Acceptance criteria

1. A valid session with parsed WindowManager or SurfaceFlinger evidence can open from its toolbar and automatically loads the current evidence package in the default browser.
2. A video-only or otherwise core-empty session cannot open the viewer and reports that no Winscope core evidence was captured; it is not described as a partial Winscope capture.
3. The optional recording and other recognized files in the current evidence package remain available to the upstream viewer without a second file picker.
4. Sensitive sessions require confirmation before their first browser handoff; cancelling exposes no evidence endpoint.
5. Evidence is reachable only through the tokenized loopback endpoint, is served with `Cache-Control: no-store`, cannot be discovered by directory traversal or listing, and becomes unreachable after shutdown or expiry.
6. Opening the viewer succeeds without an external `http-server` process or `winscope_proxy.py`; a runtime network test observes no request to ports 5544 or 9167 and no request to analytics, font, icon, CDN, or other third-party origins.
7. Packaged-resource verification checks the pinned source commit, patch series, manifest, checksums, license inventory, and exact referenced asset closure; stale compatibility bundles and `winscope_proxy.py` are absent.
8. macOS, Windows, and Linux packages contain the same viewer version and pass a smoke test covering launch, automatic trace parsing, optional video, WebGL/3D rendering, and unsupported-browser error handling.
