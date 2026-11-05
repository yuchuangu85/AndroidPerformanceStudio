# perfetto-ui-launch spec

This capability governs how Android Performance Studio launches the Perfetto UI for an active trace: opening a trace never starts the local Perfetto UI service or a browser as a side effect, the local trace-serving service starts only from an explicit "Open in Perfetto UI" / "Open in local Perfetto UI" user action, the trace endpoint serves the active trace on a fixed `127.0.0.1:9001` origin that the official `ui.perfetto.dev` CSP allows, the iframe embed fallback is removed, and trace diagnostics remain independent of the service. It also covers launching the native `trace_processor_shell` engine for large traces that exceed the browser's WASM memory limits.

## Purpose

Define how the Trace Analyzer opens a trace in the browser-side Perfetto UI: a byte-serving endpoint for the official `?url=` flow (small/medium traces) and a native `trace_processor_shell server http` engine for large traces. Both modes exclusively own port 9001, start only on explicit user action, and are serialized so rapid switching between modes leaves a single consistent running service.
## Requirements
### Requirement: Opening a trace SHALL NOT start the local Perfetto UI service

The Trace Analyzer SHALL NOT start `PerfettoUiServer` (or any local HTTP service) and SHALL NOT launch a browser as a side effect of opening a trace, regardless of entry point (file open, recent session, capture completion, or external navigation into the page). Starting the local Perfetto UI service SHALL occur only as a result of an explicit user action (see Open in Perfetto UI / Open in local Perfetto UI).

#### Scenario: User opens a trace file via the File menu
- **WHEN** the user opens a `.pftrace` file through the File menu
- **THEN** the trace becomes active in the Trace Analyzer workspace, and no local HTTP service is started and no browser window is opened

#### Scenario: Capture completes
- **WHEN** a Perfetto capture completes and its trace is shown in the workspace
- **THEN** no local HTTP service is started and no browser window is opened

#### Scenario: External navigation with an initial trace
- **WHEN** the application navigates to the Trace Analyzer with an initial trace file (e.g. from another profiler destination)
- **THEN** the trace is activated without starting the local Perfetto UI service or launching a browser

### Requirement: Open in Perfetto UI SHALL load the trace through the official Perfetto UI

The "Open in Perfetto UI" action SHALL start the local trace-serving endpoint, then open the system browser at the official Perfetto UI with a `?url=` parameter pointing at the local trace endpoint (`https://ui.perfetto.dev/#!/?url=http://127.0.0.1:9001/trace_file.perfetto-trace&referrer=record_android_trace`). The same behavior SHALL apply to opening a recent session (panel button and File → Open Recent): the trace is activated in the workspace and launched in the official UI. The trace endpoint SHALL serve the active trace bytes with a CORS header echoing the request Origin (not `*`) plus `Cache-Control: no-cache`, matching the record_android_trace server, so the official UI can fetch them. The workspace "Open in Perfetto UI" button SHALL be wired to this action: invoking it SHALL start the trace-serving endpoint and open the browser, not merely re-activate the already-active trace.

#### Scenario: Default open action
- **WHEN** the user invokes "Open in Perfetto UI" with an active trace
- **THEN** the local trace-serving endpoint starts (or is reused if already running), and the system browser opens `https://ui.perfetto.dev/#!/?url=http://127.0.0.1:9001/trace_file.perfetto-trace&referrer=record_android_trace`

#### Scenario: Recent session open action
- **WHEN** the active trace is opened from a recent session (panel button or File → Open Recent)
- **THEN** the trace is activated in the workspace, the trace-serving endpoint starts, and the browser opens the official UI pointed at the local trace endpoint

#### Scenario: Workspace button open action
- **WHEN** the user clicks the workspace "Open in Perfetto UI" button with an active trace whose file exists
- **THEN** the local trace-serving endpoint starts (or is reused if already running), and the system browser opens the official UI pointed at the local trace endpoint

#### Scenario: Official UI fetches the trace
- **WHEN** the official Perfetto UI requests the trace from the local endpoint
- **THEN** the endpoint responds with the active trace bytes, `Access-Control-Allow-Origin` echoing the request Origin, `Cache-Control: no-cache`, and content type `application/octet-stream`

#### Scenario: No trace is active
- **WHEN** the user invokes "Open in Perfetto UI" without an active trace
- **THEN** the action reports an error and does not start the endpoint or open a browser

### Requirement: Local Perfetto UI assets SHALL be served only via explicit menu action

A menu item ("Open in local Perfetto UI") SHALL serve locally available Perfetto UI assets and open the browser at the locally hosted UI (`http://127.0.0.1:9001/#!/?url=http://127.0.0.1:9001/trace_file.perfetto-trace`). The menu item SHALL be enabled only when local UI assets are discoverable (`PerfettoUiServer.tryFindUiAssetsDir()` returns a directory containing `index.html`); otherwise it SHALL be disabled.

#### Scenario: Local UI assets available
- **WHEN** local UI assets are discoverable and the user invokes "Open in local Perfetto UI"
- **THEN** the local service starts serving both the UI assets and the `/trace` endpoint, and the browser opens the locally hosted UI pointed at the local trace endpoint

#### Scenario: Local UI assets not available
- **WHEN** local UI assets are not discoverable
- **THEN** the "Open in local Perfetto UI" menu item is disabled and cannot start the service

