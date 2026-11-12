//! Private, bounded loopback hosting for the bundled Firefox Profiler.
//! Profile bytes are already gzip-encoded by the caller and are never uploaded.

#[path = "firefox_viewer/bridge.rs"]
mod bridge;
use bridge::QueryBridge;
pub(crate) use bridge::QueryTicket;

use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Write},
    net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

pub(crate) const OFFICIAL_URL: &str = "https://profiler.firefox.com/";

const DIST_DIRECTORY: &str = "third_party/firefox-profiler/dist";
const INDEX_FILE: &str = "index.html";
const PROFILE_FILE: &str = "profile.json.gz";
const MAX_REQUEST_BYTES: usize = 16 * 1024;
const MAX_BODY_BYTES: u64 = 64 * 1024 * 1024;
const WORKERS: usize = 4;
const POLL_INTERVAL: Duration = Duration::from_millis(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(3);
const RESPONSE_TIMEOUT: Duration = Duration::from_secs(30);
const QUERY_ADAPTER: &str =
    include_str!("../../../../integrations/firefox-profiler/aps-query-api.js");
// The bundle uses external module scripts and inline styles, not inline scripts.
const CONTENT_SECURITY_POLICY: &str = "default-src 'self' data: blob:; connect-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; worker-src 'self' blob:; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'none'";

pub(crate) struct FirefoxViewer {
    server: Option<ServerHandle>,
}

impl FirefoxViewer {
    pub(crate) fn new() -> Self {
        Self { server: None }
    }

    /// Atomically publishes an app-owned gzip file and returns a local viewer
    /// URL. Opening the browser is the caller's responsibility.
    pub(crate) fn open(&mut self, profile: &[u8], storage_root: &Path) -> Result<String, String> {
        let root = find_dist_root()
            .ok_or_else(|| "bundled Firefox Profiler dist/index.html was not found".to_owned())?;
        self.open_with_root(profile, storage_root, &root)
    }

    fn open_with_root(
        &mut self,
        profile: &[u8],
        storage_root: &Path,
        root: &Path,
    ) -> Result<String, String> {
        if profile.is_empty() || profile.len() as u64 > MAX_BODY_BYTES {
            return Err("Firefox profile must contain between 1 byte and 64 MiB".to_owned());
        }
        let root = validate_dist_root(root)?;
        let directory = private_directory(storage_root)?;
        if directory.starts_with(&root) {
            return Err("Firefox profile storage must be outside the static asset tree".to_owned());
        }
        let profile_path = write_profile(&directory, profile)?;
        let reuse = self.server.as_ref().is_some_and(|server| {
            server.state.root == root && server.workers.iter().all(|worker| !worker.is_finished())
        });
        if reuse {
            let replacement = QueryBridge::new()?;
            let state = &self.server.as_ref().unwrap().state;
            let mut bridge = state
                .bridge
                .lock()
                .map_err(|_| "Firefox Profiler bridge is unavailable".to_owned())?;
            bridge.close();
            *bridge = replacement;
            let mut current = state
                .profile_path
                .lock()
                .map_err(|_| "Firefox Profiler server state is unavailable".to_owned())?;
            let previous = std::mem::replace(&mut *current, profile_path);
            let _ = fs::remove_file(previous);
        } else {
            // Build the replacement before dropping a working listener.
            let server = match start_server(root, profile_path.clone()) {
                Ok(server) => server,
                Err(error) => {
                    let _ = fs::remove_file(profile_path);
                    return Err(error);
                }
            };
            self.stop();
            self.server = Some(server);
        }
        Ok(self.server.as_ref().unwrap().view_url())
    }

    pub(crate) fn stop(&mut self) {
        self.server.take();
    }

    /// Enqueue one bounded read of the live browser view. The browser performs
    /// no provider requests, and replies only to queries issued by this host.
    pub(crate) fn query(
        &self,
        method: &str,
        params: serde_json::Value,
        expected_revision: Option<u64>,
    ) -> Result<QueryTicket, String> {
        let server = self.server.as_ref().ok_or("E_BROWSER_NOT_OPEN")?;
        server
            .state
            .bridge
            .lock()
            .map_err(|_| "E_BRIDGE_UNAVAILABLE")?
            .submit(method, params, expected_revision)
    }
}

impl Drop for FirefoxViewer {
    fn drop(&mut self) {
        self.stop();
    }
}

struct ServerState {
    root: PathBuf,
    address: SocketAddr,
    profile_route: String,
    profile_path: Mutex<PathBuf>,
    bridge: Mutex<QueryBridge>,
    stop: AtomicBool,
}

struct ServerHandle {
    state: Arc<ServerState>,
    workers: Vec<JoinHandle<()>>,
}

impl ServerHandle {
    fn view_url(&self) -> String {
        let origin = format!("http://{}", self.state.address);
        let profile_url = format!("{origin}{}", self.state.profile_route);
        format!(
            "{origin}/from-url/{}/calltree/",
            percent_encode(&profile_url)
        )
    }
}

impl Drop for ServerHandle {
    fn drop(&mut self) {
        if let Ok(bridge) = self.state.bridge.lock() {
            bridge.close();
        }
        self.state.stop.store(true, Ordering::Release);
        for worker in self.workers.drain(..) {
            let _ = worker.join();
        }
        if let Ok(path) = self.state.profile_path.lock() {
            let _ = fs::remove_file(&*path);
        }
    }
}

fn start_server(root: PathBuf, profile_path: PathBuf) -> Result<ServerHandle, String> {
    let root = validate_dist_root(&root)?;
    let token = random_token()?;
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .map_err(|error| format!("cannot bind Firefox Profiler loopback server: {error}"))?;
    listener
        .set_nonblocking(true)
        .map_err(|error| format!("cannot configure Firefox Profiler listener: {error}"))?;
    let address = listener
        .local_addr()
        .map_err(|error| format!("cannot read Firefox Profiler loopback address: {error}"))?;
    let mut server = ServerHandle {
        state: Arc::new(ServerState {
            root,
            address,
            profile_route: format!("/aps-profile-{token}.json.gz"),
            profile_path: Mutex::new(profile_path),
            bridge: Mutex::new(QueryBridge::new()?),
            stop: AtomicBool::new(false),
        }),
        workers: Vec::with_capacity(WORKERS),
    };
    // A fixed pool bounds threads and active requests, including slow clients.
    // Partial startup failure drops the handle and joins all started workers.
    for number in 0..WORKERS {
        let listener = listener
            .try_clone()
            .map_err(|error| format!("cannot clone Firefox Profiler listener: {error}"))?;
        let state = Arc::clone(&server.state);
        server.workers.push(
            thread::Builder::new()
                .name(format!("aps-firefox-viewer-{number}"))
                .spawn(move || serve(listener, state))
                .map_err(|error| format!("cannot start Firefox Profiler server: {error}"))?,
        );
    }
    Ok(server)
}

fn serve(listener: TcpListener, state: Arc<ServerState>) {
    while !state.stop.load(Ordering::Acquire) {
        match listener.accept() {
            Ok((stream, peer)) if peer.ip().is_loopback() => {
                if stream.set_nonblocking(true).is_ok() {
                    let mut connection = Connection {
                        stream,
                        stop: &state.stop,
                        deadline: Instant::now() + REQUEST_TIMEOUT,
                        buffered_body: Vec::new(),
                    };
                    let _ = handle_connection(&mut connection, &state);
                }
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => thread::sleep(POLL_INTERVAL),
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(_) => break,
        }
    }
}

struct Connection<'a> {
    stream: TcpStream,
    stop: &'a AtomicBool,
    deadline: Instant,
    buffered_body: Vec<u8>,
}

impl Connection<'_> {
    fn check_deadline(&self) -> io::Result<()> {
        if self.stop.load(Ordering::Acquire) || Instant::now() >= self.deadline {
            Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "viewer request stopped or timed out",
            ))
        } else {
            Ok(())
        }
    }

    fn read_headers(&mut self) -> io::Result<Vec<u8>> {
        let mut bytes = vec![0; MAX_REQUEST_BYTES];
        let mut used = 0;
        loop {
            self.check_deadline()?;
            match self.stream.read(&mut bytes[used..]) {
                Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
                Ok(read) => used += read,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    thread::sleep(POLL_INTERVAL);
                    continue;
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error),
            }
            if let Some(end) = bytes[..used]
                .windows(4)
                .position(|part| part == b"\r\n\r\n")
            {
                self.buffered_body = bytes[end + 4..used].to_vec();
                bytes.truncate(end);
                return Ok(bytes);
            }
            if used == MAX_REQUEST_BYTES {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "request headers too large",
                ));
            }
        }
    }

    fn read_body(&mut self, length: usize) -> io::Result<Vec<u8>> {
        if self.buffered_body.len() > length {
            return Err(io::ErrorKind::InvalidData.into());
        }
        let mut read = self.buffered_body.len();
        let mut body = std::mem::take(&mut self.buffered_body);
        body.resize(length, 0);
        while read < length {
            self.check_deadline()?;
            match self.stream.read(&mut body[read..]) {
                Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
                Ok(count) => read += count,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    thread::sleep(POLL_INTERVAL)
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
                Err(error) => return Err(error),
            }
        }
        Ok(body)
    }

    fn write_all(&mut self, mut bytes: &[u8]) -> io::Result<()> {
        while !bytes.is_empty() {
            self.check_deadline()?;
            match self.stream.write(bytes) {
                Ok(0) => return Err(io::ErrorKind::WriteZero.into()),
                Ok(written) => bytes = &bytes[written..],
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    thread::sleep(POLL_INTERVAL)
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
                Err(error) => return Err(error),
            }
        }
        Ok(())
    }
}

