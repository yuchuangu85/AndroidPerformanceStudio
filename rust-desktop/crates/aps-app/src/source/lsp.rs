//! Minimal local-only LSP client for semantic source navigation.
//!
//! The client speaks JSON-RPC 2.0 over stdio, launches exactly one server per
//! workspace/language, and never sends source text anywhere except the local
//! server process. Servers are opt-in through Settings and discovered from
//! APS_JDTLS / APS_KOTLIN_LSP / APS_CLANGD or the PATH.

use serde_json::{Value, json};
use std::{
    io::{BufRead, BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::{
        Arc, Mutex,
        mpsc::{self, Receiver, RecvTimeoutError, Sender},
    },
    thread,
    time::{Duration, Instant},
};

const INITIALIZE_TIMEOUT: Duration = Duration::from_secs(30);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);
const MAX_FRAME_BYTES: usize = 128 * 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum NavigationKind {
    Definition,
    References,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct Location {
    pub path: PathBuf,
    /// Zero-based line.
    pub line: usize,
}

#[derive(Clone, Debug)]
pub(super) struct NavigationResult {
    pub kind: NavigationKind,
    pub identifier: String,
    pub locations: Vec<Location>,
}

#[derive(Debug)]
struct ServerSpec {
    program: PathBuf,
    args: Vec<String>,
}

struct SessionKey {
    root: PathBuf,
    language_id: String,
    program: PathBuf,
}

struct Session {
    child: Child,
    stdin: ChildStdin,
    messages: Receiver<Result<Value, String>>,
    key: SessionKey,
    next_id: i64,
    open_uri: Option<String>,
}

#[derive(Clone)]
pub(super) struct LspManager {
    inner: Arc<Mutex<Option<Session>>>,
    data_dir: PathBuf,
}

impl LspManager {
    pub(super) fn new(data_dir: PathBuf) -> Self {
        Self {
            inner: Arc::new(Mutex::new(None)),
            data_dir,
        }
    }

    pub(super) fn shutdown(&self) {
        if let Ok(mut session) = self.inner.lock() {
            *session = None;
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub(super) fn navigate(
        &self,
        root: &Path,
        language_id: &str,
        file_path: &Path,
        text: &str,
        line: u32,
        character: u32,
        kind: NavigationKind,
        identifier: String,
    ) -> Result<NavigationResult, String> {
        let spec = server_spec(language_id, &self.data_dir)?;
        let key = SessionKey {
            root: root.to_path_buf(),
            language_id: language_id.to_owned(),
            program: spec.program.clone(),
        };
        let mut guard = self
            .inner
            .lock()
            .map_err(|_| "semantic navigation session is poisoned".to_owned())?;
        let must_start = guard
            .as_ref()
            .is_none_or(|session| !session.key.matches(&key));
        if must_start {
            *guard = None;
            *guard = Some(Session::start(key, spec)?);
        }
        let session = guard
            .as_mut()
            .ok_or_else(|| "semantic navigation session is unavailable".to_owned())?;
        session.open_document(file_path, language_id, text)?;
        let result = match kind {
            NavigationKind::Definition => session.definition(file_path, line, character),
            NavigationKind::References => session.references(file_path, line, character),
        };
        if result.is_err() {
            *guard = None;
        }
        result.map(|locations| NavigationResult {
            kind,
            identifier,
            locations,
        })
    }
}

impl SessionKey {
    fn matches(&self, other: &Self) -> bool {
        self.root == other.root
            && self.language_id == other.language_id
            && self.program == other.program
    }
}

impl Session {
    fn start(key: SessionKey, spec: ServerSpec) -> Result<Self, String> {
        let mut command = Command::new(&spec.program);
        command
            .args(&spec.args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        let mut child = command.spawn().map_err(|error| {
            format!(
                "cannot start language server {}: {error}",
                spec.program.display()
            )
        })?;
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| "language server stdin is unavailable".to_owned())?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| "language server stdout is unavailable".to_owned())?;
        let (sender, receiver) = mpsc::channel();
        thread::Builder::new()
            .name("aps-lsp-reader".into())
            .spawn(move || read_messages(stdout, sender))
            .map_err(|error| format!("cannot start language server reader: {error}"))?;
        let mut session = Self {
            child,
            stdin,
            messages: receiver,
            key,
            next_id: 1,
            open_uri: None,
        };
        session.initialize()?;
        Ok(session)
    }

    fn initialize(&mut self) -> Result<(), String> {
        let root_uri = path_to_uri(&self.key.root);
        let result = self.request(
            "initialize",
            json!({
                "processId": std::process::id(),
                "clientInfo": {"name": "Android Performance Studio"},
                "rootUri": root_uri,
                "capabilities": {
                    "textDocument": {
                        "definition": {"linkSupport": true},
                        "references": {}
                    }
                }
            }),
            INITIALIZE_TIMEOUT,
        )?;
        if !result.is_object() {
            return Err("language server returned an invalid initialize response".to_owned());
        }
        self.notify("initialized", json!({}))
    }

    fn open_document(
        &mut self,
        file_path: &Path,
        language_id: &str,
        text: &str,
    ) -> Result<(), String> {
        let uri = path_to_uri(file_path);
        if self.open_uri.as_deref() == Some(uri.as_str()) {
            return Ok(());
        }
        if let Some(previous) = self.open_uri.take() {
            self.notify(
                "textDocument/didClose",
                json!({"textDocument": {"uri": previous}}),
            )?;
        }
        self.notify(
            "textDocument/didOpen",
            json!({
                "textDocument": {
                    "uri": uri,
                    "languageId": language_id,
                    "version": 1,
                    "text": text,
                }
            }),
        )?;
        self.open_uri = Some(uri);
        Ok(())
    }

    fn definition(
        &mut self,
        file_path: &Path,
        line: u32,
        character: u32,
    ) -> Result<Vec<Location>, String> {
        let result = self.request(
            "textDocument/definition",
            self.position_params(file_path, line, character),
            REQUEST_TIMEOUT,
        )?;
        Ok(parse_locations(&result))
    }

    fn references(
        &mut self,
        file_path: &Path,
        line: u32,
        character: u32,
    ) -> Result<Vec<Location>, String> {
        let mut params = self.position_params(file_path, line, character);
        if let Some(object) = params.as_object_mut() {
            object.insert("context".into(), json!({"includeDeclaration": true}));
        }
        let result = self.request("textDocument/references", params, REQUEST_TIMEOUT)?;
        Ok(parse_locations(&result))
    }

    fn position_params(&self, file_path: &Path, line: u32, character: u32) -> Value {
        json!({
            "textDocument": {"uri": path_to_uri(file_path)},
            "position": {"line": line, "character": character}
        })
    }

    fn request(&mut self, method: &str, params: Value, timeout: Duration) -> Result<Value, String> {
        let id = self.next_id;
        self.next_id += 1;
        self.send(&json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params}))?;
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(format!("language server timed out on {method}"));
            }
            match self.messages.recv_timeout(remaining) {
                Ok(Ok(message)) => {
                    if message.get("id").and_then(Value::as_i64) == Some(id) {
                        if let Some(error) = message.get("error") {
                            return Err(format!(
                                "language server returned an error for {method}: {}",
                                error
                                    .get("message")
                                    .and_then(Value::as_str)
                                    .unwrap_or("unknown error")
                            ));
                        }
                        return Ok(message.get("result").cloned().unwrap_or(Value::Null));
                    }
                    // Notifications and server requests are ignored until the
                    // matching response arrives; diagnostics are not rendered.
                }
                Ok(Err(error)) => return Err(error),
                Err(RecvTimeoutError::Timeout) => {
                    if Instant::now() >= deadline {
                        return Err(format!("language server timed out on {method}"));
                    }
                }
                Err(RecvTimeoutError::Disconnected) => {
                    return Err("language server stopped unexpectedly".to_owned());
                }
            }
        }
    }

    fn notify(&mut self, method: &str, params: Value) -> Result<(), String> {
        self.send(&json!({"jsonrpc": "2.0", "method": method, "params": params}))
    }

    fn send(&mut self, value: &Value) -> Result<(), String> {
        let body = serde_json::to_vec(value)
            .map_err(|error| format!("cannot encode language server request: {error}"))?;
        write_framed(&mut self.stdin, &body)
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn read_messages(stdout: impl Read, sender: Sender<Result<Value, String>>) {
    let mut reader = BufReader::new(stdout);
    loop {
        match read_frame(&mut reader) {
            Ok(Some(body)) => {
                let message = serde_json::from_slice(&body)
                    .map_err(|error| format!("language server sent malformed JSON: {error}"));
                let failed = message.is_err();
                if sender.send(message).is_err() || failed {
                    return;
                }
            }
            Ok(None) => return,
            Err(error) => {
                let _ = sender.send(Err(error));
                return;
            }
        }
    }
}

fn read_frame(reader: &mut impl BufRead) -> Result<Option<Vec<u8>>, String> {
    let mut content_length = None;
    loop {
        let mut line = String::new();
        let read = reader
            .read_line(&mut line)
            .map_err(|error| format!("cannot read language server header: {error}"))?;
        if read == 0 {
            return Ok(None);
        }
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed.is_empty() {
            break;
        }
        if let Some(value) = trimmed.strip_prefix("Content-Length:") {
            content_length = value.trim().parse::<usize>().ok();
        }
    }
    let length = content_length
        .ok_or_else(|| "language server response has no Content-Length".to_owned())?;
    if length > MAX_FRAME_BYTES {
        return Err("language server response exceeds the frame bound".to_owned());
    }
    let mut body = vec![0u8; length];
    reader
        .read_exact(&mut body)
        .map_err(|error| format!("cannot read language server body: {error}"))?;
    Ok(Some(body))
}

fn write_framed(writer: &mut impl Write, body: &[u8]) -> Result<(), String> {
    write!(writer, "Content-Length: {}\r\n\r\n", body.len())
        .map_err(|error| format!("cannot write language server header: {error}"))?;
    writer
        .write_all(body)
        .map_err(|error| format!("cannot write language server body: {error}"))?;
    writer
        .flush()
        .map_err(|error| format!("cannot flush language server request: {error}"))
}

fn server_spec(language_id: &str, data_dir: &Path) -> Result<ServerSpec, String> {
    let (variable, defaults, extra_args, data_arg) = match language_id {
        "java" => (
            "APS_JDTLS",
            &["jdtls", "jdt-language-server"][..],
            split_args("APS_JDTLS_ARGS"),
            true,
        ),
        "kotlin" => (
            "APS_KOTLIN_LSP",
            &["kotlin-lsp"][..],
            split_args("APS_KOTLIN_LSP_ARGS"),
            false,
        ),
        "c" | "cpp" | "objective-c" | "objective-cpp" => (
            "APS_CLANGD",
            &["clangd"][..],
            split_args("APS_CLANGD_ARGS"),
            false,
        ),
        other => {
            return Err(format!(
                "no local language server is configured for {other}"
            ));
        }
    };
    let program = std::env::var_os(variable)
        .map(PathBuf::from)
        .filter(|path| path.is_file())
        .or_else(|| defaults.iter().find_map(|name| find_in_path(name)))
        .ok_or_else(|| format!("{variable} is not set and no default server was found"))?;
    let mut args = extra_args;
    if data_arg {
        let server_data = data_dir.join("lsp");
        std::fs::create_dir_all(&server_data)
            .map_err(|error| format!("cannot create language server data directory: {error}"))?;
        args.push("-data".to_owned());
        args.push(server_data.to_string_lossy().into_owned());
    }
    Ok(ServerSpec { program, args })
}

fn split_args(variable: &str) -> Vec<String> {
    std::env::var(variable)
        .ok()
        .map(|value| value.split_whitespace().map(str::to_owned).collect())
        .unwrap_or_default()
}

fn find_in_path(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|directory| directory.join(name))
        .find(|candidate| candidate.is_file())
}

