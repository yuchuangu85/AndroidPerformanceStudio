//! Minimal local Perfetto UI server for the system browser.
//!
//! Compose parity with PerfettoUiServer, implemented with std only: bind
//! 127.0.0.1, serve the selected trace at /trace with CORS octet-stream
//! headers, serve a static UI directory when it has index.html, otherwise
//! render a self-contained page that explains the trace URL and how to load it
//! in ui.perfetto.dev. The trace bytes never leave the machine: there is no
//! upload, proxy or cloud connection. Request parsing and IO are bounded.

use std::{
    fs::{self, File},
    io::{self, Read, Write},
    net::{Ipv4Addr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    process::Stdio,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread::{self, JoinHandle},
    time::Duration,
};

const MAX_REQUEST_BYTES: usize = 8 * 1024;
const MAX_TRACE_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const MAX_ASSET_BYTES: u64 = 512 * 1024 * 1024;
const TIMESTAMP_WINDOW_NANOS: i64 = 5_000_000;
const IO_TIMEOUT: Duration = Duration::from_secs(2);
const ACCEPT_POLL: Duration = Duration::from_millis(5);
const BROWSER_ENVIRONMENT: &str = "APS_TRACE_NO_BROWSER";

#[derive(Clone, Debug, Default)]
struct ViewerState {
    trace: Option<PathBuf>,
    assets: Option<PathBuf>,
    zoom: Option<(i64, i64)>,
}

struct RunningServer {
    shutdown: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
    bound_port: u16,
}

/// One local HTTP server reused for every trace opened by the workspace.
pub(crate) struct ViewerServer {
    port: u16,
    running: Option<RunningServer>,
    state: Arc<Mutex<ViewerState>>,
    browser: bool,
}

impl ViewerServer {
    pub(crate) fn new(port: u16) -> Self {
        Self {
            port,
            running: None,
            state: Arc::new(Mutex::new(ViewerState::default())),
            browser: true,
        }
    }

    pub(crate) fn is_running(&self) -> bool {
        self.running.is_some()
    }

    /// Bound loopback port, or the configured port before the server starts.
    pub(crate) fn port(&self) -> u16 {
        self.running
            .as_ref()
            .map(|running| running.bound_port)
            .unwrap_or(self.port)
    }

    /// Selects the trace and view window, starts the server when needed and
    /// opens the system browser unless APS_TRACE_NO_BROWSER is present.
    pub(crate) fn open(
        &mut self,
        trace: &Path,
        timestamp_nanos: Option<i64>,
        ui_assets: Option<&Path>,
        start_nanos: Option<i64>,
        end_nanos: Option<i64>,
    ) -> Result<(), String> {
        let trace = validated_trace(trace)?;
        let assets = ui_assets.and_then(validated_assets);
        let zoom = zoom_window(timestamp_nanos, start_nanos, end_nanos);
        {
            let mut state = self
                .state
                .lock()
                .map_err(|_| "Trace viewer state lock is poisoned".to_owned())?;
            state.trace = Some(trace);
            state.assets = assets.clone();
            state.zoom = zoom;
        }
        if self.running.is_none() {
            self.start()?;
        }
        let url = viewer_url(self.port(), assets.is_some(), zoom);
        if self.browser && std::env::var_os(BROWSER_ENVIRONMENT).is_none() {
            launch_browser(&url)?;
        }
        Ok(())
    }

    /// Idempotent shutdown; the port is released for a future open.
    pub(crate) fn stop(&mut self) {
        if let Some(mut running) = self.running.take() {
            running.shutdown.store(true, Ordering::Release);
            if let Some(thread) = running.thread.take() {
                let _ = thread.join();
            }
        }
        if let Ok(mut state) = self.state.lock() {
            *state = ViewerState::default();
        }
    }

    fn start(&mut self) -> Result<(), String> {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, self.port))
            .map_err(|_| "Cannot bind the local Perfetto viewer port".to_owned())?;
        listener
            .set_nonblocking(true)
            .map_err(|_| "Cannot configure the local Perfetto viewer port".to_owned())?;
        let bound_port = listener
            .local_addr()
            .map_err(|_| "Cannot read the local Perfetto viewer port".to_owned())?
            .port();
        let shutdown = Arc::new(AtomicBool::new(false));
        let state = Arc::clone(&self.state);
        let thread_shutdown = Arc::clone(&shutdown);
        let thread = thread::Builder::new()
            .name("aps-trace-viewer".to_owned())
            .spawn(move || serve(listener, state, thread_shutdown))
            .map_err(|_| "Cannot start the local Perfetto viewer".to_owned())?;
        self.running = Some(RunningServer {
            shutdown,
            thread: Some(thread),
            bound_port,
        });
        Ok(())
    }
}

