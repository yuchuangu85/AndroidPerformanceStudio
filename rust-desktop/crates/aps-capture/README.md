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
validated ADB arguments for bounded-duration `simpleperf record`, obtains the exact
remote recorder PID, waits for the supervisor's recorder and stdin-writer exit
acknowledgement, and then reads `perf.data`
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
is performed. Remote shell strings use validated numeric arguments, the
restricted run ID, and the discovered, validated application package. Arbitrary
user shell text is never accepted. The detached worker starts through a fresh
shell exec so Android shell redirection-save descriptors cannot retain the ADB
connection. Its private stdin pipe is explicitly duplicated before launching
the asynchronous recorder; `--stdio-controls-profiling` stops on pipe EOF.
Cancel creates only this run's stop marker, closing its writer, without signalling
any numeric PID. Recorder completion also closes the writer immediately rather
than waiting for its duration-plus-ten-second safety bound. The supervisor
publishes the recorder's actual exit code only after the writer has acknowledged
completion. Cancellation cleanup ignores the cancelled host token so it can
confirm shutdown for up to ten seconds. Unconfirmed shutdown becomes
`CancellationUnverified` and retains the exact remote directory for recovery.
Failure/cancellation removes only the freshly created local run's exact
`perf.data` and `report-sample.pb` names; no completed artifact is returned.
Successful and verified failed/cancelled runs remove only the owned remote
artifact/coordination files and their fresh directory. Local cleanup is best-effort;
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
here. The recorder and its control writer have bounded lifetimes; uncertain
shutdown never authorizes artifact removal. Packaged compatibility remains a
separate release gate. A valid raw file and recorder exit zero prove transport
completion, not populated CPU samples or successful conversion/import.

Unit tests use a fake ADB/recorder to cover the detached exec boundary, prompt
pipeline completion, private-pipe cancellation, recorder failure, and exact
cleanup/retention. They do not invoke a device.

## Application integration

`CaptureWorkspace` displays immutable device/PID/process, boot/start identity,
duration, event, frequency/period, scope, callgraph, converter, local/remote paths and side effects before its
one-shot confirmation button. Current editable controls cannot alter the
frozen plan; edits require Inspect again. Background status is sampled from
`CaptureHandle`; Cancel requests an owned stdin-pipe stop and exposes an
unverified state if the stop cannot be confirmed. Completed
outputs emit `CaptureCompleted(CaptureOutput)` for trusted shell integration.
The protobuf header check is not a full parser or parity guarantee; CPU import
performs its own complete supported-format parsing. Real-device validation is
separate from unit tests and compilation.

The selected host converter is hashed during planning and rehashed immediately
before conversion. This detects ordinary replacement but is not an atomic
execute-by-file-descriptor guarantee or a sandbox. Remote recording is bounded
by duration, not a remote filesystem quota; a transfer larger than 64 MiB is
rejected locally, with exact remote cleanup only after verified shutdown. Plan
hashing is blocking on the background executor and has no hard cancellation
latency guarantee.

## Sampling Configuration

`CpuCaptureRequest.sampling` owns `SamplingConfiguration`: an event token,
`SamplingRate::Frequency` or `Period`, `EventScope` and `CallGraphMode`.
`SamplingTemplate::create(app_scoped)` ports all five Compose presets without
changing the target, requesting root or granting profiling permission. Template
scope defaults to USER for discovered app context and BOTH otherwise.

Request validation runs before device discovery. Event tokens are at most 256
bytes and restricted to ASCII alphanumerics plus `_:-./`, beginning with an
alphanumeric; shell expressions and whitespace are not accepted. Scope adds
`:u`, `:k` or no suffix. Frequency uses `-f` (1..4000); period uses `-c` with a
positive signed-64-bit count. Callgraphs retain the Compose `-g`, `--call-graph fp`
or absent-argument mapping. The frozen configuration builds the actual command,
and failure diagnostics name its event/scope without claiming kernel sampling
was not requested. No root or permission change is performed.

`tests/fixtures/compose-sampling.tsv` contains 46 cases generated by executing
the real Compose models using the retained Java driver. Its provenance records
the oracle source hashes. Normalized event/rate/scope/callgraph semantics and
template duration match; target forms, capture permissions and resource/duration
limits are separate, unfinished parity boundaries. Fake-recorder tests inspect
executed argv and owned cleanup, not just the command builder.