fn parse_locations(value: &Value) -> Vec<Location> {
    match value {
        Value::Array(items) => items.iter().flat_map(parse_locations).collect(),
        Value::Object(object) => {
            let uri = object
                .get("uri")
                .or_else(|| object.get("targetUri"))
                .and_then(Value::as_str);
            let Some(uri) = uri else {
                return Vec::new();
            };
            let Some(path) = uri_to_path(uri) else {
                return Vec::new();
            };
            let range = object
                .get("range")
                .or_else(|| object.get("targetSelectionRange"))
                .or_else(|| object.get("targetRange"));
            let line = range
                .and_then(|range| range.get("start"))
                .and_then(|start| start.get("line"))
                .and_then(Value::as_u64)
                .unwrap_or(0) as usize;
            vec![Location { path, line }]
        }
        _ => Vec::new(),
    }
}

/// Byte offset to LSP (line, character), using UTF-16 code units.
pub(super) fn position_for_offset(text: &str, offset: usize) -> (u32, u32) {
    let offset = offset.min(text.len());
    let line_start = text[..offset].rfind('\n').map_or(0, |index| index + 1);
    let line = text[..offset].bytes().filter(|byte| *byte == b'\n').count();
    let character = text[line_start..offset].encode_utf16().count();
    (line as u32, character as u32)
}

