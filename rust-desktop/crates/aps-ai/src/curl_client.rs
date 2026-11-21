//! Shared desktop/CLI HTTP host transport; independent of the GPUI application shell.
//!
//! GPUI ships a null client by default, so the AI runtime could not discover
//! models or reach a provider ("No HttpClient available" surfaced as
//! `E_AI_MODEL_CATALOG_NETWORK`). This client uses the system `curl` binary
//! through an explicit host-process boundary, like the app's other external
//! tools: it embeds no second TLS stack, and each request runs on its own worker
//! thread while the response head returns immediately and the body streams into
//! GPUI's `AsyncBody`, so SSE provider responses keep working.
//!
//! Secrets never enter the process argument list: request headers are written to
//! curl's config on stdin. A request body is a bounded in-memory JSON body, so it
//! is staged in a private app-owned file for the duration of the request.

use futures::channel::{mpsc, oneshot};
use futures::io::AsyncRead;
use futures::{SinkExt, Stream};
use http_client::http::{HeaderMap, HeaderName, HeaderValue, Version};
use http_client::{
    AsyncBody, HttpClient, Inner, Method, RedirectPolicy, Request, RequestTimeout, Response,
    StatusCode, Url,
};
use std::ffi::OsString;
use std::io::{BufRead, BufReader, Cursor, Read, Write};
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::process::{Command, Stdio};
use std::task::{Context, Poll};
use std::time::Duration;

const USER_AGENT: &str = concat!("AndroidPerformanceStudio-Rust/", env!("CARGO_PKG_VERSION"));
const CURL: &str = "curl";
const BODY_CHUNK_BYTES: usize = 16 * 1024;
const BODY_CHANNEL_CAPACITY: usize = 8;
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(120);
const CONNECT_TIMEOUT_SECONDS: u64 = 30;
const MAX_REDIRECTS: usize = 32;

/// The app's shared HTTP client, installed with `Application::with_http_client`.
pub struct CurlHttpClient {
    user_agent: HeaderValue,
    body_root: PathBuf,
}

impl CurlHttpClient {
    pub fn new(body_root: PathBuf) -> Self {
        let body_root = body_root.join("http-bodies");
        let _ = aps_platform::create_private_directory(&body_root);
        Self {
            user_agent: HeaderValue::from_static(USER_AGENT),
            body_root,
        }
    }
}

impl HttpClient for CurlHttpClient {
    fn user_agent(&self) -> Option<&HeaderValue> {
        Some(&self.user_agent)
    }

    fn proxy(&self) -> Option<&Url> {
        // curl already honours the HTTPS_PROXY-style environment.
        None
    }

    fn send(
        &self,
        request: Request<AsyncBody>,
    ) -> futures::future::BoxFuture<'static, http_client::Result<Response<AsyncBody>>> {
        let (parts, body) = request.into_parts();
        let body = match request_body(body) {
            Ok(body) => body,
            Err(error) => return Box::pin(async move { Err(error) }),
        };
        let follow = parts
            .extensions
            .get::<RedirectPolicy>()
            .cloned()
            .unwrap_or_default();
        let timeout = parts
            .extensions
            .get::<RequestTimeout>()
            .map(|timeout| timeout.0)
            .unwrap_or(DEFAULT_TIMEOUT);
        let body_path = match body
            .as_deref()
            .map(|bytes| stage_body(&self.body_root, bytes))
            .transpose()
        {
            Ok(path) => path,
            Err(error) => return Box::pin(async move { Err(http_client::anyhow!(error)) }),
        };
        let prepared = PreparedRequest {
            method: parts.method,
            url: parts.uri.to_string(),
            headers: parts.headers,
            follow,
            timeout,
        };
        let config = config_text(&prepared.headers);
        let (head_sender, head_receiver) = oneshot::channel();
        let (body_sender, body_receiver) = mpsc::channel(BODY_CHANNEL_CAPACITY);
        let spawned = std::thread::Builder::new()
            .name("aps-http".to_owned())
            .spawn(move || {
                run_request(prepared, config, body_path, head_sender, body_sender);
            });
        if let Err(error) = spawned {
            return Box::pin(async move { Err(http_client::anyhow!(error)) });
        }
        Box::pin(async move {
            match head_receiver.await {
                Ok(Ok(head)) => {
                    let mut builder = Response::builder()
                        .status(head.status)
                        .version(head.version);
                    if let Some(headers) = builder.headers_mut() {
                        *headers = head.headers;
                    }
                    Ok(builder
                        .body(AsyncBody::from_reader(BodyReader {
                            receiver: body_receiver,
                            current: None,
                        }))
                        .expect("the response head is already validated"))
                }
                Ok(Err(error)) => Err(error),
                Err(_) => Err(http_client::anyhow!(
                    "the HTTP worker stopped before returning a response"
                )),
            }
        })
    }
}

struct ResponseHead {
    status: StatusCode,
    version: Version,
    headers: HeaderMap,
}

