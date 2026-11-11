//! Blocking, explicitly authorized APS Network Agent v2 capture. Raw frames,
//! tokens and arbitrary Agent strings never leave this module. Run off the UI
//! thread; only the allocated local ADB forward is owned and removed here.
use aps_adb::{AdbClient, AdbLocator, validate_serial};
use aps_network::{
    HarSession, MINIMIZED_URL, NetworkEntry, ObservedCapture, ObservedOutcome, ObservedPhase,
    ObservedPhaseKind, ObservedRequest,
};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use std::{
    collections::BTreeMap,
    io::{Read, Write},
    net::{Ipv4Addr, Shutdown, SocketAddr, SocketAddrV4, TcpStream},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant},
};

const AGENT_PORT: u16 = 49_373;
const PROTOCOL_VERSION: u64 = 2;
const MAX_FRAME: usize = 4 * 1024 * 1024;
const BATCH: usize = 500;
const MAX_EVENTS: u64 = 200_000;
const MAX_REQUESTS: usize = 20_000;
const MAX_PHASES: usize = 64;
const MAX_DURATION: Duration = Duration::from_secs(30 * 60);
const COMMAND_TIMEOUT: Duration = Duration::from_secs(5);
const IO_TIMEOUT: Duration = Duration::from_secs(3);
const STOP_TIMEOUT: Duration = Duration::from_secs(12);

#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct CaptureTarget {
    pub serial: String,
    pub pid: u32,
    pub process_name: String,
    pub package_name: String,
    pub user: String,
}

pub(super) enum CaptureUpdate {
    Snapshot(LiveSnapshot),
    Finished(Result<LiveSnapshot, String>),
}
pub(super) struct LiveSnapshot {
    pub session: Arc<HarSession>,
    pub publication: super::NetworkPublication,
}
impl LiveSnapshot {
    fn new(session: Arc<HarSession>) -> Result<Self, String> {
        let publication = super::load_page(
            &session,
            0,
            &aps_network::EntryQuery::default(),
            None,
            &CancellationToken::new(),
        )
        .map_err(|_| "Cannot publish bounded observed network evidence")?;
        Ok(Self {
            session,
            publication,
        })
    }
}

#[derive(Default)]
pub(super) struct LiveControl {
    stop: AtomicBool,
    finished: AtomicBool,
    cancel: CancellationToken,
    latest: Mutex<Option<CaptureUpdate>>,
}
impl LiveControl {
    pub fn stop(&self) {
        self.stop.store(true, Ordering::Release);
    }
    pub fn cancel(&self) {
        self.cancel.cancel();
        if let Ok(mut latest) = self.latest.lock() {
            *latest = None;
        }
    }
    pub fn cancelled(&self) -> bool {
        self.cancel.is_cancelled()
    }
    pub fn stopping(&self) -> bool {
        self.stop.load(Ordering::Acquire)
    }
    pub fn finished(&self) -> bool {
        self.finished.load(Ordering::Acquire)
    }
    pub fn take_update(&self) -> Option<CaptureUpdate> {
        self.latest.lock().ok()?.take()
    }
    fn publish(&self, mut update: CaptureUpdate) {
        if let Ok(mut latest) = self.latest.lock() {
            // Recheck inside the same mailbox lock used by cancel, so neither
            // an in-flight snapshot nor a racing success can republish evidence.
            if self.cancelled() {
                update = match update {
                    CaptureUpdate::Snapshot(_) => return,
                    CaptureUpdate::Finished(Ok(_)) => CaptureUpdate::Finished(Err(
                        "Network capture cancelled after owned cleanup".into(),
                    )),
                    update => update,
                };
            }
            *latest = Some(update);
        }
    }
}

pub(super) fn run(target: CaptureTarget, control: Arc<LiveControl>) {
    let result = run_inner(&target, &control).and_then(|session| {
        if control.cancelled() {
            Err("Network capture cancelled after owned cleanup".into())
        } else {
            LiveSnapshot::new(session)
        }
    });
    control.publish(CaptureUpdate::Finished(result));
    control.finished.store(true, Ordering::Release);
}

