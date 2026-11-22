//! Direct links to the official Perfetto UI. APS serves only explicitly
//! selected trace bytes, over loopback; it does not host UI assets or upload
//! traces. Each workspace owns a separate route on one shared listener.

use std::{
    collections::HashMap,
    fs::{self, File},
    io::{self, Read, Write},
    net::{Ipv4Addr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, Weak,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    thread::{self, JoinHandle},
    time::Duration,
};

const OFFICIAL_ORIGIN: &str = "https://ui.perfetto.dev";
const MAX_REQUEST_BYTES: usize = 8 * 1024;
const MAX_TRACE_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const TIMESTAMP_WINDOW_NANOS: i64 = 5_000_000;
const IO_TIMEOUT: Duration = Duration::from_secs(2);
const ACCEPT_POLL: Duration = Duration::from_millis(5);
// Perfetto explicitly allows this loopback port in its Content Security Policy
// and uses it for trace links in its upstream tools/open_trace_in_ui.
static HOSTED_VIEWER: ViewerHub = ViewerHub::new(9001);
static NEXT_TRACE_ID: AtomicU64 = AtomicU64::new(1);

type TraceRoutes = Mutex<HashMap<u64, PathBuf>>;

struct ViewerHub {
    port: u16,
    running: Mutex<Weak<RunningServer>>,
}

impl ViewerHub {
    const fn new(port: u16) -> Self {
        Self {
            port,
            running: Mutex::new(Weak::new()),
        }
    }

    fn server(&self) -> io::Result<Arc<RunningServer>> {
        let mut running = self
            .running
            .lock()
            .map_err(|_| io::Error::other("Local Perfetto listener lock is poisoned"))?;
        if let Some(server) = running.upgrade() {
            return Ok(server);
        }
        let server = Arc::new(RunningServer::start(self.port)?);
        *running = Arc::downgrade(&server);
        Ok(server)
    }
}

struct RunningServer {
    port: u16,
    routes: Arc<TraceRoutes>,
    shutdown: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl RunningServer {
    fn start(port: u16) -> io::Result<Self> {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, port))?;
        listener.set_nonblocking(true)?;
        let port = listener.local_addr()?.port();
        let routes = Arc::new(TraceRoutes::default());
        let shutdown = Arc::new(AtomicBool::new(false));
        let worker_routes = Arc::clone(&routes);
        let worker_shutdown = Arc::clone(&shutdown);
        let thread = thread::Builder::new()
            .name("aps-perfetto-trace".to_owned())
            .spawn(move || serve(listener, port, &worker_routes, &worker_shutdown))?;
        Ok(Self {
            port,
            routes,
            shutdown,
            thread: Some(thread),
        })
    }
}

impl Drop for RunningServer {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::Release);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

struct PublishedTrace {
    server: Arc<RunningServer>,
    id: u64,
}

impl Drop for PublishedTrace {
    fn drop(&mut self) {
        if let Ok(mut routes) = self.server.routes.lock() {
            routes.remove(&self.id);
        }
    }
}

/// A workspace-owned trace link. The shared port is released after the last
/// workspace closes its link; replaced links cannot resolve another trace.
#[derive(Default)]
pub(crate) struct ViewerServer {
    trace: Option<PublishedTrace>,
}

impl ViewerServer {
    pub(crate) fn open(
        &mut self,
        path: &Path,
        timestamp_nanos: Option<i64>,
        browser: impl FnOnce(&str),
    ) -> Result<(), String> {
        if std::env::var_os("APS_TRACE_NO_BROWSER").is_some() {
            return Ok(());
        }
        self.open_with_hub(&HOSTED_VIEWER, path, timestamp_nanos, browser)
    }

    fn open_with_hub(
        &mut self,
        hub: &ViewerHub,
        path: &Path,
        timestamp_nanos: Option<i64>,
        browser: impl FnOnce(&str),
    ) -> Result<(), String> {
        let path = validated_trace(path)?;
        let server = match hub.server() {
            Ok(server) => server,
            Err(_) => {
                browser(&format!("{OFFICIAL_ORIGIN}/"));
                return Err("Could not start the local trace handoff. In Perfetto UI, choose Open trace file and select the trace manually.".to_owned());
            }
        };
        let id = NEXT_TRACE_ID.fetch_add(1, Ordering::Relaxed);
        server
            .routes
            .lock()
            .map_err(|_| "Local Perfetto trace routes are unavailable".to_owned())?
            .insert(id, path);
        let url = viewer_url(server.port, id, timestamp_nanos);
        self.trace = Some(PublishedTrace { server, id });
        browser(&url);
        Ok(())
    }

