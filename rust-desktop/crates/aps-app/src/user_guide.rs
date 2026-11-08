//! Loopback host for the bundled user guide.
//!
//! The guide is a docsify bundle: `index.html` loads `js/init.js`, which fetches
//! `README.md`, the sidebar and page markdown with an absolute `basePath`
//! (`/docs-user/` or `/docs-user-zh/`). Browsers refuse those requests over
//! `file://`, so Help > User guide used to open a page that rendered nothing.
//! Serving the same tree from loopback keeps the guide readable offline.

use aps_ui_foundation::Language;
use std::{
    io::{BufRead, BufReader, Write},
    net::{Ipv4Addr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    thread,
    time::Duration,
};

const INDEX_FILE: &str = "index.html";
const LOOPBACK: Ipv4Addr = Ipv4Addr::LOCALHOST;
const SOCKET_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_HEADER_BYTES: usize = 8 * 1024;

/// Browser URL of the bundled guide for `language`, starting the site if needed.
pub(crate) fn url(language: Language) -> Result<String, String> {
    let root =
        site_root(language).ok_or_else(|| "bundled user documentation was not found".to_owned())?;
    let origin = site_origin(root)?;
    Ok(format!("{origin}{}/", site_directory(language)))
}

fn site_directory(language: Language) -> &'static str {
    if language == Language::SimplifiedChinese {
        "docs-user-zh"
    } else {
        "docs-user"
    }
}

/// Parent directory of the language sites: the checkout root or an app bundle.
fn site_root(language: Language) -> Option<PathBuf> {
    let mut starts = Vec::new();
    if let Ok(current) = std::env::current_dir() {
        starts.push(current);
    }
    if let Ok(executable) = std::env::current_exe()
        && let Some(directory) = executable.parent()
    {
        starts.push(directory.to_path_buf());
    }
    starts.iter().find_map(|start| locate_site(start, language))
}

fn locate_site(start: &Path, language: Language) -> Option<PathBuf> {
    start
        .ancestors()
        .find(|candidate| {
            candidate
                .join(site_directory(language))
                .join(INDEX_FILE)
                .is_file()
        })
        .map(Path::to_path_buf)
}

/// Starts the loopback site once per process and reuses its origin afterwards.
fn site_origin(root: PathBuf) -> Result<String, String> {
    static ORIGIN: OnceLock<Mutex<Option<String>>> = OnceLock::new();
    let origin = ORIGIN.get_or_init(|| Mutex::new(None));
    let mut origin = origin
        .lock()
        .map_err(|_| "user documentation server is unavailable".to_owned())?;
    if let Some(url) = origin.as_ref() {
        return Ok(url.clone());
    }
    let url = start_server(root)?;
    *origin = Some(url.clone());
    Ok(url)
}

/// Binds a loopback port and serves `root` from a detached thread.
fn start_server(root: PathBuf) -> Result<String, String> {
    let listener = TcpListener::bind((LOOPBACK, 0))
        .map_err(|error| format!("cannot bind the user documentation server: {error}"))?;
    let port = listener
        .local_addr()
        .map_err(|error| format!("cannot read the user documentation port: {error}"))?
        .port();
    // The serving thread owns the listener for the process lifetime: closing the
    // window ends the process, so there is no shutdown path to leak.
    thread::Builder::new()
        .name("aps-user-guide".to_owned())
        .spawn(move || serve(listener, root))
        .map_err(|error| format!("cannot start the user documentation server: {error}"))?;
    Ok(format!("http://{LOOPBACK}:{port}/"))
}

fn serve(listener: TcpListener, root: PathBuf) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let root = root.clone();
        // One short-lived thread per request keeps a stalled client from blocking
        // the rest of the guide.
        let _ = thread::Builder::new()
            .name("aps-user-guide-request".to_owned())
            .spawn(move || handle(stream, &root));
    }
}

