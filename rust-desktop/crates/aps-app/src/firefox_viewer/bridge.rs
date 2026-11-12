//! Bounded, in-memory request/reply mailbox for one viewer page and document.

use std::{
    sync::{Arc, Mutex, Weak, mpsc},
    time::Duration,
};

use serde_json::{Value, json};

const MAX_PENDING: usize = 8;
const MAX_ISSUED_DOCUMENTS: usize = 16;
const MAX_REQUEST_BYTES: usize = 8 * 1024;
pub(crate) const MAX_RESPONSE_BYTES: usize = 65_536;
const CLOSED: &str = "Firefox query bridge is closed";
const UNAVAILABLE: &str = "Firefox query bridge state is unavailable";
const DOCUMENT_REPLACED: &str = "Firefox query document was replaced";

#[derive(Clone)]
pub(crate) struct QueryBridge {
    inner: Arc<BridgeInner>,
}

struct BridgeInner {
    page_id: String,
    state: Mutex<State>,
}

struct State {
    closed: bool,
    document_id: Option<String>,
    document_generation: u64,
    next_document_generation: u64,
    issued_documents: Vec<IssuedDocument>,
    next_request_id: u64,
    pending: Vec<Pending>,
}

struct IssuedDocument {
    id: String,
    generation: u64,
}

struct Pending {
    request_id: String,
    request: Option<Value>,
    expected_revision: Option<u64>,
    reply: mpsc::Sender<Result<Value, String>>,
}

pub(crate) struct QueryTicket {
    bridge: Weak<BridgeInner>,
    request_id: String,
    reply: mpsc::Receiver<Result<Value, String>>,
}

impl QueryBridge {
    pub(crate) fn new() -> Result<Self, String> {
        Ok(Self {
            inner: Arc::new(BridgeInner {
                page_id: super::random_token()?,
                state: Mutex::new(State {
                    closed: false,
                    document_id: None,
                    document_generation: 0,
                    next_document_generation: 0,
                    issued_documents: Vec::with_capacity(MAX_ISSUED_DOCUMENTS),
                    next_request_id: 0,
                    pending: Vec::new(),
                }),
            }),
        })
    }

    pub(crate) fn page_id(&self) -> String {
        self.inner.page_id.clone()
    }

    pub(crate) fn issue_document(&self) -> Result<String, String> {
        let mut state = self
            .inner
            .state
            .lock()
            .map_err(|_| UNAVAILABLE.to_owned())?;
        if state.closed {
            return Err(CLOSED.to_owned());
        }
        let generation = state
            .next_document_generation
            .checked_add(1)
            .ok_or_else(|| "Firefox query document generations are exhausted".to_owned())?;
        let id = super::random_token()?;
        if state.issued_documents.iter().any(|issued| issued.id == id) {
            return Err("Firefox query document ID collision".to_owned());
        }
        if state.issued_documents.len() == MAX_ISSUED_DOCUMENTS {
            let oldest = state
                .issued_documents
                .iter()
                .position(|issued| Some(issued.id.as_str()) != state.document_id.as_deref())
                .ok_or_else(|| "Firefox query document registry is unavailable".to_owned())?;
            state.issued_documents.remove(oldest);
        }
        state.issued_documents.push(IssuedDocument {
            id: id.clone(),
            generation,
        });
        state.next_document_generation = generation;
        Ok(id)
    }

    /// First attachment preserves queued work; a replacement cancels old work.
    pub(crate) fn attach_document(&self, document_id: &str) -> Result<(), String> {
        let mut state = self
            .inner
            .state
            .lock()
            .map_err(|_| UNAVAILABLE.to_owned())?;
        if state.closed {
            return Err(CLOSED.to_owned());
        }
        validate_document_id(document_id)?;
        let generation = state
            .issued_documents
            .iter()
            .find(|issued| issued.id == document_id)
            .map(|issued| issued.generation)
            .ok_or_else(|| "Firefox query document was not issued or has expired".to_owned())?;
        if state.document_id.as_deref() == Some(document_id) {
            return Ok(());
        }
        // Keep the active generation as a high-water mark even after older IDs are evicted.
        if generation <= state.document_generation {
            return Err(DOCUMENT_REPLACED.to_owned());
        }
        if state.document_id.is_some() {
            cancel_pending(&mut state, DOCUMENT_REPLACED);
        }
        state.document_id = Some(document_id.to_owned());
        state.document_generation = generation;
        Ok(())
    }

