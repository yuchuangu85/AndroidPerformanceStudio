## ADDED Requirements

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

## MODIFIED Requirements

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