struct Request<'a> {
    method: &'a str,
    target: &'a str,
    authority: &'a str,
    origin: Option<&'a str>,
    token: Option<&'a str>,
    document: Option<&'a str>,
    content_type: Option<&'a str>,
    content_length: Option<usize>,
}

fn parse_request(bytes: &[u8], port: u16) -> Result<Request<'_>, &'static str> {
    let header = std::str::from_utf8(bytes).map_err(|_| "400 Bad Request")?;
    let mut lines = header.split("\r\n");
    let mut parts = lines.next().ok_or("400 Bad Request")?.split(' ');
    let method = parts.next().ok_or("400 Bad Request")?;
    let target = parts.next().ok_or("400 Bad Request")?;
    let version = parts.next().ok_or("400 Bad Request")?;
    if method.is_empty()
        || target.is_empty()
        || parts.next().is_some()
        || !matches!(version, "HTTP/1.0" | "HTTP/1.1")
        || header
            .bytes()
            .any(|byte| byte.is_ascii_control() && !matches!(byte, b'\r' | b'\n' | b'\t'))
    {
        return Err("400 Bad Request");
    }
    let mut authority = None;
    let mut origin = None;
    let mut token = None;
    let mut document = None;
    let mut content_type = None;
    let mut content_length = None;
    for line in lines {
        let (name, value) = line.split_once(':').ok_or("400 Bad Request")?;
        if name.is_empty()
            || !name
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
            || value.contains(['\r', '\n'])
        {
            return Err("400 Bad Request");
        }
        let value = value.trim_matches([' ', '\t']);
        if name.eq_ignore_ascii_case("host") {
            if authority.replace(value).is_some() {
                return Err("400 Bad Request");
            }
        } else if name.eq_ignore_ascii_case("origin") {
            if origin.replace(value).is_some() {
                return Err("400 Bad Request");
            }
        } else if name.eq_ignore_ascii_case("x-aps-query-token") {
            if token.replace(value).is_some() {
                return Err("400 Bad Request");
            }
        } else if name.eq_ignore_ascii_case("x-aps-query-document") {
            if document.replace(value).is_some() {
                return Err("400 Bad Request");
            }
        } else if name.eq_ignore_ascii_case("content-type") {
            if content_type.replace(value).is_some() {
                return Err("400 Bad Request");
            }
        } else if name.eq_ignore_ascii_case("content-length") {
            if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
                return Err("400 Bad Request");
            }
            let length = value
                .parse::<usize>()
                .map_err(|_| "413 Content Too Large")?;
            if content_length.replace(length).is_some() {
                return Err("400 Bad Request");
            }
        } else if name.eq_ignore_ascii_case("transfer-encoding") {
            return Err("400 Bad Request");
        }
    }
    let authority = authority.ok_or("403 Forbidden")?;
    if !authority.eq_ignore_ascii_case(&format!("127.0.0.1:{port}"))
        && !authority.eq_ignore_ascii_case(&format!("localhost:{port}"))
    {
        return Err("403 Forbidden");
    }
    if origin.is_some_and(|origin| !origin.eq_ignore_ascii_case(&format!("http://{authority}"))) {
        return Err("403 Forbidden");
    }
    Ok(Request {
        method,
        target,
        authority,
        origin,
        token,
        document,
        content_type,
        content_length,
    })
}