    pub(crate) fn stop(&mut self) {
        self.trace.take();
    }
}

fn validated_trace(path: &Path) -> Result<PathBuf, String> {
    let metadata =
        fs::metadata(path).map_err(|_| "Trace file is missing or is not a regular file")?;
    if !metadata.is_file() {
        return Err("Trace file is missing or is not a regular file".to_owned());
    }
    if metadata.len() > MAX_TRACE_BYTES {
        return Err("Trace exceeds the viewer byte budget".to_owned());
    }
    path.canonicalize()
        .map_err(|_| "Cannot resolve the trace file".to_owned())
}

fn viewer_url(port: u16, id: u64, timestamp_nanos: Option<i64>) -> String {
    // The fragment holds a percent-encoded loopback URL, not a local path.
    let mut url =
        format!("{OFFICIAL_ORIGIN}/#!/?url=http%3A%2F%2F127.0.0.1%3A{port}%2Ftrace%2F{id}.pftrace");
    if let Some(timestamp) = timestamp_nanos {
        let start = timestamp.saturating_sub(TIMESTAMP_WINDOW_NANOS).max(0);
        let end = timestamp.saturating_add(TIMESTAMP_WINDOW_NANOS);
        if start < end {
            url.push_str(&format!("&visStart={start}&visEnd={end}"));
        }
    }
    url
}

fn serve(listener: TcpListener, port: u16, routes: &TraceRoutes, shutdown: &AtomicBool) {
    let host = format!("127.0.0.1:{port}");
    while !shutdown.load(Ordering::Acquire) {
        match listener.accept() {
            Ok((stream, _)) => {
                let _ = handle_connection(stream, &host, routes);
            }
            Err(_) => thread::sleep(ACCEPT_POLL),
        }
    }
}

fn handle_connection(mut stream: TcpStream, host: &str, routes: &TraceRoutes) -> io::Result<()> {
    stream.set_nonblocking(false)?;
    stream.set_read_timeout(Some(IO_TIMEOUT))?;
    stream.set_write_timeout(Some(IO_TIMEOUT))?;
    let Some(request) = read_request(&mut stream)? else {
        return write_status(&mut stream, "400 Bad Request");
    };
    let mut lines = request.lines();
    let mut request_line = lines.next().unwrap_or_default().split_whitespace();
    let method = request_line.next().unwrap_or_default();
    let target = request_line.next().unwrap_or_default();
    if method != "GET" && method != "OPTIONS" {
        return write_status(&mut stream, "405 Method Not Allowed");
    }
    let mut request_host = None;
    let mut origin = None;
    let mut preflight_method = None;
    for (name, value) in lines
        .take_while(|line| !line.is_empty())
        .filter_map(|line| line.split_once(':'))
    {
        let field = if name.eq_ignore_ascii_case("host") {
            &mut request_host
        } else if name.eq_ignore_ascii_case("origin") {
            &mut origin
        } else if name.eq_ignore_ascii_case("access-control-request-method") {
            &mut preflight_method
        } else {
            continue;
        };
        if field.replace(value.trim()).is_some() {
            return write_status(&mut stream, "400 Bad Request");
        }
    }
    if request_host != Some(host) || origin != Some(OFFICIAL_ORIGIN) {
        return write_status(&mut stream, "403 Forbidden");
    }
    let id = target
        .strip_prefix("/trace/")
        .and_then(|tail| tail.strip_suffix(".pftrace"))
        .and_then(|digits| digits.parse::<u64>().ok());
    let trace = {
        let Ok(routes) = routes.lock() else {
            return write_status(&mut stream, "500 Internal Server Error");
        };
        id.and_then(|id| routes.get(&id)).cloned()
    };
    let Some(trace) = trace else {
        return write_status(&mut stream, "404 Not Found");
    };
    if method == "OPTIONS" {
        if preflight_method != Some("GET") {
            return write_status(&mut stream, "405 Method Not Allowed");
        }
        // Older Chromium versions may preflight a public-to-loopback fetch.
        return write!(
            stream,
            "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: {OFFICIAL_ORIGIN}\r\nAccess-Control-Allow-Methods: GET\r\nAccess-Control-Allow-Private-Network: true\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        );
    }
    serve_trace(&mut stream, &trace)
}

fn read_request(stream: &mut TcpStream) -> io::Result<Option<String>> {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 1024];
    loop {
        let read = stream.read(&mut chunk)?;
        if read == 0 {
            break;
        }
        buffer.extend_from_slice(&chunk[..read]);
        if buffer.len() > MAX_REQUEST_BYTES {
            return Ok(None);
        }
        if has_request_terminator(&buffer) {
            break;
        }
    }
    if !has_request_terminator(&buffer) {
        return Ok(None);
    }
    Ok(String::from_utf8(buffer).ok())
}