struct PreparedRequest {
    method: Method,
    url: String,
    headers: HeaderMap,
    follow: RedirectPolicy,
    timeout: Duration,
}

fn request_body(body: AsyncBody) -> http_client::Result<Option<Vec<u8>>> {
    match body.0 {
        Inner::Empty => Ok(None),
        Inner::Bytes(cursor) => Ok(Some(cursor.into_inner().to_vec())),
        // Every provider request builds a bounded in-memory JSON body; a
        // streaming request body would need a bridge this client does not
        // silently approximate.
        Inner::AsyncReader(_) => Err(http_client::anyhow!(
            "streaming request bodies are not supported"
        )),
    }
}

fn stage_body(root: &Path, bytes: &[u8]) -> std::io::Result<PathBuf> {
    let path = root.join(format!(
        "{}-{}.body",
        std::process::id(),
        crate::platform::new_opaque_id("http")
    ));
    std::fs::write(&path, bytes)?;
    Ok(path)
}

fn config_text(headers: &HeaderMap) -> String {
    let mut config = String::new();
    if !headers.contains_key(http_client::http::header::USER_AGENT) {
        config.push_str(&format!("user-agent = {}\n", quote(USER_AGENT)));
    }
    for (name, value) in headers {
        let Ok(value) = value.to_str() else {
            continue;
        };
        config.push_str(&format!(
            "header = {}\n",
            quote(&format!("{}: {}", name.as_str(), value))
        ));
    }
    config
}

fn quote(value: &str) -> String {
    let mut quoted = String::with_capacity(value.len() + 2);
    quoted.push('"');
    for character in value.chars() {
        match character {
            '\\' => quoted.push_str("\\\\"),
            '"' => quoted.push_str("\\\""),
            '\n' => quoted.push_str("\\n"),
            '\r' => quoted.push_str("\\r"),
            '\t' => quoted.push_str("\\t"),
            other => quoted.push(other),
        }
    }
    quoted.push('"');
    quoted
}

fn run_request(
    request: PreparedRequest,
    config: String,
    body_path: Option<PathBuf>,
    head_sender: oneshot::Sender<http_client::Result<ResponseHead>>,
    body_sender: mpsc::Sender<std::io::Result<Vec<u8>>>,
) {
    let mut head_sender = Some(head_sender);
    let result = run_request_inner(
        &request,
        &config,
        body_path.as_deref(),
        &mut head_sender,
        body_sender,
    );
    if let Some(path) = body_path {
        let _ = std::fs::remove_file(path);
    }
    if let Err(error) = result {
        if let Some(sender) = head_sender {
            let _ = sender.send(Err(error));
        }
    }
}

fn run_request_inner(
    request: &PreparedRequest,
    config: &str,
    body_path: Option<&Path>,
    head_sender: &mut Option<oneshot::Sender<http_client::Result<ResponseHead>>>,
    mut body_sender: mpsc::Sender<std::io::Result<Vec<u8>>>,
) -> http_client::Result<()> {
    let is_head = request.method == Method::HEAD;
    let mut command = curl_command(request, config, body_path, is_head);
    let mut child = command
        .spawn()
        .map_err(|error| http_client::anyhow!("could not start {}: {error}", CURL))?;
    if let Some(mut stdin) = child.stdin.take() {
        stdin
            .write_all(config.as_bytes())
            .map_err(|error| http_client::anyhow!("could not pass headers to {}: {error}", CURL))?;
    }
    let stderr = child.stderr.take().map(|mut stderr| {
        std::thread::spawn(move || {
            let mut text = String::new();
            let _ = stderr.read_to_string(&mut text);
            text
        })
    });
    let Some(stdout) = child.stdout.take() else {
        let _ = child.kill();
        return Err(http_client::anyhow!("curl produced no output stream"));
    };
    let mut reader = BufReader::new(stdout);
    let follow = !matches!(request.follow, RedirectPolicy::NoFollow);
    let head = match read_head(&mut reader, follow) {
        Ok(head) => head,
        Err(error) => {
            let _ = child.kill();
            let _ = child.wait();
            let detail = stderr
                .and_then(|handle| handle.join().ok())
                .unwrap_or_default();
            return Err(http_client::anyhow!(
                "curl did not return an HTTP response ({error}): {}",
                detail.trim()
            ));
        }
    };
    let Some(sender) = head_sender.take() else {
        return Ok(());
    };
    if sender.send(Ok(head)).is_err() {
        let _ = child.kill();
        let _ = child.wait();
        return Ok(());
    }
    let mut buffer = vec![0u8; BODY_CHUNK_BYTES];
    let mut cancelled = false;
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(read) => {
                let chunk = buffer[..read].to_vec();
                // Blocking the worker (not the UI) gives the stream backpressure.
                if futures::executor::block_on(body_sender.send(Ok(chunk))).is_err() {
                    cancelled = true;
                    break;
                }
            }
            Err(error) => {
                let _ = futures::executor::block_on(body_sender.send(Err(error)));
                break;
            }
        }
    }
    if cancelled {
        let _ = child.kill();
    }
    let _ = child.wait();
    if let Some(handle) = stderr {
        let _ = handle.join();
    }
    Ok(())
}