fn handle_connection(connection: &mut Connection<'_>, state: &ServerState) -> io::Result<()> {
    let headers = match connection.read_headers() {
        Ok(headers) => headers,
        Err(error) if error.kind() == io::ErrorKind::InvalidData => {
            return reply(
                connection,
                "431 Request Header Fields Too Large",
                b"Request too large",
                false,
            );
        }
        Err(error) => return Err(error),
    };
    let request = match parse_request(&headers, state.address.port()) {
        Ok(request) => request,
        Err(status) => return reply(connection, status, b"Request rejected", false),
    };
    if request.target.starts_with("/aps-query/") {
        return handle_query(connection, state, &request);
    }
    connection.deadline = Instant::now() + RESPONSE_TIMEOUT;
    let head = request.method == "HEAD";
    if !matches!(request.method, "GET" | "HEAD") {
        return reply(
            connection,
            "405 Method Not Allowed",
            b"Method not allowed",
            head,
        );
    }
    let Some(path) = request_path(request.target) else {
        return reply(connection, "404 Not Found", b"Not found", head);
    };
    if path == state.profile_route {
        let file = state
            .profile_path
            .lock()
            .map_err(|_| io::Error::other("profile state unavailable"))?
            .clone();
        return serve_file(connection, &file, head, "application/gzip");
    }
    // Missing assets stay errors. Only the authenticated local view route gets
    // the SPA shell, never arbitrary unknown paths or missing JS/CSS files.
    let file = if is_view_route(path, request.authority, &state.profile_route) {
        safe_static_path(&state.root, "/")
    } else {
        percent_decode(path).and_then(|decoded| safe_static_path(&state.root, &decoded))
    };
    if let Some(file) = &file
        && file == &state.root.join(INDEX_FILE)
    {
        return serve_shell(connection, state, head);
    }
    match file {
        Some(file) => serve_file(connection, &file, head, content_type(&file)),
        None => reply(connection, "404 Not Found", b"Not found", head),
    }
}

fn request_path(target: &str) -> Option<&str> {
    if !target.starts_with('/')
        || target.starts_with("//")
        || target.contains('#')
        || target.bytes().any(|byte| byte.is_ascii_control())
    {
        return None;
    }
    target.split('?').next()
}

fn serve_shell(connection: &mut Connection<'_>, state: &ServerState, head: bool) -> io::Result<()> {
    let Some(path) = safe_static_path(&state.root, "/") else {
        return reply(connection, "404 Not Found", b"Not found", head);
    };
    let mut html = String::new();
    File::open(path)?
        .take(2 * 1024 * 1024 + 1)
        .read_to_string(&mut html)?;
    if html.len() > 2 * 1024 * 1024 {
        return reply(
            connection,
            "413 Content Too Large",
            b"Shell too large",
            head,
        );
    }
    let bridge = state
        .bridge
        .lock()
        .map_err(|_| io::Error::other("bridge unavailable"))?
        .clone();
    let page_id = bridge.page_id();
    let document_id = bridge.issue_document().map_err(io::Error::other)?;
    // Only the served response is extended. Upstream assets stay byte-for-byte unchanged.
    let script = format!(
        "<script defer src=\"/aps-query/{page_id}/adapter.js\" data-aps-page-id=\"{page_id}\" data-aps-document-id=\"{document_id}\" data-aps-profile-url=\"http://{}{}\"></script>",
        state.address, state.profile_route
    );
    let position = html
        .find("</head>")
        .or_else(|| html.find("</body>"))
        .unwrap_or(html.len());
    html.insert_str(position, &script);
    write_headers(
        connection,
        "200 OK",
        "text/html; charset=utf-8",
        html.len() as u64,
    )?;
    if !head {
        connection.write_all(html.as_bytes())?;
    }
    Ok(())
}

fn json_reply(connection: &mut Connection<'_>, value: &serde_json::Value) -> io::Result<()> {
    let body = serde_json::to_vec(value).map_err(io::Error::other)?;
    write_headers(connection, "200 OK", "application/json", body.len() as u64)?;
    connection.write_all(&body)
}

fn handle_query(
    connection: &mut Connection<'_>,
    state: &ServerState,
    request: &Request<'_>,
) -> io::Result<()> {
    let bridge = state
        .bridge
        .lock()
        .map_err(|_| io::Error::other("bridge unavailable"))?
        .clone();
    let page_id = bridge.page_id();
    let prefix = format!("/aps-query/{page_id}/");
    let Some(action) = request.target.strip_prefix(&prefix) else {
        return reply(connection, "410 Gone", b"Page expired", false);
    };
    if action == "adapter.js" && request.method == "GET" {
        write_headers(
            connection,
            "200 OK",
            "text/javascript; charset=utf-8",
            QUERY_ADAPTER.len() as u64,
        )?;
        return connection.write_all(QUERY_ADAPTER.as_bytes());
    }
    if request.token != Some(page_id.as_str()) {
        return reply(connection, "403 Forbidden", b"Query rejected", false);
    }
    match (request.method, action) {
        ("GET", "poll") => {
            let result = request
                .document
                .ok_or("missing document")
                .and_then(|document| {
                    bridge
                        .poll_for_document(document)
                        .map_err(|_| "expired document")
                });
            match result {
                Ok(batch) => json_reply(connection, &batch),
                Err(_) => reply(connection, "410 Gone", b"Document expired", false),
            }
        }
        ("POST", "attach" | "result") => {
            if request.origin != Some(format!("http://{}", request.authority).as_str())
                || request.content_type != Some("application/json")
            {
                return reply(connection, "403 Forbidden", b"Query rejected", false);
            }
            let maximum = if action == "attach" {
                256
            } else {
                bridge::MAX_RESPONSE_BYTES
            };
            let Some(length) = request.content_length else {
                return reply(connection, "411 Length Required", b"Length required", false);
            };
            if length == 0 || length > maximum {
                return reply(
                    connection,
                    "413 Content Too Large",
                    b"Query too large",
                    false,
                );
            }
            let body = match connection.read_body(length) {
                Ok(body) => body,
                Err(error) if error.kind() == io::ErrorKind::InvalidData => {
                    return reply(connection, "400 Bad Request", b"Invalid body", false);
                }
                Err(error) => return Err(error),
            };
            let result = if action == "attach" {
                aps_json::parse_slice(
                    &body,
                    aps_json::Limits {
                        max_bytes: 256,
                        max_depth: 2,
                        max_nodes: 4,
                        max_string_bytes: 64,
                        max_collection_items: 1,
                    },
                )
                .ok()
                .and_then(|value| {
                    value
                        .as_object()
                        .filter(|object| object.len() == 1)
                        .and_then(|object| object.get("documentId"))
                        .and_then(|id| id.as_str())
                        .map(str::to_owned)
                })
                .ok_or_else(|| "E_DOCUMENT".to_owned())
                .and_then(|id| bridge.attach_document(&id))
            } else {
                bridge.complete(&body)
            };
            match result {
                Ok(()) => json_reply(connection, &serde_json::json!({"ok": true})),
                Err(_) => reply(
                    connection,
                    "409 Conflict",
                    b"Query expired or invalid",
                    false,
                ),
            }
        }
        _ => reply(connection, "404 Not Found", b"Not found", false),
    }
}