/// Identifier under (or immediately after) the byte offset and its start.
pub(super) fn identifier_at(text: &str, offset: usize) -> Option<(&str, usize)> {
    let mut cursor = offset.min(text.len());
    while cursor > 0 && !text.is_char_boundary(cursor) {
        cursor -= 1;
    }
    let is_ident =
        |character: char| character.is_alphanumeric() || character == '_' || character == '$';
    let mut start = cursor;
    for (index, character) in text[..cursor].char_indices().rev() {
        if !is_ident(character) {
            break;
        }
        start = index;
    }
    let mut end = cursor;
    for (index, character) in text[cursor..].char_indices() {
        if !is_ident(character) {
            break;
        }
        end = cursor + index + character.len_utf8();
    }
    if start == end {
        return None;
    }
    Some((&text[start..end], start))
}

pub(super) fn path_to_uri(path: &Path) -> String {
    let mut uri = String::from("file://");
    for byte in path.to_string_lossy().bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'/' | b'-' | b'_' | b'.' | b'~') {
            uri.push(byte as char);
        } else {
            uri.push('%');
            uri.push_str(&format!("{byte:02X}"));
        }
    }
    uri
}

pub(super) fn uri_to_path(uri: &str) -> Option<PathBuf> {
    let rest = uri.strip_prefix("file://")?;
    let rest = rest.strip_prefix("localhost").unwrap_or(rest);
    let mut bytes = Vec::with_capacity(rest.len());
    let raw = rest.as_bytes();
    let mut index = 0;
    while index < raw.len() {
        if raw[index] == b'%' && index + 2 < raw.len() {
            let hex = std::str::from_utf8(&raw[index + 1..index + 3]).ok()?;
            bytes.push(u8::from_str_radix(hex, 16).ok()?);
            index += 3;
        } else {
            bytes.push(raw[index]);
            index += 1;
        }
    }
    Some(PathBuf::from(String::from_utf8(bytes).ok()?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identifier_and_positions_are_byte_and_utf16_accurate() {
        let text = "val 名称 = target\nfun target() = 1\n";
        let offset = text.find("target").unwrap();
        assert_eq!(identifier_at(text, offset + 2), Some(("target", offset)));
        assert_eq!(position_for_offset(text, offset), (0, 9));
        assert_eq!(position_for_offset(text, text.find("fun").unwrap()), (1, 0));
    }

    #[test]
    fn file_uris_roundtrip_spaces_and_unicode() {
        let path = Path::new("/tmp/source dir/日本語.kt");
        let uri = path_to_uri(path);
        assert!(uri.starts_with("file:///tmp/source%20dir/"));
        assert_eq!(uri_to_path(&uri).as_deref(), Some(path));
        assert_eq!(uri_to_path("https://example.com/x"), None);
    }

    #[test]
    fn locations_accept_definition_and_location_link_shapes() {
        let value = json!([
            {"uri": "file:///tmp/a.kt", "range": {"start": {"line": 4, "character": 1}}},
            {"targetUri": "file:///tmp/b.kt", "targetSelectionRange": {"start": {"line": 8}}}
        ]);
        assert_eq!(
            parse_locations(&value),
            vec![
                Location {
                    path: PathBuf::from("/tmp/a.kt"),
                    line: 4
                },
                Location {
                    path: PathBuf::from("/tmp/b.kt"),
                    line: 8
                }
            ]
        );
    }

    #[test]
    fn stdio_session_speaks_definition_and_references() {
        use std::fs;
        use std::os::unix::fs::PermissionsExt;

        let root = std::env::temp_dir().join(format!("aps-lsp-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("src")).unwrap();
        let script = root.join("fake-lsp.py");
        fs::write(
            &script,
            r#"#!/usr/bin/env python3
import json, sys

def read_message():
    length = None
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None
        line = line.decode().strip()
        if not line:
            break
        if line.lower().startswith("content-length:"):
            length = int(line.split(":", 1)[1].strip())
    return json.loads(sys.stdin.buffer.read(length))

def send(payload):
    body = json.dumps(payload).encode()
    sys.stdout.buffer.write(("Content-Length: %d\r\n\r\n" % len(body)).encode() + body)
    sys.stdout.buffer.flush()

while True:
    message = read_message()
    if message is None:
        break
    method = message.get("method")
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": message["id"], "result": {"capabilities": {}}})
    elif method == "textDocument/definition":
        send({"jsonrpc": "2.0", "id": message["id"], "result": [
            {"uri": "file:///tmp/definition.kt", "range": {"start": {"line": 7, "character": 0}}}
        ]})
    elif method == "textDocument/references":
        send({"jsonrpc": "2.0", "id": message["id"], "result": [
            {"uri": "file:///tmp/one.kt", "range": {"start": {"line": 1, "character": 0}}},
            {"uri": "file:///tmp/two.kt", "range": {"start": {"line": 2, "character": 0}}}
        ]})
"#,
        )
        .unwrap();
        let mut permissions = fs::metadata(&script).unwrap().permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&script, permissions).unwrap();
        let previous = std::env::var_os("APS_CLANGD");
        unsafe {
            std::env::set_var("APS_CLANGD", &script);
        }
        let manager = LspManager::new(root.join("data"));
        let source = root.join("src/example.cpp");
        let definition = manager
            .navigate(
                &root,
                "cpp",
                &source,
                "int main() { return 0; }\n",
                0,
                4,
                NavigationKind::Definition,
                "main".into(),
            )
            .unwrap();
        assert_eq!(
            definition.locations,
            vec![Location {
                path: PathBuf::from("/tmp/definition.kt"),
                line: 7
            }]
        );
        let references = manager
            .navigate(
                &root,
                "cpp",
                &source,
                "int main() { return 0; }\n",
                0,
                4,
                NavigationKind::References,
                "main".into(),
            )
            .unwrap();
        assert_eq!(references.locations.len(), 2);
        manager.shutdown();
        match previous {
            Some(value) => unsafe {
                std::env::set_var("APS_CLANGD", value);
            },
            None => unsafe {
                std::env::remove_var("APS_CLANGD");
            },
        }
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    #[ignore = "requires clangd on PATH; manual semantic-navigation smoke"]
    fn clangd_definition_smoke() {
        use std::fs;
        let root = std::env::temp_dir().join(format!("aps-clangd-smoke-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).unwrap();
        let file = root.join("main.cpp");
        let text = "int add(int a, int b) { return a + b; }\nint main() { return add(1, 2); }\n";
        fs::write(&file, text).unwrap();
        let manager = LspManager::new(root.join("data"));
        let column = text.lines().nth(1).unwrap().find("add").unwrap() as u32;
        let result = manager
            .navigate(
                &root,
                "cpp",
                &file,
                text,
                1,
                column,
                NavigationKind::Definition,
                "add".into(),
            )
            .unwrap();
        eprintln!("clangd definitions: {:?}", result.locations);
        let expected = fs::canonicalize(&file).unwrap();
        assert!(result.locations.iter().any(|location| {
            fs::canonicalize(&location.path).is_ok_and(|path| path == expected)
                && location.line == 0
        }));
        manager.shutdown();
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn unknown_language_reports_a_configuration_error() {
        let error = server_spec("ruby", Path::new("/tmp")).unwrap_err();
        assert!(error.contains("no local language server"));
    }
}