fn run_inner(target: &CaptureTarget, control: &LiveControl) -> Result<Arc<HarSession>, String> {
    validate_target(target)?;
    let location = AdbLocator::new()
        .locate(None)
        .map_err(|_| "ADB is unavailable; configure the Android SDK before network capture")?;
    let adb = AdbClient::new(location).with_timeout(COMMAND_TIMEOUT);
    if !adb
        .list_devices(&control.cancel)
        .map_err(|_| "Cannot inspect the authorized device")?
        .iter()
        .any(|device| device.serial == target.serial && device.is_online())
    {
        return Err("The authorized device is disconnected or not online".into());
    }
    let identity = read_identity(&adb, target, &control.cancel)?;
    let snapshot = adb
        .target_snapshot(&target.serial, &control.cancel)
        .map_err(|_| "Cannot verify the authorized application process")?;
    if !snapshot.application_processes().iter().any(|process| {
        process.pid == target.pid
            && process.name == target.process_name
            && process.package_name == target.package_name
            && process.user == target.user
    }) {
        return Err(
            "The selected application process changed; select it again and authorize a new capture"
                .into(),
        );
    }
    verify_identity(&adb, target, &identity, &control.cancel)?;
    if control.cancelled() || control.stopping() {
        return Err("Network capture stopped before connecting".into());
    }
    // This command has no raw-traffic output. Authentication material stays
    // transient, is never formatted into diagnostics, and is cleared below.
    let mut token = adb_bytes(&adb, &target.serial,
        &["shell", "run-as", &target.package_name, "cat", "files/aps-network/token"],
        256, &control.cancel)
        .map_err(|_| "Network Agent token unavailable; use a running debuggable app with the APS Network Agent initializer")?;
    while token.last().is_some_and(u8::is_ascii_whitespace) {
        token.pop();
    }
    if token.len() != 43
        || !token
            .iter()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
    {
        token.fill(0);
        return Err("Network Agent authentication token has an invalid format".into());
    }
    if control.cancelled() || control.stopping() {
        token.fill(0);
        return Err("Network capture stopped before allocating a forward".into());
    }
    // A successful tcp:0 allocation returns the only port we may later remove.
    // An uncertain allocation never triggers a broad forward cleanup.
    let mut forward = match OwnedForward::allocate(
        adb.clone(),
        target.serial.clone(),
        &CancellationToken::new(),
    ) {
        Ok(forward) => forward,
        Err(error) => {
            token.fill(0);
            return Err(error);
        }
    };
    let result = (|| {
        verify_identity(&adb, target, &identity, &control.cancel)?;
        let address = SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::LOCALHOST, forward.port));
        let socket = TcpStream::connect_timeout(&address, IO_TIMEOUT)
            .map_err(|_| "Cannot connect to APS Network Agent on the authorized device")?;
        socket
            .set_read_timeout(Some(Duration::from_millis(100)))
            .map_err(|_| "Cannot bound Network Agent reads")?;
        socket
            .set_write_timeout(Some(Duration::from_millis(100)))
            .map_err(|_| "Cannot bound Network Agent writes")?;
        let mut wire = Wire { socket };
        let hello = wire.exchange("HELLO", Some(&token));
        token.fill(0);
        let ready = hello?;
        if ready.kind != ReplyKind::Ready
            || ready.pid != target.pid
            || !ready.events.is_empty()
            || ready.has_more
        {
            return Err(
                "Network Agent did not acknowledge the authorized process and protocol".into(),
            );
        }
        // Any post-HELLO exit still closes only this client; the Android server
        // isolates client EOF and remains available for another capture.
        let capture_result = (|| {
            verify_identity(&adb, target, &identity, &control.cancel)?;
            let mut collector = Collector::new(target.pid, identity, &ready);
            let started = Instant::now();
            let mut last_identity = Instant::now();
            let mut last_publication = Instant::now() - Duration::from_secs(1);
            let mut draining = false;
            let mut stop_deadline = None;
            loop {
                if control.cancelled()
                    || control.stopping()
                    || started.elapsed() >= MAX_DURATION
                    || collector.capture.event_count >= MAX_EVENTS
                    || collector.calls.len() >= MAX_REQUESTS
                {
                    if !draining {
                        collector.capture.limit_reached = started.elapsed() >= MAX_DURATION
                            || collector.capture.event_count >= MAX_EVENTS
                            || collector.calls.len() >= MAX_REQUESTS;
                        draining = true;
                        stop_deadline = Some(Instant::now() + STOP_TIMEOUT);
                    }
                }
                if stop_deadline.is_some_and(|deadline| Instant::now() >= deadline) {
                    return Err("Network Agent STOP drain exceeded its deadline; capture completeness is unverified".into());
                }
                if !draining && last_identity.elapsed() >= Duration::from_secs(3) {
                    verify_identity(&adb, target, &collector.identity, &control.cancel)?;
                    last_identity = Instant::now();
                }
                let response = wire.exchange(if draining { "STOP" } else { "POLL" }, None)?;
                if response.pid != target.pid
                    || !matches!(
                        response.kind,
                        ReplyKind::Events | ReplyKind::Stopping | ReplyKind::Stopped
                    )
                    || (draining && response.kind == ReplyKind::Events)
                    || (!draining && response.kind != ReplyKind::Events)
                {
                    return Err(
                        "Network Agent response does not match the active capture operation".into(),
                    );
                }
                let has_more = response.has_more;
                let stopped = response.kind == ReplyKind::Stopped;
                collector.apply(response)?;
                if stopped {
                    collector.capture.stop_acknowledged = true;
                    // A final successful snapshot is bound to the same kernel
                    // process instance; cancellation does not publish results.
                    if !control.cancelled() {
                        verify_identity(
                            &adb,
                            target,
                            &collector.identity,
                            &CancellationToken::new(),
                        )?;
                    }
                    return collector.snapshot();
                }
                if !draining && last_publication.elapsed() >= Duration::from_millis(750) {
                    control.publish(CaptureUpdate::Snapshot(LiveSnapshot::new(
                        collector.snapshot()?,
                    )?));
                    last_publication = Instant::now();
                }
                if !has_more && !draining {
                    // Short sleeps observe stop/cancel without an unbounded UI
                    // channel or a busy poll against the device.
                    for _ in 0..10 {
                        if control.cancelled() || control.stopping() {
                            break;
                        }
                        thread::sleep(Duration::from_millis(20));
                    }
                }
            }
        })();
        if capture_result.is_err() {
            // Best effort, bounded session shutdown. Do not interpret this as
            // successful collection after an invalid or truncated response.
            let deadline = Instant::now() + IO_TIMEOUT;
            while Instant::now() < deadline {
                match wire.exchange("STOP", None) {
                    Ok(reply) if reply.kind == ReplyKind::Stopped => break,
                    Ok(reply) if reply.kind == ReplyKind::Stopping => continue,
                    _ => break,
                }
            }
        }
        let _ = wire.socket.shutdown(Shutdown::Both);
        capture_result
    })();
    token.fill(0);
    let cleanup = forward.close();
    match (result, cleanup) {
        (Ok(session), Ok(())) => Ok(session),
        (Err(error), Ok(())) => Err(error),
        (_, Err(error)) => Err(error),
    }
}