fn has_request_terminator(buffer: &[u8]) -> bool {
    buffer.windows(4).any(|window| window == b"\r\n\r\n")
}

fn serve_trace(stream: &mut TcpStream, trace: &Path) -> io::Result<()> {
    let file = match File::open(trace) {
        Ok(file) => file,
        Err(_) => return write_status(stream, "404 Not Found"),
    };
    let length = match file.metadata() {
        Ok(metadata) if metadata.is_file() && metadata.len() <= MAX_TRACE_BYTES => metadata.len(),
        _ => return write_status(stream, "404 Not Found"),
    };
    write!(
        stream,
        "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {length}\r\nAccess-Control-Allow-Origin: {OFFICIAL_ORIGIN}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
    )?;
    io::copy(&mut file.take(length), stream)?;
    stream.flush()
}

fn write_status(stream: &mut TcpStream, status: &str) -> io::Result<()> {
    write!(
        stream,
        "HTTP/1.1 {status}\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    static NEXT_DIRECTORY: AtomicUsize = AtomicUsize::new(0);

    struct TestDirectory(PathBuf);
    impl TestDirectory {
        fn new(name: &str) -> Self {
            let path = std::env::temp_dir().join(format!(
                "aps-trace-viewer-{name}-{}-{}",
                std::process::id(),
                NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
            ));
            let _ = fs::remove_dir_all(&path);
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }
        fn path(&self) -> &Path {
            &self.0
        }
    }
    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn trace_file(directory: &TestDirectory, name: &str, bytes: &[u8]) -> PathBuf {
        let path = directory.path().join(name);
        fs::write(&path, bytes).unwrap();
        path
    }

    struct Response {
        status: u16,
        headers: Vec<(String, String)>,
        body: Vec<u8>,
    }

    impl Response {
        fn header(&self, name: &str) -> Option<&str> {
            self.headers
                .iter()
                .find(|(key, _)| key.eq_ignore_ascii_case(name))
                .map(|(_, value)| value.as_str())
        }
    }

    fn http_request(port: u16, method: &str, path: &str, host: &str, headers: &str) -> Response {
        let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        write!(
            stream,
            "{method} {path} HTTP/1.1\r\nHost: {host}\r\n{headers}Connection: close\r\n\r\n"
        )
        .unwrap();
        let mut bytes = Vec::new();
        stream.read_to_end(&mut bytes).unwrap();
        let split = bytes
            .windows(4)
            .position(|window| window == b"\r\n\r\n")
            .unwrap();
        let head = String::from_utf8(bytes[..split].to_vec()).unwrap();
        let body = bytes[split + 4..].to_vec();
        let mut lines = head.lines();
        let status = lines
            .next()
            .unwrap()
            .split_whitespace()
            .nth(1)
            .unwrap()
            .parse()
            .unwrap();
        let headers = lines
            .filter_map(|line| line.split_once(':'))
            .map(|(key, value)| (key.trim().to_owned(), value.trim().to_owned()))
            .collect();
        Response {
            status,
            headers,
            body,
        }
    }

    const HOSTED_HEADER: &str = "Origin: https://ui.perfetto.dev\r\n";

    fn open(
        viewer: &mut ViewerServer,
        hub: &ViewerHub,
        trace: &Path,
        timestamp: Option<i64>,
    ) -> (u16, String) {
        let mut url = String::new();
        viewer
            .open_with_hub(hub, trace, timestamp, |link| url = link.to_owned())
            .unwrap();
        let PublishedTrace { server, id } = viewer.trace.as_ref().unwrap();
        let port = server.port;
        let tail = timestamp
            .map(|_| "&visStart=1495000000&visEnd=1505000000")
            .unwrap_or_default();
        assert_eq!(
            url,
            format!(
                "https://ui.perfetto.dev/#!/?url=http%3A%2F%2F127.0.0.1%3A{port}%2Ftrace%2F{id}.pftrace{tail}"
            )
        );
        (port, format!("/trace/{id}.pftrace"))
    }

    fn hosted_get(port: u16, path: &str) -> Response {
        http_request(
            port,
            "GET",
            path,
            &format!("127.0.0.1:{port}"),
            HOSTED_HEADER,
        )
    }

    #[test]
    fn opens_the_official_ui_with_the_selected_trace_and_zoom() {
        let directory = TestDirectory::new("hosted");
        let bytes = b"\x00\x01binary\r\n\r\n<trace>";
        let trace = trace_file(&directory, "private capture.pftrace", bytes);
        let hub = ViewerHub::new(0);
        let mut viewer = ViewerServer::default();
        let (port, path) = open(&mut viewer, &hub, &trace, Some(1_500_000_000));
        let response = hosted_get(port, &path);
        assert_eq!(response.status, 200);
        assert_eq!(response.body, bytes);
        assert_eq!(
            response.header("content-type"),
            Some("application/octet-stream")
        );
        assert_eq!(
            response.header("access-control-allow-origin"),
            Some(OFFICIAL_ORIGIN)
        );
        assert_eq!(hosted_get(port, "/").status, 404);

        viewer.stop();
        viewer.stop();
        assert!(TcpStream::connect((Ipv4Addr::LOCALHOST, port)).is_err());
    }

    #[test]
    fn replaces_only_the_owning_workspace_link_on_the_shared_port() {
        let directory = TestDirectory::new("shared");
        let trace = trace_file(&directory, "first.pftrace", b"first");
        let frame = trace_file(&directory, "second.pftrace", b"second");
        let hub = ViewerHub::new(0);
        let mut trace_viewer = ViewerServer::default();
        let mut frame_viewer = ViewerServer::default();
        let (port, path) = open(&mut trace_viewer, &hub, &trace, None);
        let (frame_port, frame_path) = open(&mut frame_viewer, &hub, &frame, None);
        assert_eq!(port, frame_port);
        assert_eq!(hosted_get(port, &path).body, b"first");
        assert_eq!(hosted_get(port, &frame_path).body, b"second");

        let (_, replacement) = open(&mut trace_viewer, &hub, &trace, None);
        assert_ne!(path, replacement);
        assert_eq!(hosted_get(port, &path).status, 404);
        trace_viewer.stop();
        assert_eq!(hosted_get(port, &replacement).status, 404);
        assert_eq!(hosted_get(port, &frame_path).body, b"second");
    }

    #[test]
    fn refuses_other_origins_and_hosts_and_accepts_get_preflight() {
        let directory = TestDirectory::new("origin");
        let trace = trace_file(&directory, "trace.pftrace", b"trace");
        let hub = ViewerHub::new(0);
        let mut viewer = ViewerServer::default();
        let (port, path) = open(&mut viewer, &hub, &trace, None);
        let host = format!("127.0.0.1:{port}");
        for (host, headers) in [
            (host.as_str(), ""),
            (host.as_str(), "Origin: https://example.com\r\n"),
            ("example.com", HOSTED_HEADER),
        ] {
            let response = http_request(port, "GET", &path, host, headers);
            assert_eq!(response.status, 403);
            assert!(response.body.is_empty());
            assert_eq!(response.header("access-control-allow-origin"), None);
        }
        let response = http_request(
            port,
            "OPTIONS",
            &path,
            &host,
            "Origin: https://ui.perfetto.dev\r\nAccess-Control-Request-Method: GET\r\nAccess-Control-Request-Private-Network: true\r\n",
        );
        assert_eq!(response.status, 204);
        assert_eq!(
            response.header("access-control-allow-origin"),
            Some(OFFICIAL_ORIGIN)
        );
        assert_eq!(
            response.header("access-control-allow-private-network"),
            Some("true")
        );
        assert_eq!(hosted_get(port, "/../secret.pftrace").status, 404);
    }

    #[test]
    fn rejects_an_invalid_trace_and_opens_the_official_homepage_if_port_is_taken() {
        let directory = TestDirectory::new("conflict");
        let trace = trace_file(&directory, "trace.pftrace", b"trace");
        let mut viewer = ViewerServer::default();
        assert!(
            viewer
                .open_with_hub(&ViewerHub::new(0), directory.path(), None, |_| panic!(
                    "invalid trace"
                ))
                .is_err()
        );
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let hub = ViewerHub::new(listener.local_addr().unwrap().port());
        let mut opened = String::new();
        let error = viewer
            .open_with_hub(&hub, &trace, None, |url| opened = url.to_owned())
            .unwrap_err();
        assert!(error.contains("Open trace file"));
        assert_eq!(opened, "https://ui.perfetto.dev/");
        assert!(viewer.trace.is_none());
    }
}
