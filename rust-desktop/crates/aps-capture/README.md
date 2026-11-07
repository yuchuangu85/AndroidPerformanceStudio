# Explicit CPU capture boundary

`CaptureController::plan_cpu` is read-only and returns a single-use
`PlannedCapture`. The user-facing caller must show its serial/PID/process name,
boot ID and `/proc/PID/stat` start ticks and call `confirm` with the exact values.
`ConfirmedCapture::run` re-observes the process before any mutation. This narrows
PID-reuse risk but is not an atomic kernel guarantee. The GPUI capture workspace now exposes a separate explicit confirmation action
that consumes the displayed plan. Navigation and Inspect never call `run`. Plans
expire after 60 seconds and the target is re-observed immediately before side
effects; this is not a kernel process-instance lease.

The confirmed run creates a new local session directory and a uniquely named
remote `/data/local/tmp/aps-rust-<run-id>` directory without `-p`. It executes
fixed ADB arguments for bounded-duration `simpleperf record`, obtains the exact
remote recorder PID, waits for that PID to exit, and then reads `perf.data`
through `adb exec-out cat` with a hard 64 MiB stdout cap. The owned
ADB child is terminated on overflow and no local artifact is written before the
bounded read completes. This deliberately rejects larger profiles instead of
risking unbounded local disk use. A remote size pre-probe is not trusted as a
transfer limit because the remote file can change between probe and read. An optional, explicitly provided absolute host `simpleperf`
executable converts using the Compose oracle command:
`report-sample --protobuf --show-callchain -i perf.data -o report-sample.pb`.
Raw and converted outputs have size limits, SHA-256 and provenance; converted
output links to the raw digest.

No `pkill`, broad remote cleanup, package install, root or bundled tool mutation
is performed. The only remote shell string is generated from validated numeric
arguments and the restricted run ID to background the fixed command and return
its PID; arbitrary user shell text is never accepted. Cancel sends `SIGINT` only
to that exact PID and waits up to five seconds for `/proc/<pid>/cmdline` to
disappear. A stop or PID-identity failure becomes `CancellationUnverified`, not
a successful cancellation. The remote artifact intentionally remains for manual
recovery after a verified stop.
Failure/cancellation removes only the freshly created local run's exact
`perf.data` and `report-sample.pb` names; no completed artifact is returned.
The remote artifact may remain for manual recovery. Local cleanup is best-effort;
a filesystem error can leave a partial file, which must never be imported as a
completed capture. On Unix, the optional converter's explicit `-o` path is `/dev/stdout`, an
inherited pipe read through the same 64 MiB hard stdout bound. No regular
protobuf file is created until the bounded stream completes; only then is it
atomically written. Expected output must begin with the SIMPLEPERF v1 header.
This relies on the selected Simpleperf accepting a pipe output; incompatible
binaries fail closed, and actual packaged-version compatibility has not been
run. Windows conversion is rejected rather than falling back to an unbounded
regular file. Executing an explicitly selected local tool is not a security
sandbox: an arbitrary malicious executable could write other paths. There is
no Perfetto capture, tool provisioning or verified packaged Simpleperf resolver
here. Remote process lifetime is now bounded by the PID lease, but real-device
stop behavior and packaged compatibility remain release gates.

Tests in source are deferred by the active migration instruction. No device
command is executed as part of implementation or compilation.

## Application integration

`CaptureWorkspace` displays immutable device/PID/process, boot/start identity,
duration/frequency, converter, local/remote paths and side effects before its
one-shot confirmation button. Current editable controls cannot alter the
frozen plan; edits require Inspect again. Background status is sampled from
`CaptureHandle`; Cancel requests an exact remote PID stop and exposes an
unverified state if the stop cannot be confirmed. Completed
outputs emit `CaptureCompleted(CaptureOutput)` for trusted shell integration.
The protobuf header check is not a full parser or parity guarantee; CPU import
performs its own complete supported-format parsing. No device/tool process was
run during implementation or compilation.

The selected host converter is hashed during planning and rehashed immediately
before conversion. This detects ordinary replacement but is not an atomic
execute-by-file-descriptor guarantee or a sandbox. Remote recording is bounded
by duration, not a remote filesystem quota; a transfer larger than 64 MiB is
rejected locally and the remote file remains. Plan hashing is blocking on the
background executor and has no hard cancellation latency guarantee.
