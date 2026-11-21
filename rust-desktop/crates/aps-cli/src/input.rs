//! Download only on an explicit host selection, never from an MCP tool call.
use reqwest::{StatusCode, Url, blocking::Client, redirect::Policy};
use std::{
    io::{Read, Write},
    path::{Path, PathBuf},
    time::Duration,
};
use tempfile::NamedTempFile;

pub enum SelectedInput {
    Local(PathBuf),
    Download(NamedTempFile),
}

impl SelectedInput {
    pub fn path(&self) -> &Path {
        match self {
            Self::Local(p) => p,
            Self::Download(f) => f.path(),
        }
    }

    pub fn acquire(input: &str) -> Result<Self, String> {
        if !input.contains("://") {
            return Ok(Self::Local(input.into()));
        }
        Self::from_https(input)
    }

    pub(crate) fn from_https(input: &str) -> Result<Self, String> {
        let url = approved_url(input)?;
        let client = Client::builder()
            .https_only(true)
            .no_proxy()
            .redirect(Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(90))
            .build()
            .map_err(|_| "E_FETCH_CLIENT")?;
        fetch(&client, url, super::MAX_INPUT_BYTES)
    }
}

fn approved_url(input: &str) -> Result<Url, String> {
    let url = Url::parse(input).map_err(|_| "E_FETCH_URL: invalid HTTPS URL")?;
    if url.scheme() != "https"
        || url.host().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || input.trim() != input
        || input.chars().any(char::is_control)
    {
        return Err("E_FETCH_URL: expected HTTPS without userinfo or fragment".into());
    }
    Ok(url)
}

fn fetch(client: &Client, url: Url, limit: u64) -> Result<SelectedInput, String> {
    // Do not propagate a reqwest error (it can contain a signed URL).
    // Preserve only the two known trace type hints. Never reuse host-provided
    // names/content-disposition, credentials or arbitrary URL path components.
    let trace_suffix = if crate::analysis::has_trace_extension(Path::new(url.path())) {
        ".pftrace"
    } else {
        ""
    };
    let response = client.get(url).send().map_err(|_| "E_FETCH_NETWORK")?;
    if response.status() != StatusCode::OK {
        return Err(format!(
            "E_FETCH_STATUS: HTTP {}",
            response.status().as_u16()
        ));
    }
    if response.content_length().is_some_and(|n| n > limit) {
        return Err("E_FETCH_LIMIT".into());
    }
    let mut selected = tempfile::Builder::new()
        .suffix(trace_suffix)
        .tempfile()
        .map_err(|_| "E_FETCH_TEMP")?;
    let n = std::io::copy(&mut response.take(limit + 1), &mut selected)
        .map_err(|_| "E_FETCH_INCOMPLETE")?;
    if n > limit {
        return Err("E_FETCH_LIMIT".into());
    }
    if n == 0 {
        return Err("E_FETCH_EMPTY".into());
    }
    selected.flush().map_err(|_| "E_FETCH_TEMP")?;
    Ok(SelectedInput::Download(selected))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    #[test]
    fn explicit_https_only() {
        for url in [
            "file:///tmp/a",
            "http://example.org/a",
            "https://me:secret@example.org/a",
            "https://example.org/a#f",
        ] {
            assert!(approved_url(url).is_err());
        }
        assert!(approved_url("https://example.org/a?signature=test").is_ok());
    }
    fn local(response: &'static [u8], limit: u64) -> Result<SelectedInput, String> {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = Url::parse(&format!("http://{}/file", l.local_addr().unwrap())).unwrap();
        let t = std::thread::spawn(move || {
            let (mut s, _) = l.accept().unwrap();
            let mut buf = [0; 2048];
            let _ = s.read(&mut buf).unwrap();
            s.write_all(response).unwrap();
        });
        let client = Client::builder()
            .redirect(Policy::none())
            .no_proxy()
            .timeout(Duration::from_secs(2))
            .build()
            .unwrap();
        let r = fetch(&client, url, limit);
        t.join().unwrap();
        r
    }
    #[test]
    fn bounded_stream_is_private_and_ephemeral() {
        let input = local(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n12345", 5).unwrap();
        let path = input.path().to_owned();
        assert_eq!(std::fs::read(&path).unwrap(), b"12345");
        drop(input);
        assert!(!path.exists());
        assert!(
            matches!(local(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n123456",5), Err(e) if e == "E_FETCH_LIMIT")
        );
        assert!(
            matches!(local(b"HTTP/1.1 302 Found\r\nLocation: https://other.invalid\r\nContent-Length: 0\r\n\r\n",5), Err(e) if e == "E_FETCH_STATUS: HTTP 302")
        );
    }
}