fn is_view_route(path: &str, authority: &str, profile_route: &str) -> bool {
    let mut parts = path.trim_matches('/').split('/');
    if parts.next() != Some("from-url") {
        return false;
    }
    let profile_url = parts.next().and_then(percent_decode);
    if profile_url.as_deref() != Some(&format!("http://{authority}{profile_route}")) {
        return false;
    }
    let Some(tab) = parts.next() else {
        return false;
    };
    !tab.is_empty()
        && tab
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        && parts.next().is_none()
}

fn safe_static_path(root: &Path, path: &str) -> Option<PathBuf> {
    if !path.starts_with('/') || path.starts_with("//") {
        return None;
    }
    let mut candidate = root.to_path_buf();
    for segment in path.split('/').filter(|segment| !segment.is_empty()) {
        if segment == "."
            || segment == ".."
            || segment.contains(['\\', ':'])
            || segment.bytes().any(|byte| byte.is_ascii_control())
        {
            return None;
        }
        candidate.push(segment);
        if fs::symlink_metadata(&candidate)
            .ok()?
            .file_type()
            .is_symlink()
        {
            return None;
        }
    }
    if candidate.is_dir() {
        candidate.push(INDEX_FILE);
    }
    let metadata = fs::symlink_metadata(&candidate).ok()?;
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return None;
    }
    let candidate = fs::canonicalize(candidate).ok()?;
    candidate.starts_with(root).then_some(candidate)
}

fn validate_dist_root(root: &Path) -> Result<PathBuf, String> {
    let metadata = fs::symlink_metadata(root)
        .map_err(|error| format!("Firefox Profiler assets are missing: {error}"))?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err("Firefox Profiler dist must be a real directory".to_owned());
    }
    let root = fs::canonicalize(root)
        .map_err(|error| format!("cannot resolve profiler assets: {error}"))?;
    if safe_static_path(&root, "/").is_none() {
        return Err("bundled Firefox Profiler index.html is missing or unsafe".to_owned());
    }
    Ok(root)
}

fn locate_dist_root(start: &Path) -> Option<PathBuf> {
    start.ancestors().find_map(|ancestor| {
        [
            ancestor.join(DIST_DIRECTORY),
            ancestor.join("Resources").join(DIST_DIRECTORY),
            ancestor.join("Contents/Resources").join(DIST_DIRECTORY),
        ]
        .into_iter()
        .find(|root| root.join(INDEX_FILE).is_file())
    })
}

fn find_dist_root() -> Option<PathBuf> {
    std::env::current_dir()
        .ok()
        .and_then(|start| locate_dist_root(&start))
        .or_else(|| {
            std::env::current_exe()
                .ok()
                .and_then(|executable| executable.parent().and_then(locate_dist_root))
        })
}

fn private_directory(storage_root: &Path) -> Result<PathBuf, String> {
    let mut builder = fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder
        .create(storage_root)
        .map_err(|error| format!("cannot create viewer storage root: {error}"))?;
    let directory = fs::canonicalize(storage_root)
        .map_err(|error| format!("cannot resolve viewer storage root: {error}"))?
        .join("firefox-viewer");
    builder.recursive(false);
    match builder.create(&directory) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
        Err(error) => return Err(format!("cannot create private viewer directory: {error}")),
    }
    let metadata = fs::symlink_metadata(&directory)
        .map_err(|error| format!("cannot inspect viewer directory: {error}"))?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err("Firefox viewer storage must be a real, app-owned directory".to_owned());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o700))
            .map_err(|error| format!("cannot restrict viewer directory permissions: {error}"))?;
    }
    Ok(directory)
}

fn write_profile(directory: &Path, profile: &[u8]) -> Result<PathBuf, String> {
    // A shared storage root must not let another viewer replace this server's bytes.
    let destination = directory.join(format!("{}-{PROFILE_FILE}", random_token()?));
    let temporary = directory.join(format!(".profile-{}.tmp", random_token()?));
    let mut options = OpenOptions::new();
    options.create_new(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&temporary).map_err(|error| {
        format!("cannot create private Firefox profile temporary file: {error}")
    })?;
    let result = (|| {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            file.set_permissions(fs::Permissions::from_mode(0o600))?;
        }
        file.write_all(profile)?;
        file.sync_all()?;
        drop(file);
        // Never truncate an existing profile or remove it on rename failure.
        fs::rename(&temporary, &destination)
    })();
    if let Err(error) = result {
        let _ = fs::remove_file(temporary);
        return Err(format!("cannot publish Firefox profile: {error}"));
    }
    Ok(destination)
}

fn random_token() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    #[cfg(unix)]
    File::open("/dev/urandom")
        .and_then(|mut random| random.read_exact(&mut bytes))
        .map_err(|error| format!("cannot obtain secure Firefox viewer token: {error}"))?;
    #[cfg(windows)]
    {
        #[link(name = "bcrypt")]
        unsafe extern "system" {
            fn BCryptGenRandom(
                algorithm: *mut std::ffi::c_void,
                buffer: *mut u8,
                length: u32,
                flags: u32,
            ) -> i32;
        }
        // SAFETY: the system RNG requires a null algorithm handle and flag 2;
        // the writable buffer remains valid for its exact length during the call.
        let status = unsafe {
            BCryptGenRandom(
                std::ptr::null_mut(),
                bytes.as_mut_ptr(),
                bytes.len() as u32,
                2,
            )
        };
        if status < 0 {
            return Err("cannot obtain secure Firefox viewer token".to_owned());
        }
    }
    #[cfg(not(any(unix, windows)))]
    return Err("secure Firefox viewer tokens are unsupported on this platform".to_owned());
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