fn handle(mut stream: TcpStream, root: &Path) {
    let _ = stream.set_read_timeout(Some(SOCKET_TIMEOUT));
    let _ = stream.set_write_timeout(Some(SOCKET_TIMEOUT));
    let Ok(clone) = stream.try_clone() else {
        return;
    };
    let mut reader = BufReader::new(clone);
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).is_err() {
        return;
    }
    let mut header = String::new();
    let mut header_bytes = 0;
    loop {
        header.clear();
        match reader.read_line(&mut header) {
            Ok(0) => break,
            Ok(_) if header == "\r\n" || header == "\n" => break,
            Ok(read) => {
                header_bytes += read;
                if header_bytes > MAX_HEADER_BYTES {
                    return;
                }
            }
            Err(_) => return,
        }
    }
    let mut parts = request_line.split(' ');
    let method = parts.next().unwrap_or_default();
    let target = parts.next().unwrap_or_default();
    let head_only = method == "HEAD";
    if method != "GET" && method != "HEAD" {
        write_response(
            &mut stream,
            "405 Method Not Allowed",
            "text/plain; charset=utf-8",
            b"Method not allowed",
            head_only,
        );
        return;
    }
    match resolve_request(root, target) {
        Some(file) => serve_file(&mut stream, &file, head_only),
        None => not_found(&mut stream, head_only),
    }
}

/// Maps one request target to a file inside `root`.
fn resolve_request(root: &Path, target: &str) -> Option<PathBuf> {
    let decoded = decode_target(target)?;
    let mut candidate = root.to_path_buf();
    for segment in decoded.split('/').filter(|segment| !segment.is_empty()) {
        if segment == "." || segment == ".." {
            return None;
        }
        candidate.push(segment);
    }
    if !candidate.starts_with(root) {
        return None;
    }
    if candidate.is_dir() {
        return Some(candidate.join(INDEX_FILE));
    }
    Some(candidate)
}

fn decode_target(target: &str) -> Option<String> {
    let without_origin = match target.split_once("://") {
        Some((_, rest)) => rest.split_once('/').map_or("/", |(_, path)| path),
        None => target,
    };
    let path = without_origin.split(['?', '#']).next().unwrap_or_default();
    let bytes = path.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            let high = hex(bytes.get(index + 1).copied()?)?;
            let low = hex(bytes.get(index + 2).copied()?)?;
            decoded.push((high << 4) | low);
            index += 3;
        } else {
            decoded.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8(decoded).ok()
}

fn hex(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

fn serve_file(stream: &mut TcpStream, file: &Path, head_only: bool) {
    let Ok(metadata) = std::fs::metadata(file) else {
        not_found(stream, head_only);
        return;
    };
    let Ok(mut source) = std::fs::File::open(file) else {
        not_found(stream, head_only);
        return;
    };
    if !metadata.is_file() {
        not_found(stream, head_only);
        return;
    }
    write_headers(stream, "200 OK", content_type(file), metadata.len());
    if !head_only {
        let _ = std::io::copy(&mut source, stream);
    }
}

fn not_found(stream: &mut TcpStream, head_only: bool) {
    write_response(
        stream,
        "404 Not Found",
        "text/plain; charset=utf-8",
        b"Not found",
        head_only,
    );
}

fn write_response(
    stream: &mut TcpStream,
    status: &str,
    content_type: &str,
    body: &[u8],
    head_only: bool,
) {
    write_headers(stream, status, content_type, body.len() as u64);
    if !head_only {
        let _ = stream.write_all(body);
    }
}

fn write_headers(stream: &mut TcpStream, status: &str, content_type: &str, length: u64) {
    let headers = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {length}\r\n\
         Cache-Control: no-cache\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
    );
    let _ = stream.write_all(headers.as_bytes());
    let _ = stream.flush();
}