    pub(crate) fn submit(
        &self,
        method: &str,
        params: Value,
        expected_revision: Option<u64>,
    ) -> Result<QueryTicket, String> {
        let mut state = self
            .inner
            .state
            .lock()
            .map_err(|_| UNAVAILABLE.to_owned())?;
        if state.closed {
            return Err(CLOSED.to_owned());
        }
        if !matches!(
            method,
            "context"
                | "selection"
                | "callTree"
                | "function"
                | "threads"
                | "analysis"
                | "capabilities"
        ) {
            return Err("unsupported Firefox query method".to_owned());
        }
        if !params.is_object() {
            return Err("Firefox query params must be an object".to_owned());
        }
        for (key, maximum) in [("limit", 100), ("maxDepth", 64)] {
            if let Some(value) = params.get(key)
                && !value
                    .as_u64()
                    .is_some_and(|value| (1..=maximum).contains(&value))
            {
                return Err("Firefox query limit or maxDepth is out of range".to_owned());
            }
        }
        if expected_revision == Some(0) {
            return Err("Firefox query expected revision must be positive".to_owned());
        }
        if state.pending.len() >= MAX_PENDING {
            return Err("Firefox query bridge already has 8 pending requests".to_owned());
        }
        let next_id = state
            .next_request_id
            .checked_add(1)
            .ok_or_else(|| "Firefox query request IDs are exhausted".to_owned())?;
        let request_id = next_id.to_string();
        let mut request = json!({
            "protocolVersion": 1,
            "requestId": request_id,
            "method": method,
            "params": params,
        });
        if let Some(revision) = expected_revision {
            request["expectedRevision"] = json!(revision);
        }
        let bytes = serde_json::to_vec(&request)
            .map_err(|_| "Firefox query request cannot be encoded".to_owned())?;
        if bytes.len() > MAX_REQUEST_BYTES {
            return Err("Firefox query request exceeds 8 KiB".to_owned());
        }
        let (sender, receiver) = mpsc::channel();
        state.next_request_id = next_id;
        state.pending.push(Pending {
            request_id: request_id.clone(),
            request: Some(request),
            expected_revision,
            reply: sender,
        });
        Ok(QueryTicket {
            bridge: Arc::downgrade(&self.inner),
            request_id,
            reply: receiver,
        })
    }

    /// Local polling also waits for the first attachment. HTTP callers must use
    /// `poll_for_document` so an old document cannot dispatch another's work.
    #[cfg(test)]
    pub(crate) fn poll(&self) -> Value {
        let requests = match self.inner.state.lock() {
            Ok(mut state) if !state.closed && state.document_id.is_some() => dispatch(&mut state),
            _ => Vec::new(),
        };
        self.poll_body(requests)
    }

    pub(crate) fn poll_for_document(&self, document_id: &str) -> Result<Value, String> {
        let mut state = self
            .inner
            .state
            .lock()
            .map_err(|_| UNAVAILABLE.to_owned())?;
        if state.closed {
            return Err(CLOSED.to_owned());
        }
        validate_document_id(document_id)?;
        if state.document_id.as_deref() != Some(document_id) {
            return Err("Firefox query document is not active".to_owned());
        }
        Ok(self.poll_body(dispatch(&mut state)))
    }

    fn poll_body(&self, requests: Vec<Value>) -> Value {
        json!({ "protocolVersion": 1, "pageId": self.inner.page_id, "requests": requests })
    }

    pub(crate) fn complete(&self, body: &[u8]) -> Result<(), String> {
        if body.len() > MAX_RESPONSE_BYTES {
            return Err("Firefox query response exceeds 64 KiB".to_owned());
        }
        let mut response = aps_json::parse_slice(
            body,
            aps_json::Limits {
                max_bytes: MAX_RESPONSE_BYTES as u64,
                max_depth: 16,
                max_nodes: 8192,
                max_string_bytes: MAX_RESPONSE_BYTES,
                max_collection_items: 256,
            },
        )
        .map_err(|_| "invalid Firefox query response JSON".to_owned())?;
        let envelope = response
            .as_object()
            .filter(|fields| fields.len() == 5)
            .ok_or_else(|| "invalid Firefox query response envelope".to_owned())?;
        if envelope.get("protocolVersion").and_then(Value::as_u64) != Some(1) {
            return Err("unsupported Firefox query response protocol".to_owned());
        }
        if envelope.get("pageId").and_then(Value::as_str) != Some(self.inner.page_id.as_str()) {
            return Err("Firefox query response page is not current".to_owned());
        }
        let document_id = envelope
            .get("documentId")
            .and_then(Value::as_str)
            .ok_or_else(|| "invalid Firefox query response document ID".to_owned())?;
        let request_id = envelope
            .get("requestId")
            .and_then(Value::as_str)
            .ok_or_else(|| "invalid Firefox query response request ID".to_owned())?;
        let result = envelope
            .get("result")
            .and_then(Value::as_object)
            .filter(|fields| fields.len() == 3)
            .ok_or_else(|| "invalid Firefox query response result".to_owned())?;
        let revision = match result.get("ok").and_then(Value::as_bool) {
            Some(true) if result.contains_key("data") => Some(
                result
                    .get("revision")
                    .and_then(Value::as_u64)
                    .filter(|revision| *revision > 0)
                    .ok_or_else(|| "Firefox query response revision must be positive".to_owned())?,
            ),
            Some(false)
                if result.get("code").is_some_and(Value::is_string)
                    && result.get("message").is_some_and(Value::is_string) =>
            {
                None
            }
            _ => return Err("invalid Firefox query response result".to_owned()),
        };
        let mut state = self
            .inner
            .state
            .lock()
            .map_err(|_| UNAVAILABLE.to_owned())?;
        if state.closed {
            return Err(CLOSED.to_owned());
        }
        if state.document_id.as_deref() != Some(document_id) {
            return Err("Firefox query response document is not active".to_owned());
        }
        let index = state
            .pending
            .iter()
            .position(|pending| pending.request_id == request_id)
            .ok_or_else(|| "Firefox query response request is not pending".to_owned())?;
        let pending = &state.pending[index];
        if pending.request.is_some() {
            return Err("Firefox query response request has not been dispatched".to_owned());
        }
        if let (Some(expected), Some(actual)) = (pending.expected_revision, revision)
            && expected != actual
        {
            return Err(
                "Firefox query response revision does not match expected revision".to_owned(),
            );
        }
        let pending = state.pending.remove(index);
        pending
            .reply
            .send(Ok(response["result"].take()))
            .map_err(|_| "Firefox query response ticket is no longer waiting".to_owned())
    }