fn curl_command(
    request: &PreparedRequest,
    _config: &str,
    body_path: Option<&Path>,
    is_head: bool,
) -> Command {
    let mut command = Command::new(OsString::from(CURL));
    command
        .env_remove("APS_CLI_AI_KEY")
        .arg("-q")
        .arg("--globoff")
        .arg("-sS")
        .arg("-N")
        .arg("--connect-timeout")
        .arg(CONNECT_TIMEOUT_SECONDS.to_string())
        .arg("--max-time")
        .arg(request.timeout.as_secs().max(1).to_string())
        .arg("--url")
        .arg(&request.url);
    if is_head {
        command.arg("--head");
    } else {
        command
            .arg("-X")
            .arg(request.method.as_str())
            .arg("-D")
            .arg("-");
    }
    match request.follow {
        RedirectPolicy::NoFollow => {}
        RedirectPolicy::FollowLimit(limit) => {
            command
                .arg("-L")
                .arg("--max-redirs")
                .arg(limit.min(MAX_REDIRECTS as u32).to_string());
        }
        RedirectPolicy::FollowAll => {
            command
                .arg("-L")
                .arg("--max-redirs")
                .arg(MAX_REDIRECTS.to_string());
        }
    }
    if let Some(path) = body_path {
        let mut argument = OsString::from("@");
        argument.push(path);
        command.arg("--data-binary").arg(argument);
    }
    command
        .arg("-K")
        .arg("-")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    command
}

/// Read response head blocks until the final one. curl prints one block per
/// response, so a followed redirect chain surfaces only the last block; an EOF
/// after a block (for example when the redirect limit stops the chain) keeps
/// that block instead of failing.
fn read_head(reader: &mut impl BufRead, follow: bool) -> http_client::Result<ResponseHead> {
    let mut last: Option<ResponseHead> = None;
    loop {
        let mut line = Vec::new();
        if reader
            .read_until(b'\n', &mut line)
            .map_err(|error| http_client::anyhow!(error))?
            == 0
        {
            return last.ok_or_else(|| http_client::anyhow!("no response head"));
        }
        let text = String::from_utf8_lossy(&line);
        let text = text.trim_end_matches(['\r', '\n']);
        let mut parts = text.splitn(3, ' ');
        let version = parts.next().unwrap_or_default();
        let Some(status) = parts.next().and_then(|value| value.parse::<u16>().ok()) else {
            return Err(http_client::anyhow!("malformed status line"));
        };
        let version = match version {
            "HTTP/1.0" => Version::HTTP_10,
            "HTTP/2" | "HTTP/2.0" => Version::HTTP_2,
            _ => Version::HTTP_11,
        };
        let mut headers = HeaderMap::new();
        loop {
            let mut header_line = Vec::new();
            if reader
                .read_until(b'\n', &mut header_line)
                .map_err(|error| http_client::anyhow!(error))?
                == 0
            {
                return Err(http_client::anyhow!("truncated response headers"));
            }
            let header_line = String::from_utf8_lossy(&header_line);
            let header_line = header_line.trim_end_matches(['\r', '\n']);
            if header_line.is_empty() {
                break;
            }
            let Some((name, value)) = header_line.split_once(':') else {
                continue;
            };
            if let (Ok(name), Ok(value)) = (
                HeaderName::from_bytes(name.trim().as_bytes()),
                HeaderValue::from_str(value.trim()),
            ) {
                headers.append(name, value);
            }
        }
        if (100..200).contains(&status) {
            continue;
        }
        let status = StatusCode::from_u16(status).map_err(|error| http_client::anyhow!(error))?;
        let redirection = (300..400).contains(&status.as_u16());
        last = Some(ResponseHead {
            status,
            version,
            headers,
        });
        if follow && redirection {
            continue;
        }
        return Ok(last.expect("a head block was just stored"));
    }
}

struct BodyReader {
    receiver: mpsc::Receiver<std::io::Result<Vec<u8>>>,
    current: Option<Cursor<Vec<u8>>>,
}

impl AsyncRead for BodyReader {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buffer: &mut [u8],
    ) -> Poll<std::io::Result<usize>> {
        let this = self.get_mut();
        loop {
            if let Some(current) = this.current.as_mut() {
                let read = current.read(buffer)?;
                if read > 0 {
                    return Poll::Ready(Ok(read));
                }
                this.current = None;
            }
            match Pin::new(&mut this.receiver).poll_next(cx) {
                Poll::Ready(Some(Ok(chunk))) => this.current = Some(Cursor::new(chunk)),
                Poll::Ready(Some(Err(error))) => return Poll::Ready(Err(error)),
                Poll::Ready(None) => return Poll::Ready(Ok(0)),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}