fn content_type(file: &Path) -> &'static str {
    let extension = file
        .extension()
        .and_then(|extension| extension.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase();
    match extension.as_str() {
        "html" => "text/html; charset=utf-8",
        "md" => "text/markdown; charset=utf-8",
        "js" => "text/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "json" => "application/json; charset=utf-8",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "webm" => "video/webm",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        _ => "application/octet-stream",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::Read,
        path::Path,
        sync::atomic::{AtomicU64, Ordering},
    };

    static SEQUENCE: AtomicU64 = AtomicU64::new(0);

    struct Fixture {
        root: PathBuf,
    }

    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-user-guide-{}-{}",
                std::process::id(),
                SEQUENCE.fetch_add(1, Ordering::Relaxed)
            ));
            let english = root.join("docs-user");
            let chinese = root.join("docs-user-zh");
            std::fs::create_dir_all(&english).unwrap();
            std::fs::create_dir_all(&chinese).unwrap();
            std::fs::write(english.join(INDEX_FILE), "<html>en</html>").unwrap();
            std::fs::write(english.join("README.md"), "# Hello").unwrap();
            std::fs::write(chinese.join(INDEX_FILE), "<html>zh</html>").unwrap();
            std::fs::write(root.join("secret.txt"), "not part of the guide").unwrap();
            Self { root }
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    fn request(address: &str, raw: &str) -> String {
        let mut stream = TcpStream::connect(address).unwrap();
        stream.write_all(raw.as_bytes()).unwrap();
        let mut response = String::new();
        stream.read_to_string(&mut response).unwrap();
        response
    }

    fn address(origin: &str) -> &str {
        origin.trim_start_matches("http://").trim_end_matches('/')
    }

    #[test]
    fn locates_a_language_site_from_a_nested_directory() {
        let fixture = Fixture::new();
        let nested = fixture.root.join("crates").join("aps-app");

        assert_eq!(
            locate_site(&nested, Language::English),
            Some(fixture.root.clone())
        );
        assert_eq!(
            locate_site(&nested, Language::SimplifiedChinese),
            Some(fixture.root.clone())
        );
        assert_eq!(locate_site(Path::new("/"), Language::English), None);
    }

    #[test]
    fn resolves_directories_and_rejects_escapes() {
        let fixture = Fixture::new();

        assert_eq!(
            resolve_request(&fixture.root, "/docs-user/"),
            Some(fixture.root.join("docs-user").join(INDEX_FILE))
        );
        assert_eq!(
            resolve_request(&fixture.root, "/docs-user/README.md?search=1"),
            Some(fixture.root.join("docs-user").join("README.md"))
        );
        assert_eq!(
            resolve_request(&fixture.root, "/%64ocs-user/"),
            Some(fixture.root.join("docs-user").join(INDEX_FILE))
        );
        assert_eq!(
            resolve_request(&fixture.root, "/docs-user/../secret.txt"),
            None
        );
        assert_eq!(resolve_request(&fixture.root, "/%2E%2E/secret.txt"), None);
        assert_eq!(resolve_request(&fixture.root, "/docs-user/%zz"), None);
    }

    #[test]
    fn serves_pages_markdown_and_head_without_a_body() {
        let fixture = Fixture::new();
        let origin = start_server(fixture.root.clone()).unwrap();
        let address = address(&origin);

        let page = request(
            address,
            "GET /docs-user/ HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(page.starts_with("HTTP/1.1 200 OK"), "{page}");
        assert!(page.contains("Content-Type: text/html; charset=utf-8"));
        assert!(page.ends_with("<html>en</html>"));

        let markdown = request(
            address,
            "GET /docs-user/README.md HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(markdown.contains("Content-Type: text/markdown; charset=utf-8"));
        assert!(markdown.ends_with("# Hello"));

        let head = request(
            address,
            "HEAD /docs-user/README.md HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(head.starts_with("HTTP/1.1 200 OK"), "{head}");
        assert!(!head.contains("# Hello"), "HEAD must not send a body");
    }

    #[test]
    fn serves_the_bundled_guide_tree_from_the_checkout() {
        let root = site_root(Language::SimplifiedChinese).expect("the checkout ships docs-user-zh");
        let origin = start_server(root).unwrap();
        let address = address(&origin);

        let page = request(
            address,
            "GET /docs-user-zh/ HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(page.starts_with("HTTP/1.1 200 OK"), "{page}");
        assert!(
            page.contains("docsify"),
            "the landing page must be the docsify shell"
        );

        // docsify fetches the shell assets, the sidebar and the page markdown.
        let script = request(
            address,
            "GET /docs-user-zh/js/init.js HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(script.starts_with("HTTP/1.1 200 OK"), "{script}");
        assert!(script.contains("Content-Type: text/javascript"));
        let readme = request(
            address,
            "GET /docs-user-zh/README.md HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(readme.starts_with("HTTP/1.1 200 OK"), "{readme}");
        assert!(readme.contains("Content-Type: text/markdown"));
        let sidebar = request(
            address,
            "GET /docs-user-zh/_sidebar.md HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(sidebar.starts_with("HTTP/1.1 200 OK"), "{sidebar}");
    }

    #[test]
    fn rejects_unknown_paths_escapes_and_non_read_methods() {
        let fixture = Fixture::new();
        let origin = start_server(fixture.root.clone()).unwrap();
        let address = address(&origin);

        let missing = request(
            address,
            "GET /missing.html HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(missing.starts_with("HTTP/1.1 404 Not Found"));
        let escape = request(
            address,
            "GET /..%2Fsecret.txt HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(escape.starts_with("HTTP/1.1 404 Not Found"), "{escape}");
        let post = request(
            address,
            "POST /docs-user/ HTTP/1.1\r\nHost: localhost\r\n\r\n",
        );
        assert!(post.starts_with("HTTP/1.1 405 Method Not Allowed"));
    }
}