    pub(crate) fn close(&self) {
        let mut state = self
            .inner
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        state.closed = true;
        cancel_pending(&mut state, CLOSED);
    }
}

impl QueryTicket {
    /// Returns the result object, including remote `ok: false` results verbatim.
    /// Transport cancellation and timeout are errors, never remote payload text.
    pub(crate) fn wait(self, timeout: Duration) -> Result<Value, String> {
        match self.reply.recv_timeout(timeout) {
            Ok(result) => result,
            Err(mpsc::RecvTimeoutError::Timeout) => {
                Err("Firefox query request timed out".to_owned())
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => Err(CLOSED.to_owned()),
        }
    }
}

impl Drop for QueryTicket {
    fn drop(&mut self) {
        if let Some(bridge) = self.bridge.upgrade() {
            let mut state = bridge
                .state
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            state
                .pending
                .retain(|pending| pending.request_id != self.request_id);
        }
    }
}

fn validate_document_id(document_id: &str) -> Result<(), String> {
    if document_id.is_empty()
        || document_id.len() > 64
        || !document_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    {
        return Err("invalid Firefox query document ID".to_owned());
    }
    Ok(())
}

fn dispatch(state: &mut State) -> Vec<Value> {
    state
        .pending
        .iter_mut()
        .filter_map(|pending| pending.request.take())
        .collect()
}

fn cancel_pending(state: &mut State, reason: &str) {
    for pending in state.pending.drain(..) {
        let _ = pending.reply.send(Err(reason.to_owned()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{sync::Barrier, thread};

    const DOCUMENT: &str = "52cd2742-dc21-4d47-a1c5-698fdb9c91d1";

    fn register_document(bridge: &QueryBridge, id: &str) {
        let mut state = bridge.inner.state.lock().unwrap();
        assert!(!state.closed);
        assert!(state.issued_documents.len() < MAX_ISSUED_DOCUMENTS);
        assert!(!state.issued_documents.iter().any(|issued| issued.id == id));
        let generation = state.next_document_generation.checked_add(1).unwrap();
        state.issued_documents.push(IssuedDocument {
            id: id.to_owned(),
            generation,
        });
        state.next_document_generation = generation;
    }

    fn bridge() -> QueryBridge {
        let bridge = QueryBridge::new().unwrap();
        register_document(&bridge, DOCUMENT);
        bridge.attach_document(DOCUMENT).unwrap();
        bridge
    }

    fn response(bridge: &QueryBridge, request: &Value, result: Value) -> Value {
        json!({
            "protocolVersion": 1,
            "pageId": bridge.page_id(),
            "documentId": DOCUMENT,
            "requestId": request["requestId"],
            "result": result,
        })
    }

    fn complete(bridge: &QueryBridge, response: &Value) -> Result<(), String> {
        bridge.complete(&serde_json::to_vec(response).unwrap())
    }

    fn success(revision: u64) -> Value {
        json!({"ok": true, "revision": revision, "data": {"items": [1, 2, 3]}})
    }

    #[test]
    fn allows_only_whitelisted_methods_and_dispatches_once() {
        let bridge = bridge();
        for method in [
            "context",
            "selection",
            "callTree",
            "function",
            "threads",
            "analysis",
            "capabilities",
        ] {
            let ticket = bridge.submit(method, json!({}), None).unwrap();
            let batch = bridge.poll_for_document(DOCUMENT).unwrap();
            assert_eq!(batch["protocolVersion"], 1);
            assert_eq!(batch["pageId"], bridge.page_id());
            assert_eq!(
                batch["requests"],
                json!([{
                    "protocolVersion": 1, "requestId": ticket.request_id, "method": method, "params": {}
                }])
            );
            assert_eq!(bridge.poll()["requests"], json!([]));
        }
        for method in ["", "Context", "calltree", "upload", "private-payload"] {
            let error = bridge.submit(method, json!({}), None).err().unwrap();
            assert_eq!(error, "unsupported Firefox query method");
        }
    }

    #[test]
    fn validates_parameter_and_revision_bounds() {
        let bridge = bridge();
        for (limit, depth) in [(1, 1), (100, 64)] {
            let ticket = bridge
                .submit(
                    "callTree",
                    json!({"limit": limit, "maxDepth": depth}),
                    Some(u64::MAX),
                )
                .unwrap();
            assert_eq!(bridge.poll()["requests"][0]["expectedRevision"], u64::MAX);
            drop(ticket);
        }
        for (key, maximum) in [("limit", 100), ("maxDepth", 64)] {
            for value in [
                Value::Null,
                json!(false),
                json!("1"),
                json!(1.0),
                json!(-1),
                json!(0),
                json!(maximum + 1),
                json!([]),
                json!({}),
            ] {
                assert!(bridge.submit("context", json!({key: value}), None).is_err());
            }
        }
        assert!(bridge.submit("context", json!({}), Some(0)).is_err());
        assert!(bridge.poll()["requests"].as_array().unwrap().is_empty());
    }

    #[test]
    fn rejects_non_object_params_without_queueing_or_consuming_request_ids() {
        let bridge = bridge();
        for params in [
            Value::Null,
            json!(false),
            json!(true),
            json!(0),
            json!(1.5),
            json!("private payload"),
            json!([]),
            json!([{"limit": 1}]),
        ] {
            let error = bridge.submit("context", params, None).err().unwrap();
            assert_eq!(error, "Firefox query params must be an object");
            assert!(bridge.inner.state.lock().unwrap().pending.is_empty());
        }
        assert_eq!(bridge.poll()["requests"], json!([]));
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        assert_eq!(ticket.request_id, "1");
        assert_eq!(bridge.poll()["requests"][0]["params"], json!({}));
    }

    #[test]
    fn bounds_the_entire_encoded_request_including_escapes() {
        let bridge = bridge();
        let ticket = bridge
            .submit("context", json!({"text": ""}), Some(1))
            .unwrap();
        let baseline = serde_json::to_vec(&bridge.poll()["requests"][0])
            .unwrap()
            .len();
        drop(ticket);
        let available = MAX_REQUEST_BYTES - baseline;
        let ticket = bridge
            .submit("context", json!({"text": "a".repeat(available)}), Some(1))
            .unwrap();
        assert_eq!(
            serde_json::to_vec(&bridge.poll()["requests"][0])
                .unwrap()
                .len(),
            MAX_REQUEST_BYTES
        );
        drop(ticket);
        assert!(
            bridge
                .submit(
                    "context",
                    json!({"text": "a".repeat(available + 1)}),
                    Some(1)
                )
                .is_err()
        );
        assert!(
            bridge
                .submit("context", json!({"text": "\n".repeat(available)}), None)
                .is_err()
        );
        assert!(
            bridge
                .submit(
                    "context",
                    json!({"text": "\u{00e9}".repeat(available)}),
                    None
                )
                .is_err()
        );
        assert_eq!(bridge.poll()["requests"], json!([]));
    }

    #[test]
    fn at_most_eight_pending_including_dispatched_and_clones() {
        let bridge = bridge();
        let clone = bridge.clone();
        let mut tickets: Vec<_> = (0..MAX_PENDING)
            .map(|_| bridge.submit("context", json!({}), None).unwrap())
            .collect();
        assert!(clone.submit("context", json!({}), None).is_err());
        let batch = clone.poll();
        assert_eq!(batch["requests"].as_array().unwrap().len(), MAX_PENDING);
        assert_eq!(bridge.poll()["requests"], json!([]));
        assert!(bridge.submit("context", json!({}), None).is_err());
        let old_id = tickets.remove(0).request_id.clone();
        let ticket = clone.submit("context", json!({}), None).unwrap();
        assert_ne!(ticket.request_id, old_id);
        assert_eq!(bridge.poll()["requests"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn valid_pull_and_success_or_failure_response_delivers_result_only() {
        let bridge = bridge();
        for result in [
            success(7),
            json!({"ok": false, "code": "NOT_READY", "message": "private remote payload"}),
        ] {
            let ticket = bridge
                .submit("selection", json!({"limit": 1}), Some(7))
                .unwrap();
            let request = bridge.poll()["requests"][0].clone();
            complete(&bridge, &response(&bridge, &request, result.clone())).unwrap();
            assert_eq!(ticket.wait(Duration::ZERO).unwrap(), result);
            assert!(bridge.inner.state.lock().unwrap().pending.is_empty());
        }
    }

    #[test]
    fn rejects_undispatched_unknown_foreign_and_duplicate_replies() {
        let bridge = bridge();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        let request = json!({"requestId": ticket.request_id});
        let valid = response(&bridge, &request, success(1));
        assert!(
            complete(&bridge, &valid)
                .unwrap_err()
                .contains("dispatched")
        );
        bridge.poll();
        let mut foreign = valid.clone();
        foreign["pageId"] = json!("another-page");
        assert!(complete(&bridge, &foreign).unwrap_err().contains("page"));
        let mut unknown = valid.clone();
        unknown["requestId"] = json!("unknown");
        assert!(complete(&bridge, &unknown).unwrap_err().contains("pending"));
        complete(&bridge, &valid).unwrap();
        assert!(complete(&bridge, &valid).unwrap_err().contains("pending"));
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), success(1));
    }

    #[test]
    fn rejects_stale_revision_without_consuming_the_pending_request() {
        let bridge = bridge();
        let ticket = bridge.submit("context", json!({}), Some(9)).unwrap();
        let request = bridge.poll()["requests"][0].clone();
        for revision in [0, 8, 10] {
            assert!(complete(&bridge, &response(&bridge, &request, success(revision))).is_err());
        }
        complete(&bridge, &response(&bridge, &request, success(9))).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), success(9));
    }

    #[test]
    fn requires_exact_response_schema_and_field_types() {
        let bridge = bridge();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        let request = bridge.poll()["requests"][0].clone();
        let valid = response(&bridge, &request, success(u64::MAX));
        for key in [
            "protocolVersion",
            "pageId",
            "documentId",
            "requestId",
            "result",
        ] {
            let mut invalid = valid.clone();
            invalid.as_object_mut().unwrap().remove(key);
            assert!(complete(&bridge, &invalid).is_err());
        }
        let mut extra = valid.clone();
        extra["extra"] = json!(true);
        assert!(complete(&bridge, &extra).is_err());
        for (key, value) in [
            ("protocolVersion", json!(2)),
            ("protocolVersion", json!(1.0)),
            ("pageId", json!(1)),
            ("documentId", json!(1)),
            ("requestId", json!(1)),
        ] {
            let mut invalid = valid.clone();
            invalid[key] = value;
            assert!(complete(&bridge, &invalid).is_err());
        }
        for result in [
            Value::Null,
            json!([]),
            json!({}),
            json!({"ok": "true", "revision": 1, "data": null}),
            json!({"ok": true, "revision": 1}),
            json!({"ok": true, "revision": 1, "data": null, "extra": 1}),
            json!({"ok": true, "revision": -1, "data": null}),
            json!({"ok": true, "revision": 1.0, "data": null}),
            json!({"ok": true, "revision": "1", "data": null}),
            json!({"ok": false, "code": 1, "message": "private payload"}),
            json!({"ok": false, "code": "FAIL", "message": null}),
            json!({"ok": false, "code": "FAIL", "message": "private payload", "revision": 1}),
        ] {
            assert!(complete(&bridge, &response(&bridge, &request, result)).is_err());
        }
        complete(&bridge, &valid).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), success(u64::MAX));
    }

    #[test]
    fn bounds_responses_and_rejects_invalid_nonfinite_or_trailing_json() {
        let bridge = bridge();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        let request = bridge.poll()["requests"][0].clone();
        let mut valid = response(
            &bridge,
            &request,
            json!({"ok": true, "revision": 1, "data": ""}),
        );
        let baseline = serde_json::to_vec(&valid).unwrap().len();
        valid["result"]["data"] = json!("a".repeat(MAX_RESPONSE_BYTES - baseline));
        let mut bytes = serde_json::to_vec(&valid).unwrap();
        assert_eq!(bytes.len(), MAX_RESPONSE_BYTES);
        bytes.push(b' ');
        assert!(bridge.complete(&bytes).unwrap_err().contains("64 KiB"));
        for body in [
            b"private-invalid-payload".as_slice(),
            b"{} {}",
            b"{\"secret\":NaN}",
            b"{\"secret\":Infinity}",
            b"{\"secret\":1e400}",
            b"{\"secret\":-1e400}",
            &[0xff],
        ] {
            let error = bridge.complete(body).unwrap_err();
            assert_eq!(error, "invalid Firefox query response JSON");
            assert!(!error.contains("private"));
        }
        bytes.pop();
        bridge.complete(&bytes).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), valid["result"]);
    }

    #[test]
    fn timeout_and_drop_remove_queued_and_dispatched_requests() {
        let bridge = bridge();
        for dispatched in [false, true] {
            let ticket = bridge.submit("context", json!({}), None).unwrap();
            let id = ticket.request_id.clone();
            if dispatched {
                bridge.poll();
            }
            assert!(
                ticket
                    .wait(Duration::ZERO)
                    .unwrap_err()
                    .contains("timed out")
            );
            assert!(bridge.inner.state.lock().unwrap().pending.is_empty());
            assert_eq!(bridge.poll()["requests"], json!([]));
            assert!(
                complete(
                    &bridge,
                    &response(&bridge, &json!({"requestId": id}), success(1))
                )
                .is_err()
            );
            let ticket = bridge.submit("context", json!({}), None).unwrap();
            if dispatched {
                bridge.poll();
            }
            drop(ticket);
            assert!(bridge.inner.state.lock().unwrap().pending.is_empty());
        }
    }

    #[test]
    fn rejects_duplicate_keys_without_consuming_a_valid_reply() {
        let bridge = bridge();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        let request = bridge.poll()["requests"][0].clone();
        let valid = response(&bridge, &request, success(1));
        let encoded = serde_json::to_string(&valid).unwrap();
        let duplicate = encoded.replacen('{', "{\"protocolVersion\":1,", 1);
        assert!(bridge.complete(duplicate.as_bytes()).is_err());
        complete(&bridge, &valid).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), success(1));
    }

    #[test]
    fn close_cancels_all_tickets_and_future_operations_across_clones() {
        let bridge = bridge();
        let clone = bridge.clone();
        let first = bridge.submit("context", json!({}), None).unwrap();
        let request = bridge.poll()["requests"][0].clone();
        let queued = bridge.submit("context", json!({}), None).unwrap();
        clone.close();
        bridge.close();
        assert_eq!(first.wait(Duration::ZERO).unwrap_err(), CLOSED);
        assert_eq!(queued.wait(Duration::ZERO).unwrap_err(), CLOSED);
        assert_eq!(
            clone.submit("context", json!({}), None).err().unwrap(),
            CLOSED
        );
        assert_eq!(clone.attach_document("another").unwrap_err(), CLOSED);
        assert_eq!(clone.issue_document().unwrap_err(), CLOSED);
        assert_eq!(clone.poll_for_document(DOCUMENT).unwrap_err(), CLOSED);
        assert_eq!(
            complete(&clone, &response(&clone, &request, success(1))).unwrap_err(),
            CLOSED
        );
        assert_eq!(bridge.poll()["requests"], json!([]));
        assert!(bridge.inner.state.lock().unwrap().pending.is_empty());
    }

    #[test]
    fn dropping_last_bridge_cancels_wait_without_ticket_keeping_it_alive() {
        let bridge = bridge();
        let clone = bridge.clone();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        drop(bridge);
        assert_eq!(clone.poll()["requests"].as_array().unwrap().len(), 1);
        drop(clone);
        assert_eq!(ticket.wait(Duration::ZERO).unwrap_err(), CLOSED);
    }

    #[test]
    fn first_document_attach_preserves_queued_work_and_same_attach_is_idempotent() {
        let bridge = QueryBridge::new().unwrap();
        register_document(&bridge, DOCUMENT);
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        assert!(bridge.poll_for_document(DOCUMENT).is_err());
        assert_eq!(bridge.poll()["requests"], json!([]));
        bridge.attach_document(DOCUMENT).unwrap();
        let request = bridge.poll_for_document(DOCUMENT).unwrap()["requests"][0].clone();
        bridge.attach_document(DOCUMENT).unwrap();
        complete(&bridge, &response(&bridge, &request, success(1))).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), success(1));
    }

    #[test]
    fn validates_document_ids_without_replacing_the_active_document() {
        let bridge = bridge();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        for id in [
            "".to_owned(),
            "a".repeat(65),
            "a_b".to_owned(),
            "a b".to_owned(),
            "a/b".to_owned(),
            "\u{00e9}".to_owned(),
        ] {
            assert!(bridge.attach_document(&id).is_err());
            assert!(bridge.poll_for_document(&id).is_err());
        }
        assert_eq!(
            bridge.poll_for_document(DOCUMENT).unwrap()["requests"][0]["requestId"],
            ticket.request_id
        );
        for id in ["A0-z".to_owned(), "x".repeat(64)] {
            assert!(bridge.attach_document(&id).is_err());
            assert!(bridge.poll_for_document(&id).is_err());
        }
        let replacement = bridge.issue_document().unwrap();
        bridge.attach_document(&replacement).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap_err(), DOCUMENT_REPLACED);
    }

    #[test]
    fn replacing_document_cancels_old_work_and_rejects_old_poll_and_result() {
        let bridge = bridge();
        let dispatched = bridge.submit("context", json!({}), None).unwrap();
        let old_request = bridge.poll_for_document(DOCUMENT).unwrap()["requests"][0].clone();
        let queued = bridge.submit("context", json!({}), None).unwrap();
        let replacement = bridge.issue_document().unwrap();
        bridge.attach_document(&replacement).unwrap();
        assert_eq!(
            dispatched.wait(Duration::ZERO).unwrap_err(),
            DOCUMENT_REPLACED
        );
        assert_eq!(queued.wait(Duration::ZERO).unwrap_err(), DOCUMENT_REPLACED);
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        assert!(bridge.poll_for_document(DOCUMENT).is_err());
        assert!(complete(&bridge, &response(&bridge, &old_request, success(1))).is_err());
        let request = bridge.poll_for_document(&replacement).unwrap()["requests"][0].clone();
        assert_ne!(request["requestId"], old_request["requestId"]);
        let mut valid = response(&bridge, &request, success(1));
        assert!(complete(&bridge, &valid).unwrap_err().contains("document"));
        valid["documentId"] = json!(replacement);
        complete(&bridge, &valid).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), success(1));
    }

    #[test]
    fn issues_random_document_ids_without_activating_or_canceling_work() {
        let bridge = QueryBridge::new().unwrap();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        let first = bridge.issue_document().unwrap();
        let second = bridge.issue_document().unwrap();
        assert_ne!(first, second);
        for id in [&first, &second] {
            assert_eq!(id.len(), 64);
            assert!(
                id.bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
            );
        }
        {
            let state = bridge.inner.state.lock().unwrap();
            assert!(state.document_id.is_none());
            assert_eq!(state.document_generation, 0);
            assert_eq!(state.next_document_generation, 2);
            assert_eq!(state.issued_documents[0].generation, 1);
            assert_eq!(state.issued_documents[1].generation, 2);
            assert_eq!(state.pending.len(), 1);
        }
        bridge.attach_document(&first).unwrap();
        bridge.attach_document(&first).unwrap();
        let request = bridge.poll_for_document(&first).unwrap()["requests"][0].clone();
        let mut reply = response(&bridge, &request, success(1));
        reply["documentId"] = json!(first);
        complete(&bridge, &reply).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), success(1));
    }

    #[test]
    fn rejects_unissued_document_ids_without_consuming_queued_work() {
        let bridge = QueryBridge::new().unwrap();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        for id in [DOCUMENT.to_owned(), "0".repeat(64), "f".repeat(64)] {
            assert!(
                bridge
                    .attach_document(&id)
                    .unwrap_err()
                    .contains("not issued")
            );
            assert!(bridge.poll_for_document(&id).is_err());
        }
        let state = bridge.inner.state.lock().unwrap();
        assert!(state.document_id.is_none());
        assert_eq!(state.document_generation, 0);
        assert_eq!(state.next_document_generation, 0);
        assert!(state.issued_documents.is_empty());
        assert_eq!(state.pending.len(), 1);
        drop(state);
        drop(ticket);
    }

    #[test]
    fn superseded_document_cannot_reclaim_after_lost_attachment_reply() {
        let bridge = QueryBridge::new().unwrap();
        let first = bridge.issue_document().unwrap();
        let second = bridge.issue_document().unwrap();
        let old_ticket = bridge.submit("context", json!({}), None).unwrap();
        bridge.attach_document(&first).unwrap();
        bridge.poll_for_document(&first).unwrap();
        // The first attachment committed, but its HTTP response was lost.
        bridge.attach_document(&second).unwrap();
        assert_eq!(
            old_ticket.wait(Duration::ZERO).unwrap_err(),
            DOCUMENT_REPLACED
        );
        let current_ticket = bridge.submit("context", json!({}), None).unwrap();
        for _ in 0..3 {
            assert_eq!(
                bridge.attach_document(&first).unwrap_err(),
                DOCUMENT_REPLACED
            );
        }
        bridge.attach_document(&second).unwrap();
        assert!(bridge.poll_for_document(&first).is_err());
        let request = bridge.poll_for_document(&second).unwrap()["requests"][0].clone();
        let mut reply = response(&bridge, &request, success(1));
        reply["documentId"] = json!(second);
        complete(&bridge, &reply).unwrap();
        assert_eq!(current_ticket.wait(Duration::ZERO).unwrap(), success(1));
    }

    #[test]
    fn newer_first_attachment_rejects_a_delayed_older_initial_attachment() {
        let bridge = QueryBridge::new().unwrap();
        let older = bridge.issue_document().unwrap();
        let newer = bridge.issue_document().unwrap();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        bridge.attach_document(&newer).unwrap();
        assert_eq!(
            bridge.attach_document(&older).unwrap_err(),
            DOCUMENT_REPLACED
        );
        let request = bridge.poll_for_document(&newer).unwrap()["requests"][0].clone();
        let mut reply = response(&bridge, &request, success(1));
        reply["documentId"] = json!(newer);
        complete(&bridge, &reply).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), success(1));
    }

    #[test]
    fn issuing_without_an_active_document_evicts_oldest_ids_at_a_fixed_bound() {
        let bridge = QueryBridge::new().unwrap();
        let mut ids = Vec::new();
        for _ in 0..MAX_ISSUED_DOCUMENTS * 4 {
            ids.push(bridge.issue_document().unwrap());
            let state = bridge.inner.state.lock().unwrap();
            assert!(state.issued_documents.len() <= MAX_ISSUED_DOCUMENTS);
            assert_eq!(state.issued_documents.capacity(), MAX_ISSUED_DOCUMENTS);
        }
        {
            let state = bridge.inner.state.lock().unwrap();
            let retained: Vec<_> = state
                .issued_documents
                .iter()
                .map(|issued| &issued.id)
                .collect();
            assert_eq!(
                retained,
                ids[ids.len() - MAX_ISSUED_DOCUMENTS..]
                    .iter()
                    .collect::<Vec<_>>()
            );
            assert_eq!(state.next_document_generation, ids.len() as u64);
        }
        assert!(bridge.attach_document(&ids[0]).is_err());
        bridge.attach_document(ids.last().unwrap()).unwrap();
    }

    #[test]
    fn bounded_issuance_preserves_the_active_document_but_never_revives_evicted_ids() {
        let bridge = QueryBridge::new().unwrap();
        let active = bridge.issue_document().unwrap();
        bridge.attach_document(&active).unwrap();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        let expired = bridge.issue_document().unwrap();
        let mut latest = String::new();
        for _ in 0..MAX_ISSUED_DOCUMENTS * 4 {
            latest = bridge.issue_document().unwrap();
            let state = bridge.inner.state.lock().unwrap();
            assert!(state.issued_documents.len() <= MAX_ISSUED_DOCUMENTS);
            assert_eq!(state.issued_documents.capacity(), MAX_ISSUED_DOCUMENTS);
            assert!(
                state
                    .issued_documents
                    .iter()
                    .any(|issued| issued.id == active)
            );
            assert_eq!(state.document_generation, 1);
            assert_eq!(state.pending.len(), 1);
        }
        {
            let state = bridge.inner.state.lock().unwrap();
            let generations: Vec<_> = state
                .issued_documents
                .iter()
                .map(|issued| issued.generation)
                .collect();
            let mut expected = vec![1];
            expected.extend(
                state.next_document_generation - MAX_ISSUED_DOCUMENTS as u64 + 2
                    ..=state.next_document_generation,
            );
            assert_eq!(generations, expected);
        }
        assert!(bridge.attach_document(&expired).is_err());
        bridge.attach_document(&active).unwrap();
        bridge.attach_document(&latest).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap_err(), DOCUMENT_REPLACED);
        assert_eq!(
            bridge.attach_document(&active).unwrap_err(),
            DOCUMENT_REPLACED
        );
        bridge.issue_document().unwrap();
        assert!(
            !bridge
                .inner
                .state
                .lock()
                .unwrap()
                .issued_documents
                .iter()
                .any(|issued| issued.id == active)
        );
        assert!(bridge.attach_document(&active).is_err());
        bridge.attach_document(&latest).unwrap();
    }

    #[test]
    fn document_generation_exhaustion_fails_without_evicting_or_canceling_work() {
        let bridge = bridge();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        bridge.inner.state.lock().unwrap().next_document_generation = u64::MAX - 1;
        let last = bridge.issue_document().unwrap();
        let issued_before: Vec<_> = bridge
            .inner
            .state
            .lock()
            .unwrap()
            .issued_documents
            .iter()
            .map(|issued| (issued.id.clone(), issued.generation))
            .collect();
        assert!(bridge.issue_document().unwrap_err().contains("exhausted"));
        {
            let state = bridge.inner.state.lock().unwrap();
            assert_eq!(state.next_document_generation, u64::MAX);
            assert_eq!(state.document_id.as_deref(), Some(DOCUMENT));
            assert_eq!(state.pending.len(), 1);
            assert_eq!(
                state
                    .issued_documents
                    .iter()
                    .map(|issued| (issued.id.clone(), issued.generation))
                    .collect::<Vec<_>>(),
                issued_before
            );
        }
        bridge.attach_document(&last).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap_err(), DOCUMENT_REPLACED);
        assert_eq!(
            bridge.attach_document(DOCUMENT).unwrap_err(),
            DOCUMENT_REPLACED
        );
        bridge.attach_document(&last).unwrap();
    }

    #[test]
    fn concurrent_document_issuance_has_unique_ids_and_monotonic_bounded_generations() {
        let bridge = bridge();
        let mut ids = thread::scope(|scope| {
            let handles: Vec<_> = (0..4)
                .map(|_| {
                    let bridge = &bridge;
                    scope.spawn(move || {
                        (0..8)
                            .map(|_| bridge.issue_document().unwrap())
                            .collect::<Vec<_>>()
                    })
                })
                .collect();
            handles
                .into_iter()
                .flat_map(|handle| handle.join().unwrap())
                .collect::<Vec<_>>()
        });
        assert_eq!(ids.len(), 32);
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), 32);
        let state = bridge.inner.state.lock().unwrap();
        assert_eq!(state.next_document_generation, 33);
        assert_eq!(state.issued_documents.len(), MAX_ISSUED_DOCUMENTS);
        assert_eq!(state.issued_documents[0].id, DOCUMENT);
        assert!(
            state
                .issued_documents
                .windows(2)
                .all(|pair| pair[0].generation < pair[1].generation)
        );
    }

    #[test]
    fn replacing_page_invalidates_old_page_responses_even_if_request_ids_match() {
        let old = bridge();
        let old_ticket = old.submit("context", json!({}), None).unwrap();
        let old_request = old.poll()["requests"][0].clone();
        let stale = response(&old, &old_request, success(1));
        old.close();
        let new = bridge();
        assert_ne!(old.page_id(), new.page_id());
        let ticket = new.submit("context", json!({}), None).unwrap();
        let request = new.poll()["requests"][0].clone();
        assert_eq!(request["requestId"], old_request["requestId"]);
        assert!(complete(&new, &stale).unwrap_err().contains("page"));
        assert_eq!(old_ticket.wait(Duration::ZERO).unwrap_err(), CLOSED);
        complete(&new, &response(&new, &request, success(1))).unwrap();
        assert_eq!(ticket.wait(Duration::ZERO).unwrap(), success(1));
    }

    #[test]
    fn concurrent_pollers_dispatch_each_request_exactly_once() {
        let bridge = bridge();
        let _tickets: Vec<_> = (0..MAX_PENDING)
            .map(|_| bridge.submit("context", json!({}), None).unwrap())
            .collect();
        let barrier = Barrier::new(4);
        let count = thread::scope(|scope| {
            let handles: Vec<_> = (0..4)
                .map(|_| {
                    let bridge = &bridge;
                    let barrier = &barrier;
                    scope.spawn(move || {
                        barrier.wait();
                        bridge.poll_for_document(DOCUMENT).unwrap()["requests"]
                            .as_array()
                            .unwrap()
                            .len()
                    })
                })
                .collect();
            handles
                .into_iter()
                .map(|handle| handle.join().unwrap())
                .sum::<usize>()
        });
        assert_eq!(count, MAX_PENDING);
    }

    #[test]
    fn close_wakes_a_waiting_thread() {
        let bridge = bridge();
        let ticket = bridge.submit("context", json!({}), None).unwrap();
        thread::scope(|scope| {
            let waiter = scope.spawn(move || ticket.wait(Duration::from_secs(5)));
            bridge.close();
            assert_eq!(waiter.join().unwrap().unwrap_err(), CLOSED);
        });
    }
}