fn serve_file(
    connection: &mut Connection<'_>,
    path: &Path,
    head: bool,
    mime: &str,
) -> io::Result<()> {
    let metadata = fs::symlink_metadata(path);
    if !metadata.is_ok_and(|metadata| metadata.is_file() && !metadata.file_type().is_symlink()) {
        return reply(connection, "404 Not Found", b"Not found", head);
    }
    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(_) => return reply(connection, "404 Not Found", b"Not found", head),
    };
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.len() > MAX_BODY_BYTES {
        return reply(connection, "413 Content Too Large", b"File too large", head);
    }
    write_headers(connection, "200 OK", mime, metadata.len())?;
    if !head {
        let mut remaining = metadata.len();
        let mut buffer = [0; 16 * 1024];
        while remaining > 0 {
            connection.check_deadline()?;
            let wanted = remaining.min(buffer.len() as u64) as usize;
            let read = file.read(&mut buffer[..wanted])?;
            if read == 0 {
                return Err(io::ErrorKind::UnexpectedEof.into());
            }
            connection.write_all(&buffer[..read])?;
            remaining -= read as u64;
        }
    }
    Ok(())
}

fn reply(connection: &mut Connection<'_>, status: &str, body: &[u8], head: bool) -> io::Result<()> {
    write_headers(
        connection,
        status,
        "text/plain; charset=utf-8",
        body.len() as u64,
    )?;
    if !head {
        connection.write_all(body)?;
    }
    Ok(())
}

fn write_headers(
    connection: &mut Connection<'_>,
    status: &str,
    mime: &str,
    length: u64,
) -> io::Result<()> {
    connection.write_all(format!(
        "HTTP/1.1 {status}\r\nContent-Type: {mime}\r\nContent-Length: {length}\r\nContent-Security-Policy: {CONTENT_SECURITY_POLICY}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
    ).as_bytes())
}

fn content_type(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|extension| extension.to_str())
        .unwrap_or_default()
    {
        "html" => "text/html; charset=utf-8",
        "js" | "mjs" => "text/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "json" | "map" => "application/json",
        "wasm" => "application/wasm",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "ico" => "image/x-icon",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        "ftl" | "txt" | "md" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}

fn percent_encode(value: &str) -> String {
    let mut result = String::with_capacity(value.len());
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || b"-_.!~*'()".contains(&byte) {
            result.push(byte as char);
        } else {
            result.push_str(&format!("%{byte:02X}"));
        }
    }
    result
}