impl Drop for ViewerServer {
    fn drop(&mut self) {
        self.stop();
    }
}

fn validated_trace(trace: &Path) -> Result<PathBuf, String> {
    let metadata = fs::metadata(trace)
        .map_err(|_| "Trace file is missing or is not a regular file".to_owned())?;
    if !metadata.is_file() {
        return Err("Trace file is missing or is not a regular file".to_owned());
    }
    if metadata.len() > MAX_TRACE_BYTES {
        return Err("Trace exceeds the viewer byte budget".to_owned());
    }
    trace
        .canonicalize()
        .map_err(|_| "Cannot resolve the trace file".to_owned())
}

fn validated_assets(path: &Path) -> Option<PathBuf> {
    if path.is_dir() && path.join("index.html").is_file() {
        path.canonicalize().ok()
    } else {
        None
    }
}

fn zoom_window(
    timestamp_nanos: Option<i64>,
    start_nanos: Option<i64>,
    end_nanos: Option<i64>,
) -> Option<(i64, i64)> {
    if let (Some(start), Some(end)) = (start_nanos, end_nanos) {
        if start >= 0 && start < end {
            return Some((start, end));
        }
    }
    timestamp_nanos.map(|timestamp| {
        (
            timestamp.saturating_sub(TIMESTAMP_WINDOW_NANOS).max(0),
            timestamp.saturating_add(TIMESTAMP_WINDOW_NANOS),
        )
    })
}

fn viewer_url(port: u16, has_assets: bool, zoom: Option<(i64, i64)>) -> String {
    if has_assets {
        let zoom_parameters = zoom
            .map(|(start, end)| format!("&visStart={start}&visEnd={end}"))
            .unwrap_or_default();
        format!("http://127.0.0.1:{port}/#!/?url=http://127.0.0.1:{port}/trace{zoom_parameters}")
    } else {
        format!("http://127.0.0.1:{port}/")
    }
}

fn launch_browser(url: &str) -> Result<(), String> {
    let mut command = match std::env::consts::OS {
        "macos" => {
            let mut command = std::process::Command::new("open");
            command.arg(url);
            command
        }
        "linux" => {
            let mut command = std::process::Command::new("xdg-open");
            command.arg(url);
            command
        }
        "windows" => {
            let mut command = std::process::Command::new("cmd");
            command.args(["/C", "start", "", url]);
            command
        }
        _ => return Err("No system browser is available to open the local Perfetto UI".to_owned()),
    };
    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    command
        .spawn()
        .map(|_| ())
        .map_err(|_| "Failed to open the local Perfetto UI in the system browser".to_owned())
}

fn serve(listener: TcpListener, state: Arc<Mutex<ViewerState>>, shutdown: Arc<AtomicBool>) {
    while !shutdown.load(Ordering::Acquire) {
        match listener.accept() {
            Ok((stream, _)) => {
                let _ = handle_connection(stream, &state);
            }
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => thread::sleep(ACCEPT_POLL),
            Err(_) => thread::sleep(ACCEPT_POLL),
        }
    }
}