### Requirement: Trace endpoint SHALL serve on port 9001

The local trace-serving endpoint SHALL bind `127.0.0.1:9001`. This port is the only origin the official ui.perfetto.dev Content-Security-Policy allows fetching from (the same port record_android_trace reuses). Browser URLs SHALL reference `http://127.0.0.1:9001/trace_file.perfetto-trace` with `referrer=record_android_trace`.

#### Scenario: Port 9001 is free
- **WHEN** the service starts and port 9001 is not in use
- **THEN** the endpoint binds `127.0.0.1:9001` and the browser URL references `http://127.0.0.1:9001/trace_file.perfetto-trace`

#### Scenario: Port 9001 is occupied
- **WHEN** the service starts and binding port 9001 fails
- **THEN** the start fails with a `SERVER_START_FAILED` error surfaced to the user; the service MUST NOT fall back to another port, because the official UI cannot fetch from any other origin

### Requirement: The embedded fallback page SHALL be removed

The service SHALL NOT provide the iframe embed fallback page (postMessage-based trace injection into `ui.perfetto.dev`, including `mode=embedded` and zoom postMessage logic). Requests to the service root in trace-only mode SHALL NOT serve an embed page.

#### Scenario: Root request in trace-only mode
- **WHEN** the service is running in trace-only mode (official UI flow) and a client requests the service root
- **THEN** the service does not serve an embed page

### Requirement: Trace diagnostics SHALL NOT depend on the Perfetto UI service

Trace diagnostics SHALL continue to resolve and launch `trace_processor_shell` on demand when a diagnostic query runs, independent of whether the Perfetto UI service is running.

#### Scenario: Diagnostic query without the UI service
- **WHEN** the user runs a trace diagnostic query while the Perfetto UI service has never been started
- **THEN** the diagnostic executes successfully via `trace_processor_shell` without requiring the Perfetto UI service

### Requirement: Open with native engine SHALL launch the native trace processor and open the official UI

The Trace Analyzer SHALL provide an explicit "Open with native engine" action for the active trace that starts `trace_processor_shell` (resolved via the Trace Processor tool resolution rules) with `server http --ip-address 127.0.0.1 --port 9001 --idle-timeout 30m <trace>`, waits for the HTTP endpoint to respond (bounded startup probe; failure SHALL surface a `TRACE_PROCESSOR_NOT_READY` / start-failure error without leaving a running process), and then opens the system browser at the official `https://ui.perfetto.dev` without a `?url=` parameter, relying on the UI's automatic detection of the RPC endpoint. The action SHALL be available whenever the active trace file exists (same availability as "Open in Perfetto UI"). Resolution failures (tool not found, checksum mismatch, override invalid) SHALL surface the existing trace-processor tool-resolution errors.

#### Scenario: Native engine open succeeds
- **WHEN** the user invokes "Open with native engine" with an active trace and a usable trace processor binary
- **THEN** the native RPC server process starts bound to 127.0.0.1:9001 with the trace file loaded, and the system browser opens `https://ui.perfetto.dev` (no `?url=` parameter)

#### Scenario: Tool resolution fails
- **THEN** WHEN the trace processor binary cannot be resolved (missing, checksum mismatch, or invalid override) when invoking "Open with native engine"
- **THEN** the action surfaces the tool-resolution error, no process is started, and no browser is opened

#### Scenario: Startup probe times out
- **WHEN** the native RPC server fails to bind port 9001 or does not respond within the startup probe window
- **THEN** the action surfaces a start-failure error, the process is stopped, and no browser is opened

### Requirement: Native engine and trace-serving endpoint SHALL exclusively own port 9001

Starting the native engine SHALL first stop a running `PerfettoUiServer` (byte-serving endpoint), releasing port 9001; conversely, invoking "Open in Perfetto UI" while the native engine owns port 9001 SHALL surface the existing `SERVER_START_FAILED` error rather than silently switching modes. Mode handover SHALL be explicit: whichever action the user invoked last owns the port.

#### Scenario: Byte service is running when native engine starts
- **WHEN** the `PerfettoUiServer` is running and the user invokes "Open with native engine"
- **THEN** the `PerfettoUiServer` is stopped first, and the native RPC server binds port 9001

#### Scenario: Native engine owns port when byte service starts
- **WHEN** the native engine is running and the user invokes "Open in Perfetto UI"
- **THEN** the start fails with the existing `SERVER_START_FAILED` error and the native engine keeps running

### Requirement: Native engine process SHALL be cleaned up with the page lifecycle

The native engine process SHALL be stopped when the Trace Analyzer page is disposed and when the page deactivates (`isActive` becomes false), alongside the existing `PerfettoUiServer` cleanup. The `--idle-timeout 30m` flag SHALL additionally bound the process lifetime to inactivity as a backstop.

#### Scenario: Page disposed while native engine runs
- **WHEN** the Trace Analyzer page is disposed while the native engine is running
- **THEN** the native engine process is stopped and port 9001 is released

#### Scenario: Page deactivates while native engine runs
- **WHEN** the page deactivates (isActive false) while the native engine is running
- **THEN** the native engine process is stopped

#### Scenario: Idle timeout backstop
- **WHEN** the native engine receives no activity for 30 minutes
- **ONLY THEN** the process exits by itself and releases port 9001