fn percent_decode(value: &str) -> Option<String> {
    let mut bytes = value.bytes();
    let mut decoded = Vec::with_capacity(value.len());
    while let Some(byte) = bytes.next() {
        decoded.push(if byte == b'%' {
            let high = (bytes.next()? as char).to_digit(16)?;
            let low = (bytes.next()? as char).to_digit(16)?;
            (high * 16 + low) as u8
        } else {
            byte
        });
    }
    String::from_utf8(decoded).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fixture {
        root: PathBuf,
        assets: PathBuf,
    }

    impl Fixture {
        fn new() -> Self {
            let root =
                std::env::temp_dir().join(format!("aps-firefox-test-{}", random_token().unwrap()));
            let assets = root.join(DIST_DIRECTORY);
            fs::create_dir_all(assets.join("nested")).unwrap();
            fs::write(assets.join(INDEX_FILE), b"<html>profiler</html>").unwrap();
            fs::write(assets.join("nested/app.js"), b"script").unwrap();
            fs::write(root.join("secret.txt"), b"secret").unwrap();
            Self {
                root: fs::canonicalize(root).unwrap(),
                assets: fs::canonicalize(assets).unwrap(),
            }
        }

        fn viewer(&self) -> FirefoxViewer {
            let mut viewer = FirefoxViewer::new();
            viewer
                .open_with_root(b"profile", &self.root, &self.assets)
                .unwrap();
            viewer
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }

    fn request(viewer: &FirefoxViewer, target: &str, method: &str, extra: &str) -> Vec<u8> {
        let state = &viewer.server.as_ref().unwrap().state;
        raw_request(
            state.address,
            &format!(
                "{method} {target} HTTP/1.1\r\nHost: {}\r\n{extra}\r\n",
                state.address
            ),
        )
    }

    fn raw_request(address: SocketAddr, raw: &str) -> Vec<u8> {
        let mut stream = TcpStream::connect_timeout(&address, Duration::from_secs(2)).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        stream
            .set_write_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        stream.write_all(raw.as_bytes()).unwrap();
        let mut response = Vec::new();
        stream.read_to_end(&mut response).unwrap();
        response
    }

    fn text(response: &[u8]) -> &str {
        std::str::from_utf8(response).unwrap()
    }

    fn profile_path(viewer: &FirefoxViewer) -> PathBuf {
        viewer
            .server
            .as_ref()
            .unwrap()
            .state
            .profile_path
            .lock()
            .unwrap()
            .clone()
    }

    fn issue_document(viewer: &FirefoxViewer) -> String {
        viewer
            .server
            .as_ref()
            .unwrap()
            .state
            .bridge
            .lock()
            .unwrap()
            .issue_document()
            .unwrap()
    }

    #[test]
    fn viewer_is_send_and_url_contains_a_local_secret_profile() {
        fn assert_send<T: Send>() {}
        assert_send::<FirefoxViewer>();
        let fixture = Fixture::new();
        let viewer = fixture.viewer();
        let server = viewer.server.as_ref().unwrap();
        let url = server.view_url();
        let origin = format!("http://{}", server.state.address);
        let suffix = url.strip_prefix(&format!("{origin}/from-url/")).unwrap();
        let encoded = suffix.strip_suffix("/calltree/").unwrap();
        assert_eq!(
            percent_decode(encoded).unwrap(),
            format!("{origin}{}", server.state.profile_route)
        );
        assert!(encoded.contains("http%3A%2F%2F127.0.0.1%3A"));
        assert_eq!(
            server.state.profile_route.len(),
            "/aps-profile-".len() + 64 + ".json.gz".len()
        );
        assert_ne!(random_token().unwrap(), random_token().unwrap());
        assert_eq!(OFFICIAL_URL, "https://profiler.firefox.com/");
    }

    #[test]
    fn locates_checkout_and_packaged_assets_and_errors_when_missing() {
        let fixture = Fixture::new();
        assert_eq!(
            locate_dist_root(&fixture.root.join("deep/nested")),
            Some(fixture.assets.clone())
        );
        let bundle = fixture.root.join("Viewer.app");
        let packaged = bundle.join("Contents/Resources").join(DIST_DIRECTORY);
        fs::create_dir_all(&packaged).unwrap();
        fs::write(packaged.join(INDEX_FILE), b"packaged").unwrap();
        assert_eq!(
            locate_dist_root(&bundle.join("Contents/MacOS")),
            Some(packaged)
        );
        let mut viewer = FirefoxViewer::new();
        let storage = fixture.root.join("unused");
        assert!(
            viewer
                .open_with_root(b"profile", &storage, &fixture.root.join("missing"))
                .is_err()
        );
        assert!(!storage.exists());
        fs::remove_file(fixture.assets.join(INDEX_FILE)).unwrap();
        assert!(
            viewer
                .open_with_root(b"profile", &storage, &fixture.assets)
                .is_err()
        );
        assert!(viewer.server.is_none());
    }

    #[test]
    fn static_routes_profile_head_and_privacy_headers() {
        let fixture = Fixture::new();
        let viewer = fixture.viewer();
        let server = viewer.server.as_ref().unwrap();
        let profile = request(&viewer, &server.state.profile_route, "GET", "");
        assert!(text(&profile).starts_with("HTTP/1.1 200 OK\r\n"));
        assert!(text(&profile).contains("Content-Type: application/gzip\r\n"));
        assert!(text(&profile).ends_with("profile"));
        assert!(!text(&profile).contains("Access-Control-Allow"));
        assert!(!text(&profile).contains("Content-Encoding:"));
        assert!(text(&profile).contains(&format!(
            "Content-Security-Policy: {CONTENT_SECURITY_POLICY}\r\n"
        )));
        assert!(text(&profile).contains("Referrer-Policy: no-referrer\r\n"));
        let head = request(&viewer, &server.state.profile_route, "HEAD", "");
        assert!(text(&head).contains("Content-Length: 7\r\n"));
        assert!(text(&head).ends_with("\r\n\r\n"));
        let route = server
            .view_url()
            .strip_prefix(&format!("http://{}", server.state.address))
            .unwrap()
            .to_owned();
        let shell = request(&viewer, &route, "GET", "");
        assert!(text(&shell).contains("<html>profiler</html>"));
        assert!(text(&shell).contains("data-aps-profile-url="));
        assert!(text(&shell).contains("/adapter.js\""));
        assert_eq!(
            fs::read(fixture.assets.join(INDEX_FILE)).unwrap(),
            b"<html>profiler</html>"
        );
        assert!(text(&request(&viewer, "/nested/app.js?cache=1", "GET", "")).ends_with("script"));
        for path in ["/missing.js", "/unknown/", "/aps-profile.json.gz"] {
            assert!(text(&request(&viewer, path, "GET", "")).starts_with("HTTP/1.1 404"));
        }
        for method in ["POST", "OPTIONS"] {
            assert!(
                text(&request(&viewer, &server.state.profile_route, method, ""))
                    .starts_with("HTTP/1.1 405")
            );
        }
        assert_eq!(content_type(Path::new("module.wasm")), "application/wasm");
    }

    #[test]
    fn rejects_foreign_hosts_origins_and_ambiguous_headers() {
        let fixture = Fixture::new();
        let viewer = fixture.viewer();
        let state = &viewer.server.as_ref().unwrap().state;
        for host in ["evil.example", "127.0.0.1:1", "localhost"] {
            let response = raw_request(
                state.address,
                &format!(
                    "GET {} HTTP/1.1\r\nHost: {host}\r\n\r\n",
                    state.profile_route
                ),
            );
            assert!(text(&response).starts_with("HTTP/1.1 403"), "{host}");
            assert!(!text(&response).ends_with("profile"));
        }
        for origin in ["https://evil.example", "null", "http://localhost:1"] {
            let response = request(
                &viewer,
                &state.profile_route,
                "GET",
                &format!("Origin: {origin}\r\n"),
            );
            assert!(text(&response).starts_with("HTTP/1.1 403"), "{origin}");
        }
        let same_origin = format!("Origin: http://{}\r\n", state.address);
        assert!(
            text(&request(&viewer, &state.profile_route, "GET", &same_origin)).ends_with("profile")
        );
        let duplicate = request(
            &viewer,
            &state.profile_route,
            "GET",
            &format!("Host: {}\r\n", state.address),
        );
        assert!(text(&duplicate).starts_with("HTTP/1.1 400"));
        let local = raw_request(
            state.address,
            &format!(
                "GET / HTTP/1.1\r\nHost: localhost:{}\r\nOrigin: http://localhost:{}\r\n\r\n",
                state.address.port(),
                state.address.port()
            ),
        );
        assert!(text(&local).starts_with("HTTP/1.1 200"));
    }

    #[test]
    fn rejects_encoded_traversal_absolute_targets_and_invalid_encodings() {
        let fixture = Fixture::new();
        let viewer = fixture.viewer();
        for path in [
            "/../secret.txt",
            "/%2e%2e/secret.txt",
            "/nested/%2E%2E/%2E%2E/secret.txt",
            "/nested%2f..%2f..%2fsecret.txt",
            "/nested%5c..%5csecret.txt",
            "/x%00",
            "/nested/./app.js",
            "/C%3A/secret.txt",
            "/%",
            "/%FF",
            "/%zz",
            "//evil.example/",
            "http://evil.example/",
            "/from-url/http%3A%2F%2Fevil.example%2Fx/calltree/",
        ] {
            let response = request(&viewer, path, "GET", "");
            assert!(
                text(&response).starts_with("HTTP/1.1 404"),
                "{path}: {}",
                text(&response)
            );
            assert!(!text(&response).ends_with("secret"));
        }
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlinks_and_uses_private_permissions() {
        use std::os::unix::fs::{PermissionsExt, symlink};
        let fixture = Fixture::new();
        let viewer = fixture.viewer();
        let private = fixture.root.join("firefox-viewer");
        assert_eq!(
            fs::metadata(&private).unwrap().permissions().mode() & 0o777,
            0o700
        );
        assert_eq!(
            fs::metadata(profile_path(&viewer))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        symlink(
            fixture.root.join("secret.txt"),
            fixture.assets.join("leak.txt"),
        )
        .unwrap();
        symlink(&fixture.root, fixture.assets.join("outside")).unwrap();
        symlink(
            fixture.assets.join("nested/app.js"),
            fixture.assets.join("alias.js"),
        )
        .unwrap();
        for path in ["/leak.txt", "/outside/secret.txt", "/alias.js"] {
            assert!(text(&request(&viewer, path, "GET", "")).starts_with("HTTP/1.1 404"));
        }
        drop(viewer);
        assert_eq!(fs::read_dir(&private).unwrap().count(), 0);
        symlink(fixture.root.join("secret.txt"), private.join(PROFILE_FILE)).unwrap();
        let independent = write_profile(&private, b"replacement").unwrap();
        assert_ne!(independent, private.join(PROFILE_FILE));
        assert_eq!(
            fs::read(fixture.root.join("secret.txt")).unwrap(),
            b"secret"
        );
        fs::remove_file(private.join(PROFILE_FILE)).unwrap();
        fs::remove_file(independent).unwrap();
        fs::remove_dir(&private).unwrap();
        symlink(&fixture.assets, &private).unwrap();
        assert!(private_directory(&fixture.root).is_err());
    }

    #[test]
    fn reuses_listener_replaces_profiles_and_stops_idempotently() {
        let fixture = Fixture::new();
        let mut viewer = fixture.viewer();
        let original_url = viewer.server.as_ref().unwrap().view_url();
        let address = viewer.server.as_ref().unwrap().state.address;
        assert_eq!(
            viewer
                .open_with_root(b"new profile", &fixture.root, &fixture.assets)
                .unwrap(),
            original_url
        );
        assert_eq!(fs::read(profile_path(&viewer)).unwrap(), b"new profile");
        assert_eq!(
            fs::read_dir(fixture.root.join("firefox-viewer"))
                .unwrap()
                .count(),
            1
        );
        let profile_route = viewer.server.as_ref().unwrap().state.profile_route.clone();
        assert!(text(&request(&viewer, &profile_route, "GET", "")).ends_with("new profile"));
        assert!(
            viewer
                .open_with_root(b"", &fixture.root, &fixture.assets)
                .is_err()
        );
        assert!(text(&request(&viewer, &profile_route, "GET", "")).ends_with("new profile"));
        let other_storage = fixture.root.join("other-storage");
        assert_eq!(
            viewer
                .open_with_root(b"other profile", &other_storage, &fixture.assets)
                .unwrap(),
            original_url
        );
        let profile_route = &viewer.server.as_ref().unwrap().state.profile_route;
        assert!(text(&request(&viewer, profile_route, "GET", "")).ends_with("other profile"));
        viewer.stop();
        viewer.stop();
        assert!(TcpStream::connect_timeout(&address, Duration::from_secs(1)).is_err());
        let new_url = viewer
            .open_with_root(b"restarted", &fixture.root, &fixture.assets)
            .unwrap();
        assert_ne!(new_url, original_url);
        let address = viewer.server.as_ref().unwrap().state.address;
        drop(viewer);
        assert!(TcpStream::connect_timeout(&address, Duration::from_secs(1)).is_err());
    }

    #[test]
    fn shared_storage_keeps_viewer_profiles_independent_and_cleans_owned_files_only() {
        let fixture = Fixture::new();
        let mut first = fixture.viewer();
        let mut second = FirefoxViewer::new();
        second
            .open_with_root(b"second profile", &fixture.root, &fixture.assets)
            .unwrap();
        let first_path = profile_path(&first);
        let second_path = profile_path(&second);
        assert_ne!(first_path, second_path);
        let first_route = first.server.as_ref().unwrap().state.profile_route.clone();
        let second_route = second.server.as_ref().unwrap().state.profile_route.clone();
        assert!(text(&request(&first, &first_route, "GET", "")).ends_with("profile"));
        assert!(text(&request(&second, &second_route, "GET", "")).ends_with("second profile"));
        first
            .open_with_root(b"first replacement", &fixture.root, &fixture.assets)
            .unwrap();
        assert!(!first_path.exists());
        assert_eq!(fs::read(&second_path).unwrap(), b"second profile");
        first.stop();
        assert_eq!(fs::read(&second_path).unwrap(), b"second profile");
        second.stop();
        assert!(!second_path.exists());
    }

    #[test]
    fn read_and_write_deadlines_are_total_not_per_operation() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        client
            .set_write_timeout(Some(Duration::from_secs(1)))
            .unwrap();
        let (server, _) = listener.accept().unwrap();
        server.set_nonblocking(true).unwrap();
        let stop = AtomicBool::new(false);
        let mut connection = Connection {
            stream: server,
            stop: &stop,
            deadline: Instant::now() + Duration::from_millis(80),
            buffered_body: Vec::new(),
        };
        thread::scope(|scope| {
            scope.spawn(|| {
                for _ in 0..100 {
                    if stop.load(Ordering::Acquire) || client.write_all(b"a").is_err() {
                        break;
                    }
                    thread::sleep(Duration::from_millis(5));
                }
            });
            let started = Instant::now();
            let result = connection.read_headers();
            stop.store(true, Ordering::Release);
            assert_eq!(result.unwrap_err().kind(), io::ErrorKind::TimedOut);
            assert!(started.elapsed() < Duration::from_secs(1));
        });
        stop.store(false, Ordering::Release);
        let body = vec![0; MAX_BODY_BYTES as usize];
        connection.deadline = Instant::now() + Duration::from_millis(80);
        let started = Instant::now();
        assert_eq!(
            connection.write_all(&body).unwrap_err().kind(),
            io::ErrorKind::TimedOut
        );
        assert!(started.elapsed() < Duration::from_secs(1));
    }

    #[test]
    fn bounds_headers_files_and_shutdown_with_stalled_clients() {
        let fixture = Fixture::new();
        let mut viewer = fixture.viewer();
        let state = &viewer.server.as_ref().unwrap().state;
        let address = state.address;
        let long_header = format!(
            "GET / HTTP/1.1\r\nHost: {address}\r\nX-Large: {}",
            "a".repeat(MAX_REQUEST_BYTES)
        );
        // Leave no unread input when the server closes after rejecting headers.
        let response = raw_request(address, &long_header[..MAX_REQUEST_BYTES]);
        assert!(text(&response).starts_with("HTTP/1.1 431"));
        let large = File::create(fixture.assets.join("large.js")).unwrap();
        large.set_len(MAX_BODY_BYTES + 1).unwrap();
        assert!(text(&request(&viewer, "/large.js", "GET", "")).starts_with("HTTP/1.1 413"));
        let mut clients = Vec::new();
        for _ in 0..WORKERS {
            let mut client = TcpStream::connect(address).unwrap();
            client.write_all(b"GET / HTTP/1.1\r\n").unwrap();
            clients.push(client);
        }
        thread::sleep(Duration::from_millis(40));
        let started = Instant::now();
        viewer.stop();
        assert!(started.elapsed() < Duration::from_secs(1));
        assert!(TcpStream::connect_timeout(&address, Duration::from_secs(1)).is_err());
    }

    fn query_request(
        viewer: &FirefoxViewer,
        action: &str,
        document: &str,
        body: Option<&serde_json::Value>,
    ) -> Vec<u8> {
        let state = &viewer.server.as_ref().unwrap().state;
        let id = state.bridge.lock().unwrap().page_id();
        let body = body.map(serde_json::Value::to_string);
        let method = if body.is_some() { "POST" } else { "GET" };
        let body_headers = body
            .as_ref()
            .map(|body| {
                format!(
                    "Origin: http://{}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n",
                    state.address,
                    body.len()
                )
            })
            .unwrap_or_default();
        raw_request(
            state.address,
            &format!(
                "{method} /aps-query/{id}/{action} HTTP/1.1\r\nHost: {}\r\nX-APS-Query-Token: {id}\r\nX-APS-Query-Document: {document}\r\n{body_headers}\r\n{}",
                state.address,
                body.unwrap_or_default()
            ),
        )
    }

    #[test]
    fn query_http_round_trip_preserves_body_received_with_headers() {
        let fixture = Fixture::new();
        let viewer = fixture.viewer();
        let ticket = viewer
            .query("context", serde_json::json!({}), None)
            .unwrap();
        let document = issue_document(&viewer);
        let attached = query_request(
            &viewer,
            "attach",
            &document,
            Some(&serde_json::json!({"documentId": document})),
        );
        assert!(text(&attached).starts_with("HTTP/1.1 200"));
        let batch = query_request(&viewer, "poll", &document, None);
        let batch: serde_json::Value =
            serde_json::from_str(text(&batch).split_once("\r\n\r\n").unwrap().1).unwrap();
        assert_eq!(batch["requests"].as_array().unwrap().len(), 1);
        let expected = serde_json::json!({"ok":true,"revision":1,"data":{"tab":"calltree"}});
        let response = query_request(
            &viewer,
            "result",
            &document,
            Some(&serde_json::json!({
                "protocolVersion":1,"pageId":batch["pageId"],"documentId":document,
                "requestId":batch["requests"][0]["requestId"],"result":expected,
            })),
        );
        assert!(text(&response).starts_with("HTTP/1.1 200"));
        assert_eq!(ticket.wait(Duration::from_millis(100)).unwrap(), expected);
    }

    #[test]
    fn query_http_rejects_foreign_missing_and_oversized_requests() {
        let fixture = Fixture::new();
        let viewer = fixture.viewer();
        let state = &viewer.server.as_ref().unwrap().state;
        let id = state.bridge.lock().unwrap().page_id();
        let path = format!("/aps-query/{id}/poll");
        assert!(text(&request(&viewer, &path, "GET", "")).starts_with("HTTP/1.1 403"));
        for headers in [
            format!("X-APS-Query-Token: {id}\r\nOrigin: https://evil.example\r\n"),
            format!(
                "X-APS-Query-Token: wrong\r\nOrigin: http://{}\r\n",
                state.address
            ),
        ] {
            assert!(text(&request(&viewer, &path, "GET", &headers)).starts_with("HTTP/1.1 403"));
        }
        let path = format!("/aps-query/{id}/result");
        let common = format!(
            "X-APS-Query-Token: {id}\r\nContent-Type: application/json\r\nContent-Length: 65537\r\n"
        );
        let missing_origin = request(&viewer, &path, "POST", &common);
        assert!(text(&missing_origin).starts_with("HTTP/1.1 403"));
        let oversized = request(
            &viewer,
            &path,
            "POST",
            &format!("{common}Origin: http://{}\r\n", state.address),
        );
        assert!(text(&oversized).starts_with("HTTP/1.1 413"));
        for extra in [
            "Content-Length: 2\r\nContent-Length: 2\r\n",
            "Transfer-Encoding: chunked\r\n",
            "Content-Length: -1\r\n",
        ] {
            assert!(text(&request(&viewer, "/", "GET", extra)).starts_with("HTTP/1.1 400"));
        }
    }

    #[test]
    fn query_http_invalidates_previous_document_profile_and_listener() {
        let fixture = Fixture::new();
        let mut viewer = fixture.viewer();
        let old_document = issue_document(&viewer);
        let new_document = issue_document(&viewer);
        query_request(
            &viewer,
            "attach",
            &old_document,
            Some(&serde_json::json!({"documentId":old_document})),
        );
        let pending = viewer
            .query("context", serde_json::json!({}), None)
            .unwrap();
        query_request(
            &viewer,
            "attach",
            &new_document,
            Some(&serde_json::json!({"documentId":new_document})),
        );
        assert!(pending.wait(Duration::from_millis(100)).is_err());
        assert!(
            text(&query_request(&viewer, "poll", &old_document, None)).starts_with("HTTP/1.1 410")
        );
        assert!(
            text(&query_request(
                &viewer,
                "attach",
                &old_document,
                Some(&serde_json::json!({"documentId":old_document}))
            ))
            .starts_with("HTTP/1.1 409")
        );
        let old_id = viewer
            .server
            .as_ref()
            .unwrap()
            .state
            .bridge
            .lock()
            .unwrap()
            .page_id();
        let pending = viewer
            .query("context", serde_json::json!({}), None)
            .unwrap();
        viewer
            .open_with_root(b"new profile", &fixture.root, &fixture.assets)
            .unwrap();
        assert!(pending.wait(Duration::from_millis(100)).is_err());
        assert!(
            text(&request(
                &viewer,
                &format!("/aps-query/{old_id}/adapter.js"),
                "GET",
                ""
            ))
            .starts_with("HTTP/1.1 410")
        );
        let pending = viewer
            .query("context", serde_json::json!({}), None)
            .unwrap();
        viewer.stop();
        assert!(pending.wait(Duration::from_millis(100)).is_err());
        assert!(
            viewer
                .query("context", serde_json::json!({}), None)
                .is_err()
        );
    }
}