fn validate_target(target: &CaptureTarget) -> Result<(), String> {
    validate_serial(&target.serial).map_err(|_| "Invalid selected device serial")?;
    if target.pid == 0
        || target.package_name.len() > 255
        || !target.package_name.contains('.')
        || !target.package_name.split('.').all(|part| {
            !part.is_empty()
                && part
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
        })
        || target.process_name.len() > 512
        || target.user.is_empty()
        || target.user.len() > 128
    {
        return Err("Invalid selected application process".into());
    }
    Ok(())
}

#[derive(Clone, PartialEq, Eq)]
struct Identity {
    boot_id: String,
    start_ticks: u64,
}

fn read_identity(
    adb: &AdbClient,
    target: &CaptureTarget,
    cancel: &CancellationToken,
) -> Result<Identity, String> {
    let boot = adb_bytes(
        adb,
        &target.serial,
        &["shell", "cat", "/proc/sys/kernel/random/boot_id"],
        128,
        cancel,
    )?;
    let boot = std::str::from_utf8(&boot)
        .map_err(|_| "Invalid device boot identity")?
        .trim();
    if boot.len() != 36
        || !boot.bytes().enumerate().all(|(i, byte)| {
            if [8, 13, 18, 23].contains(&i) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit()
            }
        })
    {
        return Err("Cannot verify device boot identity".into());
    }
    let path = format!("/proc/{}/stat", target.pid);
    let stat = adb_bytes(adb, &target.serial, &["shell", "cat", &path], 8192, cancel)?;
    let stat = std::str::from_utf8(&stat).map_err(|_| "Invalid selected process identity")?;
    let (prefix, fields) = stat
        .rsplit_once(") ")
        .ok_or("Cannot verify selected process start identity")?;
    if prefix
        .split_once(" (")
        .and_then(|(pid, _)| pid.parse::<u32>().ok())
        != Some(target.pid)
    {
        return Err("Selected process identity changed".into());
    }
    let start_ticks = fields
        .split_whitespace()
        .nth(19)
        .and_then(|value| value.parse::<u64>().ok())
        .filter(|ticks| *ticks > 0)
        .ok_or("Cannot verify selected process start ticks")?;
    Ok(Identity {
        boot_id: boot.to_owned(),
        start_ticks,
    })
}

fn verify_identity(
    adb: &AdbClient,
    target: &CaptureTarget,
    expected: &Identity,
    cancel: &CancellationToken,
) -> Result<(), String> {
    if &read_identity(adb, target, cancel)? != expected {
        return Err(
            "Authorized device or process instance changed; capture was not published".into(),
        );
    }
    Ok(())
}

fn adb_bytes(
    adb: &AdbClient,
    serial: &str,
    args: &[&str],
    limit: usize,
    cancel: &CancellationToken,
) -> Result<Vec<u8>, String> {
    let mut request = ProcessRequest::new(&adb.location().executable)
        .args(["-s", serial])
        .args(args);
    request.timeout = COMMAND_TIMEOUT;
    request.stdout_limit = limit;
    request.stderr_limit = 4096;
    let output = ProcessRunner
        .run(&request, cancel)
        .map_err(|_| "Authorized network ADB command did not complete")?;
    if !output.status.success() {
        return Err("Authorized network ADB command failed; check device authorization and debuggable Agent installation".into());
    }
    Ok(output.stdout)
}