fn handle_connection(mut stream: TcpStream, state: &Arc<Mutex<ViewerState>>) -> io::Result<()> {
    stream.set_nonblocking(false)?;
    stream.set_read_timeout(Some(IO_TIMEOUT))?;
    stream.set_write_timeout(Some(IO_TIMEOUT))?;
    let Some(request) = read_request(&mut stream)? else {
        return write_status(&mut stream, "400 Bad Request");
    };
    let request_line = request.lines().next().unwrap_or_default();
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or_default();
    let target = parts.next().unwrap_or_default();
    if method != "GET" {
        return write_status(&mut stream, "405 Method Not Allowed");
    }
    let path = target.split_once('?').map_or(target, |(path, _)| path);
    if !valid_request_path(path) {
        return write_status(&mut stream, "400 Bad Request");
    }
    if path == "/trace" {
        return serve_trace(&mut stream, state);
    }
    let (assets, zoom) = {
        let Ok(state) = state.lock() else {
            return write_status(&mut stream, "500 Internal Server Error");
        };
        (state.assets.clone(), state.zoom)
    };
    match assets {
        Some(root) => match resolve_asset(&root, path) {
            Some(file) => serve_file(&mut stream, &file),
            None => write_status(&mut stream, "404 Not Found"),
        },
        None if path == "/" => {
            let page = embed_page(port_of(&stream), zoom);
            write_response(
                &mut stream,
                "200 OK",
                "text/html; charset=utf-8",
                page.as_bytes(),
            )
        }
        None => write_status(&mut stream, "404 Not Found"),
    }
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
        if has_request_terminator(&buffer) {
            break;
        }
        if buffer.len() > MAX_REQUEST_BYTES {
            return Ok(None);
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

fn valid_request_path(path: &str) -> bool {
    path.starts_with('/')
        && !path.contains("..")
        && !path.contains('\\')
        && !path.contains('%')
        && !path.chars().any(char::is_control)
}

fn port_of(stream: &TcpStream) -> u16 {
    stream
        .local_addr()
        .map(|address| address.port())
        .unwrap_or(0)
}

fn serve_trace(stream: &mut TcpStream, state: &Arc<Mutex<ViewerState>>) -> io::Result<()> {
    let trace = {
        let Ok(state) = state.lock() else {
            return write_status(stream, "500 Internal Server Error");
        };
        state.trace.clone()
    };
    let Some(trace) = trace else {
        return write_status(stream, "404 Not Found");
    };
    let file = match File::open(&trace) {
        Ok(file) => file,
        Err(_) => return write_status(stream, "404 Not Found"),
    };
    let length = match file.metadata() {
        Ok(metadata) if metadata.is_file() && metadata.len() <= MAX_TRACE_BYTES => metadata.len(),
        _ => return write_status(stream, "404 Not Found"),
    };
    write!(
        stream,
        "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {length}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
    )?;
    let mut body = file.take(length);
    io::copy(&mut body, stream)?;
    stream.flush()
}

fn resolve_asset(root: &Path, path: &str) -> Option<PathBuf> {
    let relative = path.trim_start_matches('/');
    let relative = if relative.is_empty() {
        "index.html"
    } else {
        relative
    };
    let canonical_root = root.canonicalize().ok()?;
    let candidate = root.join(relative).canonicalize().ok()?;
    if !candidate.starts_with(&canonical_root) || !candidate.is_file() {
        return None;
    }
    Some(candidate)
}

fn serve_file(stream: &mut TcpStream, file: &Path) -> io::Result<()> {
    let handle = match File::open(file) {
        Ok(handle) => handle,
        Err(_) => return write_status(stream, "404 Not Found"),
    };
    let length = match handle.metadata() {
        Ok(metadata) if metadata.is_file() && metadata.len() <= MAX_ASSET_BYTES => metadata.len(),
        _ => return write_status(stream, "404 Not Found"),
    };
    let content_type = content_type(file);
    write!(
        stream,
        "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {length}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
    )?;
    let mut body = handle.take(length);
    io::copy(&mut body, stream)?;
    stream.flush()
}

fn content_type(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|extension| extension.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase()
        .as_str()
    {
        "html" | "htm" => "text/html; charset=utf-8",
        "js" | "mjs" => "text/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "json" | "map" => "application/json",
        "wasm" => "application/wasm",
        "png" => "image/png",
        "svg" => "image/svg+xml",
        "ico" => "image/x-icon",
        "txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}

fn write_status(stream: &mut TcpStream, status: &str) -> io::Result<()> {
    write_response(
        stream,
        status,
        "text/plain; charset=utf-8",
        status.as_bytes(),
    )
}

fn write_response(
    stream: &mut TcpStream,
    status: &str,
    content_type: &str,
    body: &[u8],
) -> io::Result<()> {
    write!(
        stream,
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        body.len()
    )?;
    stream.write_all(body)?;
    stream.flush()
}

fn embed_page(port: u16, zoom: Option<(i64, i64)>) -> String {
    let trace_url = format!("http://127.0.0.1:{port}/trace");
    let zoom_note = zoom
        .map(|(start, end)| {
            format!(
                "<p>Suggested view window: {:.6} s to {:.6} s.</p>",
                start as f64 / 1_000_000_000.0,
                end as f64 / 1_000_000_000.0
            )
        })
        .unwrap_or_default();
    format!(
        "<!DOCTYPE html>\n\
         <html><head><meta charset=\"utf-8\"><title>Perfetto Trace</title>\n\
         <style>body{{margin:0;padding:40px;background:#1f2430;color:#d7dae0;font-family:-apple-system,\"Segoe UI\",sans-serif}}\
         a{{color:#61afef}}code{{background:#2b3240;padding:2px 6px;border-radius:4px}}\
         li{{margin:8px 0}}</style></head><body>\n\
         <h1>Perfetto Trace Viewer</h1>\n\
         <p>The trace is served locally at <a href=\"{trace_url}\">{trace_url}</a>.</p>\n\
         <p>To inspect it, open the Perfetto UI at \
         <a href=\"https://ui.perfetto.dev\" target=\"_blank\" rel=\"noreferrer\">ui.perfetto.dev</a>, \
         choose <b>Open trace file</b> then <b>Open URL</b>, and enter:</p>\n\
         <p><code>{trace_url}</code></p>\n\
         <ul><li>This page runs on loopback only; the trace is not uploaded anywhere.</li>\
         <li>Keep the Trace Analyzer open while the Perfetto UI reads the trace.</li></ul>\n\
         {zoom_note}\n\
         </body></html>"
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::sync::atomic::{AtomicUsize, Ordering};

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

    fn muted(port: u16) -> ViewerServer {
        let mut server = ViewerServer::new(port);
        server.browser = false;
        server
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

    fn http_get(port: u16, path: &str) -> Response {
        let mut stream = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        write!(
            stream,
            "GET {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
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

    #[test]
    fn serves_exact_trace_bytes_with_cors_and_stops_idempotently() {
        let directory = TestDirectory::new("trace");
        let bytes = b"\x00\x01binary\r\n\r\n<trace>".to_vec();
        let trace = trace_file(&directory, "capture.pftrace", &bytes);
        let mut server = muted(0);

        server.open(&trace, None, None, None, None).unwrap();
        assert!(server.is_running());
        let port = server.port();
        assert_ne!(port, 0);

        let response = http_get(port, "/trace");
        assert_eq!(response.status, 200);
        assert_eq!(response.body, bytes);
        assert_eq!(
            response.header("content-type"),
            Some("application/octet-stream")
        );
        assert_eq!(response.header("access-control-allow-origin"), Some("*"));

        server.stop();
        assert!(!server.is_running());
        server.stop();
        assert!(!server.is_running());
    }

    #[test]
    fn opening_another_trace_reuses_the_running_server() {
        let directory = TestDirectory::new("reuse");
        let first = trace_file(&directory, "first.pftrace", b"first");
        let second = trace_file(&directory, "second.pftrace", b"second");
        let mut server = muted(0);
        server.open(&first, None, None, None, None).unwrap();
        let port = server.port();
        server.open(&second, None, None, None, None).unwrap();
        assert!(server.is_running());
        assert_eq!(server.port(), port);
        assert_eq!(http_get(port, "/trace").body, b"second".to_vec());
        server.stop();
    }

    #[test]
    fn fallback_page_explains_the_trace_url_and_zoom_window() {
        let directory = TestDirectory::new("fallback");
        let trace = trace_file(&directory, "capture.pftrace", b"trace");
        let mut server = muted(0);
        server
            .open(&trace, Some(1_500_000_000), None, None, None)
            .unwrap();
        let page = http_get(server.port(), "/");
        assert_eq!(page.status, 200);
        let text = String::from_utf8(page.body).unwrap();
        assert!(text.contains("/trace"));
        assert!(text.contains("ui.perfetto.dev"));
        assert!(text.contains("Suggested view window: 1.495000 s to 1.505000 s."));
        assert!(text.contains("not uploaded anywhere"));

        let (start, end) = zoom_window(Some(999), Some(10), Some(20)).unwrap();
        assert_eq!((start, end), (10, 20));
        assert_eq!(zoom_window(Some(0), None, None), Some((0, 5_000_000)));
        assert_eq!(zoom_window(None, None, None), None);
        server.stop();
    }

    #[test]
    fn serves_ui_assets_and_rejects_traversal_or_missing_files() {
        let directory = TestDirectory::new("assets");
        let trace = trace_file(&directory, "capture.pftrace", b"trace");
        let assets = directory.path().join("ui");
        fs::create_dir_all(assets.join("static")).unwrap();
        fs::write(
            assets.join("index.html"),
            b"<!doctype html><title>ui</title>",
        )
        .unwrap();
        fs::write(assets.join("static").join("app.js"), b"console.log(1)").unwrap();
        fs::write(directory.path().join("secret.txt"), b"secret").unwrap();
        let mut server = muted(0);
        server
            .open(&trace, None, Some(&assets), None, None)
            .unwrap();
        let port = server.port();

        let index = http_get(port, "/");
        assert_eq!(index.status, 200);
        assert_eq!(index.body, b"<!doctype html><title>ui</title>".to_vec());
        assert_eq!(
            index.header("content-type"),
            Some("text/html; charset=utf-8")
        );
        let script = http_get(port, "/static/app.js");
        assert_eq!(script.status, 200);
        assert_eq!(script.body, b"console.log(1)".to_vec());
        assert_eq!(
            script.header("content-type"),
            Some("text/javascript; charset=utf-8")
        );
        assert_eq!(http_get(port, "/missing.js").status, 404);
        assert_eq!(http_get(port, "/../secret.txt").status, 400);
        assert_eq!(
            http_get(port, "/").body,
            b"<!doctype html><title>ui</title>"
        );
        server.stop();
    }

    #[test]
    fn invalid_inputs_and_port_conflicts_fail_closed() {
        let directory = TestDirectory::new("invalid");
        let trace = trace_file(&directory, "capture.pftrace", b"trace");
        let mut server = muted(0);
        assert!(
            server
                .open(
                    &directory.path().join("missing.pftrace"),
                    None,
                    None,
                    None,
                    None
                )
                .is_err()
        );
        assert!(!server.is_running());
        assert!(
            server
                .open(directory.path(), None, None, None, None)
                .is_err()
        );
        assert!(!server.is_running());

        let occupied = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = occupied.local_addr().unwrap().port();
        let mut conflict = muted(port);
        assert!(conflict.open(&trace, None, None, None, None).is_err());
        assert!(!conflict.is_running());
    }

    #[test]
    fn open_is_muted_for_tests_without_launching_a_browser() {
        let directory = TestDirectory::new("muted");
        let trace = trace_file(&directory, "capture.pftrace", b"trace");
        let mut server = muted(0);
        assert!(server.open(&trace, None, None, None, None).is_ok());
        assert!(server.is_running());
        server.stop();
    }
}
