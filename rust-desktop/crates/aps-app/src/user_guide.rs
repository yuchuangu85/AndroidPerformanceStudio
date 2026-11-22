//! Loopback host for the bundled user guide.
//!
//! The guide is a docsify bundle: `index.html` loads `js/init.js`, which fetches
//! `README.md`, the sidebar and page markdown with an absolute `basePath`
//! (`/docs-user/` or `/docs-user-zh/`). Browsers refuse those requests over
//! `file://`. Serving embedded assets from loopback keeps the guide readable
//! offline, even when the executable is installed outside the checkout.

use aps_ui_foundation::Language;
use std::{
    io::{BufRead, BufReader, Read, Write},
    net::{Ipv4Addr, TcpListener, TcpStream},
    path::Path,
    sync::{Mutex, OnceLock},
    thread,
    time::Duration,
};

const ASSETS: &[(&str, &[u8])] = include!(concat!(env!("OUT_DIR"), "/user_guide_assets.rs"));
const LOOPBACK: Ipv4Addr = Ipv4Addr::LOCALHOST;
const SOCKET_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_HEADER_BYTES: usize = 8 * 1024;

/// Browser URL of the bundled guide for `language`, starting the site if needed.
pub(crate) fn url(language: Language) -> Result<String, String> {
    let origin = site_origin()?;
    Ok(format!("{origin}{}/", site_directory(language)))
}

fn site_directory(language: Language) -> &'static str {
    if language == Language::SimplifiedChinese {
        "docs-user-zh"
    } else {
        "docs-user"
    }
}

/// Starts the loopback site once per process and reuses its origin afterwards.
fn site_origin() -> Result<String, String> {
    static ORIGIN: OnceLock<Mutex<Option<String>>> = OnceLock::new();
    let origin = ORIGIN.get_or_init(|| Mutex::new(None));
    let mut origin = origin
        .lock()
        .map_err(|_| "user documentation server is unavailable".to_owned())?;
    if let Some(url) = origin.as_ref() {
        return Ok(url.clone());
    }
    let url = start_server()?;
    *origin = Some(url.clone());
    Ok(url)
}

/// Binds a loopback port and serves the embedded files from a detached thread.
fn start_server() -> Result<String, String> {
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
        .spawn(move || serve(listener))
        .map_err(|error| format!("cannot start the user documentation server: {error}"))?;
    Ok(format!("http://{LOOPBACK}:{port}/"))
}

fn serve(listener: TcpListener) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        // One short-lived thread per request keeps a stalled client from blocking
        // the rest of the guide.
        let _ = thread::Builder::new()
            .name("aps-user-guide-request".to_owned())
            .spawn(move || handle(stream));
    }
}

fn handle(mut stream: TcpStream) {
    let _ = stream.set_read_timeout(Some(SOCKET_TIMEOUT));
    let _ = stream.set_write_timeout(Some(SOCKET_TIMEOUT));
    let Ok(clone) = stream.try_clone() else {
        return;
    };
    let mut reader = BufReader::new(clone).take(MAX_HEADER_BYTES as u64);
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).is_err() {
        return;
    }
    let mut header = String::new();
    loop {
        header.clear();
        match reader.read_line(&mut header) {
            Ok(_) if header == "\r\n" => break,
            Ok(0) | Err(_) => return,
            Ok(_) => {}
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
    match resolve_request(target) {
        Some((file, body)) => write_response(
            &mut stream,
            "200 OK",
            content_type(Path::new(file)),
            body,
            head_only,
        ),
        None => write_response(
            &mut stream,
            "404 Not Found",
            "text/plain; charset=utf-8",
            b"Not found",
            head_only,
        ),
    }
}

/// Resolves only embedded assets, never a pathname on the host.
fn resolve_request(target: &str) -> Option<(&'static str, &'static [u8])> {
    let mut path = decode_target(target)?;
    if path.ends_with('/') {
        path.push_str("index.html");
    }
    let index = ASSETS
        .binary_search_by_key(&path.as_str(), |&(name, _)| name)
        .ok()?;
    Some(ASSETS[index])
}

fn decode_target(target: &str) -> Option<String> {
    let path = target.split(['?', '#']).next()?;
    if !path.starts_with('/') {
        return None;
    }
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

    fn request(origin: &str, method: &str, path: &str) -> String {
        let address = origin.trim_start_matches("http://").trim_end_matches('/');
        let mut stream = TcpStream::connect(address).unwrap();
        stream.set_read_timeout(Some(SOCKET_TIMEOUT)).unwrap();
        write!(
            stream,
            "{method} {path} HTTP/1.1\r\nHost: {address}\r\n\r\n"
        )
        .unwrap();
        let mut response = String::new();
        stream.read_to_string(&mut response).unwrap();
        response
    }

    #[test]
    fn serves_both_embedded_guides_from_one_origin() {
        let en = url(Language::English).unwrap();
        let zh = url(Language::SimplifiedChinese).unwrap();
        let origin = en.strip_suffix("docs-user/").unwrap();
        assert_eq!(zh, format!("{origin}docs-user-zh/"));
        for (site, heading, language) in [
            ("docs-user", "# User Guide", "en"),
            ("docs-user-zh", "# 用户指南", "zh-CN"),
        ] {
            let page = request(origin, "GET", &format!("/{site}/"));
            assert!(page.starts_with("HTTP/1.1 200 OK"), "{page}");
            assert!(page.contains(&format!("<html lang=\"{language}\">")));
            assert!(page.contains("docsify"));
            for asset in [
                "README.md",
                "_sidebar.md",
                "_navbar.md",
                "js/init.js",
                "css/vue_v4.13.1.min.css",
            ] {
                let response = request(origin, "GET", &format!("/{site}/{asset}"));
                assert!(response.starts_with("HTTP/1.1 200 OK"), "{site}/{asset}");
            }
            let markdown = request(origin, "GET", &format!("/{site}/README.md?test=1"));
            assert!(markdown.contains("Content-Type: text/markdown; charset=utf-8"));
            assert!(markdown.contains(heading), "{markdown}");
        }
    }

    #[test]
    fn accepts_head_and_never_serves_files_outside_the_guide() {
        let url = url(Language::English).unwrap();
        let origin = url.strip_suffix("docs-user/").unwrap();
        let response = request(origin, "HEAD", "/docs-user/README.md");
        assert!(response.starts_with("HTTP/1.1 200 OK"));
        assert!(response.ends_with("\r\n\r\n"));
        for path in [
            "/Cargo.toml",
            "/docs-user/../README.md",
            "/docs-user/%2E%2E/README.md",
            "/docs-user/%zz",
        ] {
            assert!(request(origin, "GET", path).starts_with("HTTP/1.1 404 Not Found"));
        }
        assert!(
            request(origin, "POST", "/docs-user/").starts_with("HTTP/1.1 405 Method Not Allowed")
        );
    }
}