struct OwnedForward {
    adb: AdbClient,
    serial: String,
    port: u16,
    active: bool,
}
impl OwnedForward {
    fn allocate(
        adb: AdbClient,
        serial: String,
        cancel: &CancellationToken,
    ) -> Result<Self, String> {
        let output = adb_bytes(&adb, &serial, &["forward", "--no-rebind", "tcp:0", &format!("tcp:{AGENT_PORT}")], 64, cancel)
            .map_err(|_| "Network forward allocation was not acknowledged; no unrelated forwards were removed")?;
        let port = std::str::from_utf8(&output)
            .ok()
            .and_then(|value| value.trim().parse::<u16>().ok())
            .filter(|port| *port > 0)
            .ok_or(
                "Network forward returned no verifiable owned port; no broad cleanup was attempted",
            )?;
        Ok(Self {
            adb,
            serial,
            port,
            active: true,
        })
    }
    fn close(&mut self) -> Result<(), String> {
        if !self.active {
            return Ok(());
        }
        let cancel = CancellationToken::new();
        let output = adb_bytes(
            &self.adb,
            &self.serial,
            &["forward", "--list"],
            256 * 1024,
            &cancel,
        )
        .map_err(
            |_| "Cannot verify owned network forward cleanup; no unrelated mappings were removed",
        )?;
        let rows = std::str::from_utf8(&output)
            .map_err(|_| "Cannot verify owned network forward identity")?;
        let local = format!("tcp:{}", self.port);
        let remote = format!("tcp:{AGENT_PORT}");
        let mut matches = rows
            .lines()
            .map(|line| line.split_whitespace().collect::<Vec<_>>())
            .filter(|row| row.get(1).is_some_and(|value| *value == local));
        match (matches.next(), matches.next()) {
            (None, _) => {
                self.active = false;
                return Ok(());
            }
            (Some(row), None) if row.len() == 3 && row[0] == self.serial && row[2] == remote => {}
            _ => {
                return Err(
                    "Network forward identity changed; replacement mapping was not removed".into(),
                );
            }
        }
        // Once removal is attempted its acknowledgement may be lost. Never
        // retry in Drop against a potentially reused port, even on failure.
        self.active = false;
        adb_bytes(
            &self.adb,
            &self.serial,
            &["forward", "--remove", &local],
            64,
            &cancel,
        )
        .map_err(|_| "Owned network forward removal failed; cleanup remains unverified")?;
        let output = adb_bytes(
            &self.adb,
            &self.serial,
            &["forward", "--list"],
            256 * 1024,
            &cancel,
        )
        .map_err(
            |_| "Owned network forward removal was acknowledged but cleanup could not be verified",
        )?;
        let rows = std::str::from_utf8(&output)
            .map_err(|_| "Owned network forward cleanup returned an invalid mapping list")?;
        if rows
            .lines()
            .any(|line| line.split_whitespace().nth(1) == Some(local.as_str()))
        {
            return Err("Network forward port remained or was reused after removal; no replacement mapping was removed".into());
        }
        Ok(())
    }
}
impl Drop for OwnedForward {
    fn drop(&mut self) {
        let _ = self.close();
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ReplyKind {
    Ready,
    Events,
    Stopping,
    Stopped,
}
struct Reply {
    kind: ReplyKind,
    pid: u32,
    latest: u64,
    dropped: u64,
    clock: u64,
    has_more: bool,
    events: Vec<Event>,
}
struct Wire {
    socket: TcpStream,
}
impl Wire {
    fn exchange(&mut self, command: &str, token: Option<&[u8]>) -> Result<Reply, String> {
        let token = token
            .map(std::str::from_utf8)
            .transpose()
            .map_err(|_| "Invalid Agent token encoding")?;
        let value = serde_json::json!({ "type": command, "protocolVersion": PROTOCOL_VERSION, "token": token, "maxEvents": BATCH });
        let mut bytes = serde_json::to_vec(&value)
            .map_err(|_| "Cannot encode bounded Network Agent command")?;
        drop(value);
        let deadline = Instant::now() + IO_TIMEOUT;
        let written = write_bounded(
            &mut self.socket,
            &(bytes.len() as u32).to_be_bytes(),
            deadline,
        )
        .and_then(|()| write_bounded(&mut self.socket, &bytes, deadline));
        bytes.fill(0);
        written?;
        let mut length = [0; 4];
        read_bounded(&mut self.socket, &mut length, deadline)?;
        let length = u32::from_be_bytes(length) as usize;
        if length == 0 || length > MAX_FRAME {
            return Err("Network Agent frame exceeds its byte budget".into());
        }
        let mut frame = vec![0; length];
        let read = read_bounded(&mut self.socket, &mut frame, deadline);
        let parsed = read.and_then(|()| parse_reply(&frame));
        frame.fill(0);
        parsed
    }
}
fn read_bounded(
    socket: &mut TcpStream,
    mut bytes: &mut [u8],
    deadline: Instant,
) -> Result<(), String> {
    while !bytes.is_empty() {
        if Instant::now() >= deadline {
            return Err("Network Agent response timed out".into());
        }
        match socket.read(bytes) {
            Ok(0) => return Err("Network Agent disconnected before completing a frame".into()),
            Ok(count) => bytes = &mut bytes[count..],
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock
                        | std::io::ErrorKind::TimedOut
                        | std::io::ErrorKind::Interrupted
                ) => {}
            Err(_) => return Err("Cannot read Network Agent frame".into()),
        }
    }
    Ok(())
}
fn write_bounded(
    socket: &mut TcpStream,
    mut bytes: &[u8],
    deadline: Instant,
) -> Result<(), String> {
    while !bytes.is_empty() {
        if Instant::now() >= deadline {
            return Err("Network Agent command timed out".into());
        }
        match socket.write(bytes) {
            Ok(0) => return Err("Network Agent disconnected during command".into()),
            Ok(count) => bytes = &bytes[count..],
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock
                        | std::io::ErrorKind::TimedOut
                        | std::io::ErrorKind::Interrupted
                ) => {}
            Err(_) => return Err("Cannot write Network Agent command".into()),
        }
    }
    Ok(())
}

fn integer(value: &aps_json::Value, key: &str) -> Result<u64, String> {
    value
        .get(key)
        .and_then(aps_json::Value::as_u64)
        .filter(|value| *value <= i64::MAX as u64)
        .ok_or_else(|| "Network Agent numeric field is missing or invalid".into())
}
fn optional_integer(value: &aps_json::Value, key: &str) -> Result<Option<u64>, String> {
    match value.get(key) {
        None | Some(aps_json::Value::Null) => Ok(None),
        Some(_) => integer(value, key).map(Some),
    }
}
fn parse_reply(bytes: &[u8]) -> Result<Reply, String> {
    let value = aps_json::parse_slice(
        bytes,
        aps_json::Limits {
            max_bytes: MAX_FRAME as u64,
            max_depth: 12,
            max_nodes: 40_000,
            max_string_bytes: 256 * 1024,
            max_collection_items: 1000,
        },
    )
    .map_err(|_| "Network Agent JSON is malformed, ambiguous or exceeds bounds")?;
    if integer(&value, "protocolVersion")? != PROTOCOL_VERSION {
        return Err("Network Agent protocol version mismatch".into());
    }
    let kind = match value.get("type").and_then(aps_json::Value::as_str) {
        Some("READY") => ReplyKind::Ready,
        Some("EVENTS") => ReplyKind::Events,
        Some("STOPPING") => ReplyKind::Stopping,
        Some("STOPPED") => ReplyKind::Stopped,
        _ => {
            return Err(
                "Network Agent rejected the command or returned an unknown response".into(),
            );
        }
    };
    let pid = integer(&value, "processId")
        .ok()
        .and_then(|value| u32::try_from(value).ok())
        .filter(|pid| *pid > 0)
        .ok_or("Network Agent did not identify its process")?;
    let raw_events = value
        .get("events")
        .and_then(aps_json::Value::as_array)
        .ok_or("Network Agent events are missing")?;
    if raw_events.len() > BATCH {
        return Err("Network Agent exceeded the requested batch size".into());
    }
    let mut events = Vec::with_capacity(raw_events.len());
    for raw in raw_events {
        events.push(Event::parse(raw)?);
    }
    Ok(Reply {
        kind,
        pid,
        latest: integer(&value, "latestSequence")?,
        dropped: integer(&value, "droppedEvents")?,
        clock: integer(&value, "deviceMonotonicNs")?,
        has_more: value
            .get("hasMore")
            .and_then(aps_json::Value::as_bool)
            .ok_or("Network Agent continuation flag is missing")?,
        events,
    })
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum EventKind {
    Start,
    End,
    Failed,
    Cancelled,
    Phase(ObservedPhaseKind, bool, bool),
    Other,
    Unknown,
}
struct Event {
    sequence: u64,
    clock: u64,
    call_id: String,
    kind: EventKind,
    method: Option<&'static str>,
    status: Option<u16>,
    bytes: Option<u64>,
    protocol: Option<&'static str>,
}
impl Event {
    fn parse(raw: &aps_json::Value) -> Result<Self, String> {
        use ObservedPhaseKind as P;
        let kind = match raw.get("kind").and_then(aps_json::Value::as_str) {
            Some("callStart") => EventKind::Start,
            Some("callEnd") => EventKind::End,
            Some("callFailed") => EventKind::Failed,
            Some("canceled") => EventKind::Cancelled,
            Some("proxySelectStart") => EventKind::Phase(P::Proxy, true, false),
            Some("proxySelectEnd") => EventKind::Phase(P::Proxy, false, false),
            Some("dnsStart") => EventKind::Phase(P::Dns, true, false),
            Some("dnsEnd") => EventKind::Phase(P::Dns, false, false),
            Some("connectStart") => EventKind::Phase(P::Connect, true, false),
            Some("connectEnd") => EventKind::Phase(P::Connect, false, false),
            Some("connectFailed") => EventKind::Phase(P::Connect, false, true),
            Some("secureConnectStart") => EventKind::Phase(P::Tls, true, false),
            Some("secureConnectEnd") => EventKind::Phase(P::Tls, false, false),
            Some("requestHeadersStart") => EventKind::Phase(P::RequestHeaders, true, false),
            Some("requestHeadersEnd") => EventKind::Phase(P::RequestHeaders, false, false),
            Some("requestBodyStart") => EventKind::Phase(P::RequestBody, true, false),
            Some("requestBodyEnd") => EventKind::Phase(P::RequestBody, false, false),
            Some("responseHeadersStart") => EventKind::Phase(P::ResponseHeaders, true, false),
            Some("responseHeadersEnd") => EventKind::Phase(P::ResponseHeaders, false, false),
            Some("responseBodyStart") => EventKind::Phase(P::ResponseBody, true, false),
            Some("responseBodyEnd") => EventKind::Phase(P::ResponseBody, false, false),
            Some(
                "connectionAcquired"
                | "connectionReleased"
                | "requestFailed"
                | "responseFailed"
                | "cacheHit"
                | "cacheMiss"
                | "cacheConditionalHit"
                | "satisfactionFailure",
            ) => EventKind::Other,
            Some(_) => EventKind::Unknown,
            None => return Err("Network Agent event kind is missing".into()),
        };
        let call_id = raw
            .get("callId")
            .and_then(aps_json::Value::as_str)
            .ok_or("Network Agent call identity is missing")?;
        if call_id.len() > 80
            || call_id.len() < 3
            || !call_id
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() || byte == b'-')
        {
            return Err("Network Agent call identity has an invalid format".into());
        }
        let method =
            raw.get("method")
                .and_then(aps_json::Value::as_str)
                .map(|method| match method {
                    "GET" => "GET",
                    "POST" => "POST",
                    "PUT" => "PUT",
                    "PATCH" => "PATCH",
                    "DELETE" => "DELETE",
                    "HEAD" => "HEAD",
                    "OPTIONS" => "OPTIONS",
                    "CONNECT" => "CONNECT",
                    "TRACE" => "TRACE",
                    _ => "OTHER",
                });
        let protocol = raw
            .get("protocol")
            .and_then(aps_json::Value::as_str)
            .and_then(|protocol| match protocol {
                "http/1.0" => Some("http/1.0"),
                "http/1.1" => Some("http/1.1"),
                "h2" => Some("h2"),
                "h2_prior_knowledge" => Some("h2_prior_knowledge"),
                "spdy/3.1" => Some("spdy/3.1"),
                "quic" => Some("quic"),
                _ => None,
            });
        let status = optional_integer(raw, "statusCode")?
            .map(|value| {
                u16::try_from(value)
                    .ok()
                    .filter(|status| (100..600).contains(status))
                    .ok_or("Network Agent HTTP status is invalid")
            })
            .transpose()?;
        // URL, message, TLS/cipher, connection and instrumentation strings are
        // intentionally never copied from this transient JSON representation.
        Ok(Self {
            sequence: integer(raw, "sequence")?,
            clock: integer(raw, "monotonicNs")?,
            call_id: call_id.to_owned(),
            kind,
            method,
            status,
            bytes: optional_integer(raw, "byteCount")?,
            protocol,
        })
    }
}

struct Collector {
    identity: Identity,
    capture: ObservedCapture,
    calls: BTreeMap<String, ObservedRequest>,
    cursor: u64,
    latest_boundary: u64,
    dropped_baseline: u64,
    last_dropped: u64,
    stop_boundary: Option<(u64, u64, u64)>,
}
impl Collector {
    fn new(pid: u32, identity: Identity, ready: &Reply) -> Self {
        Self {
            capture: ObservedCapture {
                process_id: pid,
                process_start_ticks: identity.start_ticks,
                device_boot_id: identity.boot_id.clone(),
                start_monotonic_ns: ready.clock,
                end_monotonic_ns: ready.clock,
                event_count: 0,
                dropped_events: 0,
                sequence_gaps: 0,
                excluded_preboundary_events: 0,
                unknown_events: 0,
                discarded_events: 0,
                omitted_call_events: 0,
                stop_acknowledged: false,
                limit_reached: false,
                requests: Vec::new(),
            },
            identity,
            calls: BTreeMap::new(),
            cursor: ready.latest,
            latest_boundary: ready.latest,
            dropped_baseline: ready.dropped,
            last_dropped: ready.dropped,
            stop_boundary: None,
        }
    }
    fn apply(&mut self, reply: Reply) -> Result<(), String> {
        if reply.pid != self.capture.process_id
            || reply.latest < self.latest_boundary
            || reply.clock < self.capture.end_monotonic_ns
            || reply.dropped < self.last_dropped
            || (reply.kind == ReplyKind::Stopped && reply.has_more)
            || (reply.kind == ReplyKind::Stopping && !reply.has_more)
            || (reply.has_more && reply.events.is_empty())
        {
            return Err("Network Agent sequence, clock or drain boundary regressed".into());
        }
        if matches!(reply.kind, ReplyKind::Stopping | ReplyKind::Stopped) {
            let boundary = (reply.latest, reply.clock, reply.dropped);
            if self
                .stop_boundary
                .is_some_and(|previous| previous != boundary)
            {
                return Err("Network Agent changed its frozen STOP boundary".into());
            }
            self.stop_boundary = Some(boundary);
        } else if self.stop_boundary.is_some() {
            return Err("Network Agent resumed events after its STOP boundary".into());
        }
        self.latest_boundary = reply.latest;
        self.last_dropped = reply.dropped;
        self.capture.end_monotonic_ns = reply.clock;
        self.capture.dropped_events = reply.dropped - self.dropped_baseline;
        for event in reply.events {
            if event.sequence <= self.cursor
                || event.sequence > reply.latest
                || event.clock > reply.clock
            {
                return Err(
                    "Network Agent event is outside its sequence or device-clock boundary".into(),
                );
            }
            self.capture.sequence_gaps = self
                .capture
                .sequence_gaps
                .saturating_add(event.sequence - self.cursor - 1);
            self.cursor = event.sequence;
            self.capture.event_count += 1;
            if self.capture.event_count > MAX_EVENTS + 20_500 {
                return Err("Network capture reached its bounded STOP-drain event budget".into());
            }
            if self.capture.event_count > MAX_EVENTS {
                self.capture.limit_reached = true;
                self.capture.discarded_events += 1;
                continue;
            }
            if event.clock < self.capture.start_monotonic_ns {
                self.capture.excluded_preboundary_events += 1;
                continue;
            }
            if event.kind == EventKind::Unknown {
                self.capture.unknown_events += 1;
                continue;
            }
            let next_index = self.calls.len() as u64 + 1;
            if !self.calls.contains_key(&event.call_id) && self.calls.len() >= MAX_REQUESTS {
                self.capture.limit_reached = true;
                self.capture.omitted_call_events += 1;
                continue;
            }
            let request = self
                .calls
                .entry(event.call_id)
                .or_insert_with(|| ObservedRequest {
                    entry: NetworkEntry {
                        index: next_index,
                        method: "OTHER",
                        status: 0,
                        duration_ms: None,
                        request_bytes: None,
                        response_bytes: None,
                        minimized_url: MINIMIZED_URL,
                    },
                    outcome: ObservedOutcome::InFlight,
                    start_ns: None,
                    end_ns: None,
                    protocol: None,
                    cancellation_requested: false,
                    phases_truncated: 0,
                    phases: Vec::new(),
                });
            if let Some(method) = event.method {
                request.entry.method = method;
            }
            if let Some(status) = event.status {
                request.entry.status = status;
            }
            if let Some(protocol) = event.protocol {
                request.protocol = Some(protocol);
            }
            match event.kind {
                EventKind::Start => {
                    if request.start_ns.replace(event.clock).is_some() {
                        return Err("Network Agent duplicated a request start".into());
                    }
                }
                EventKind::End | EventKind::Failed => {
                    if request.end_ns.replace(event.clock).is_some() {
                        return Err("Network Agent duplicated a request completion".into());
                    }
                    request.outcome = if event.kind == EventKind::End {
                        ObservedOutcome::Completed
                    } else if request.cancellation_requested {
                        ObservedOutcome::Cancelled
                    } else {
                        ObservedOutcome::Failed
                    };
                }
                EventKind::Cancelled => {
                    request.cancellation_requested = true;
                    // OkHttp callbacks can race: a socket failure may be
                    // emitted before the cancelling thread reports canceled.
                    // Completion remains completion if cancel came too late.
                    if request.outcome == ObservedOutcome::Failed {
                        request.outcome = ObservedOutcome::Cancelled;
                    }
                }
                EventKind::Phase(kind, start, failed) => {
                    if !start {
                        // Without raw endpoint IDs, overlapping attempts cannot
                        // be paired reliably. Leave ambiguous endpoints unknown
                        // rather than manufacture a LIFO timing measurement.
                        let open_count = request
                            .phases
                            .iter()
                            .filter(|phase| {
                                phase.kind == kind
                                    && phase.start_ns.is_some()
                                    && phase.end_ns.is_none()
                            })
                            .count();
                        let open = request.phases.iter_mut().find(|phase| {
                            phase.kind == kind
                                && phase.start_ns.is_some()
                                && phase.end_ns.is_none()
                                && open_count == 1
                        });
                        if let Some(phase) = open {
                            phase.end_ns = Some(event.clock);
                            phase.failed = failed;
                        } else if request.phases.len() < MAX_PHASES {
                            request.phases.push(ObservedPhase {
                                kind,
                                start_ns: None,
                                end_ns: Some(event.clock),
                                failed,
                            });
                        } else {
                            request.phases_truncated += 1;
                        }
                    } else if request.phases.len() < MAX_PHASES {
                        request.phases.push(ObservedPhase {
                            kind,
                            start_ns: Some(event.clock),
                            end_ns: None,
                            failed: false,
                        });
                    } else {
                        request.phases_truncated += 1;
                    }
                    if !start && let Some(count) = event.bytes {
                        let total = match kind {
                            ObservedPhaseKind::RequestBody => {
                                Some(&mut request.entry.request_bytes)
                            }
                            ObservedPhaseKind::ResponseBody => {
                                Some(&mut request.entry.response_bytes)
                            }
                            _ => None,
                        };
                        if let Some(total) = total {
                            *total = Some(
                                total
                                    .unwrap_or_default()
                                    .checked_add(count)
                                    .ok_or("Network Agent byte evidence overflowed")?,
                            );
                        }
                    }
                }
                EventKind::Other | EventKind::Unknown => {}
            }
            request.entry.duration_ms = request
                .start_ns
                .zip(request.end_ns)
                .and_then(|(start, end)| end.checked_sub(start))
                .map(|ns| ns as f64 / 1_000_000.);
        }
        if !reply.has_more {
            self.capture.sequence_gaps = self
                .capture
                .sequence_gaps
                .saturating_add(reply.latest - self.cursor);
            self.cursor = reply.latest;
        }
        Ok(())
    }
    fn snapshot(&self) -> Result<Arc<HarSession>, String> {
        let mut capture = self.capture.clone();
        capture.requests = self.calls.values().cloned().collect();
        capture
            .requests
            .sort_unstable_by_key(|request| request.entry.index);
        HarSession::from_observed(capture)
            .map(Arc::new)
            .map_err(|_| {
                "Network Agent evidence could not form a bounded minimized snapshot".into()
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn collector() -> Collector {
        Collector::new(
            123,
            Identity {
                boot_id: "01234567-89ab-cdef-0123-456789abcdef".into(),
                start_ticks: 10,
            },
            &Reply {
                kind: ReplyKind::Ready,
                pid: 123,
                latest: 0,
                dropped: 2,
                clock: 1_000_000,
                has_more: false,
                events: Vec::new(),
            },
        )
    }

    fn event(sequence: u64, clock: u64, kind: EventKind) -> Event {
        Event {
            sequence,
            clock,
            kind,
            call_id: "c-1".into(),
            method: Some("GET"),
            status: None,
            bytes: None,
            protocol: None,
        }
    }

    fn reply(events: Vec<Event>, latest: u64) -> Reply {
        Reply {
            kind: ReplyKind::Events,
            pid: 123,
            latest,
            dropped: 2,
            clock: 5_000_000,
            has_more: false,
            events,
        }
    }

    #[::core::prelude::v1::test]
    fn cancellation_callback_races_do_not_turn_failures_into_http_outcomes() {
        for (terminal, expected) in [
            (EventKind::Failed, ObservedOutcome::Cancelled),
            (EventKind::End, ObservedOutcome::Completed),
        ] {
            let mut collector = collector();
            collector
                .apply(reply(
                    vec![
                        event(1, 1_100_000, EventKind::Start),
                        event(2, 3_000_000, terminal),
                        event(3, 3_100_000, EventKind::Cancelled),
                    ],
                    3,
                ))
                .unwrap();
            let snapshot = collector.snapshot().unwrap();
            let request = snapshot.observed().unwrap().request(1).unwrap();
            assert_eq!(request.outcome, expected);
            assert!(request.cancellation_requested);
            assert_eq!(request.end_ns, Some(3_000_000));
        }
    }

    #[::core::prelude::v1::test]
    fn overlapping_phase_attempts_never_gain_invented_durations() {
        let mut collector = collector();
        collector
            .apply(reply(
                vec![
                    event(1, 1_100_000, EventKind::Start),
                    event(
                        2,
                        2_000_000,
                        EventKind::Phase(ObservedPhaseKind::Connect, true, false),
                    ),
                    event(
                        3,
                        2_100_000,
                        EventKind::Phase(ObservedPhaseKind::Connect, true, false),
                    ),
                    event(
                        4,
                        2_200_000,
                        EventKind::Phase(ObservedPhaseKind::Connect, false, false),
                    ),
                ],
                4,
            ))
            .unwrap();
        let snapshot = collector.snapshot().unwrap();
        let request = snapshot.observed().unwrap().request(1).unwrap();
        assert_eq!(request.phases.len(), 3);
        assert!(
            request
                .phases
                .iter()
                .all(|phase| phase.duration_ms().is_none())
        );
    }

    #[::core::prelude::v1::test]
    fn stop_drain_keeps_its_first_frozen_sequence_clock_and_loss_boundary() {
        let mut collector = collector();
        let mut first = reply(vec![event(1, 1_100_000, EventKind::Start)], 2);
        first.kind = ReplyKind::Stopping;
        first.has_more = true;
        collector.apply(first).unwrap();
        let mut last = reply(vec![event(2, 3_000_000, EventKind::End)], 2);
        last.kind = ReplyKind::Stopped;
        collector.apply(last).unwrap();
        let mut changed = reply(Vec::new(), 3);
        changed.kind = ReplyKind::Stopped;
        assert!(collector.apply(changed).is_err());
    }

    #[::core::prelude::v1::test]
    fn loss_counters_remain_separate_and_duplicate_events_fail_closed() {
        let mut collector = collector();
        let mut batch = reply(vec![event(2, 2_000_000, EventKind::Start)], 4);
        batch.dropped = 4;
        collector.apply(batch).unwrap();
        assert_eq!(collector.capture.dropped_events, 2);
        assert_eq!(collector.capture.sequence_gaps, 3);
        let mut duplicate = reply(vec![event(4, 3_000_000, EventKind::End)], 4);
        duplicate.dropped = 4;
        assert!(collector.apply(duplicate).is_err());
    }

    #[::core::prelude::v1::test]
    fn cancelled_mailbox_rejects_racing_snapshots_and_successes() {
        let control = LiveControl::default();
        control.cancel();
        control.publish(CaptureUpdate::Snapshot(
            LiveSnapshot::new(collector().snapshot().unwrap()).unwrap(),
        ));
        assert!(control.take_update().is_none());
        control.publish(CaptureUpdate::Finished(Ok(LiveSnapshot::new(
            collector().snapshot().unwrap(),
        )
        .unwrap())));
        assert!(matches!(
            control.take_update(),
            Some(CaptureUpdate::Finished(Err(_)))
        ));
    }

    #[::core::prelude::v1::test]
    fn transient_agent_strings_never_enter_the_observed_snapshot() {
        let raw = serde_json::json!({
            "sequence": 1, "monotonicNs": 2_000_000,
            "callId": "c-1", "kind": "callStart", "method": "private-method",
            "url": "https://private-url/?token=private-credential",
            "message": "private-diagnostic", "tlsVersion": "private-tls",
            "cipherSuite": "private-cipher", "instrumentationId": "private-factory",
            "protocol": "private-protocol", "connectionId": "private-connection",
        });
        let bytes = raw.to_string();
        let value = aps_json::parse_slice(bytes.as_bytes(), aps_json::Limits::default()).unwrap();
        let mut collector = collector();
        collector
            .apply(reply(vec![Event::parse(&value).unwrap()], 1))
            .unwrap();
        let snapshot = collector.snapshot().unwrap();
        let minimized = format!("{:?}", snapshot.observed().unwrap());
        assert!(!minimized.contains("private-"));
        assert_eq!(
            snapshot.observed().unwrap().requests[0].entry.method,
            "OTHER"
        );
    }
}
