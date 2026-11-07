use crate::{AiError, bounded_text};
use aps_json::Value;

#[derive(Debug, Clone, Copy)]
pub struct StreamLimits {
    pub max_wire_bytes: usize,
    pub max_line_bytes: usize,
    pub max_event_bytes: usize,
    pub max_content_bytes: usize,
    pub max_events: usize,
}

impl Default for StreamLimits {
    fn default() -> Self {
        Self {
            max_wire_bytes: 2 * 1024 * 1024,
            max_line_bytes: 128 * 1024,
            max_event_bytes: 128 * 1024,
            max_content_bytes: 256 * 1024,
            max_events: 10_000,
        }
    }
}

/// Text deltas are untrusted provider bytes, NOT validated findings or navigation.
/// The gateway keeps them internal and only returns a fully validated result.
pub enum StreamEvent {
    TextDelta(String),
    Finished,
}

/// Incremental OpenAI-compatible chat SSE decoder. All chunk boundaries, including
/// UTF-8 codepoints and CRLF pairs, may be split. EOF alone never proves success:
/// a stop finish_reason and a dispatched [DONE] event are both mandatory.
pub struct SseDecoder {
    limits: StreamLimits,
    wire_bytes: usize,
    content_bytes: usize,
    event_count: usize,
    line: Vec<u8>,
    data: String,
    event: Option<String>,
    first_line: bool,
    previous_cr: bool,
    stopped: bool,
    done: bool,
    failed: bool,
    usage_seen: bool,
    completion_id: Option<String>,
    model: Option<String>,
}

impl SseDecoder {
    pub fn new(limits: StreamLimits) -> Result<Self, AiError> {
        if limits.max_wire_bytes == 0
            || limits.max_wire_bytes > 8 * 1024 * 1024
            || limits.max_line_bytes == 0
            || limits.max_line_bytes > 256 * 1024
            || limits.max_event_bytes == 0
            || limits.max_event_bytes > 512 * 1024
            || limits.max_content_bytes == 0
            || limits.max_content_bytes > 1024 * 1024
            || limits.max_events == 0
            || limits.max_events > 20_000
        {
            return Err(AiError::InvalidConfiguration);
        }
        Ok(Self {
            limits,
            wire_bytes: 0,
            content_bytes: 0,
            event_count: 0,
            line: Vec::new(),
            data: String::new(),
            event: None,
            first_line: true,
            previous_cr: false,
            stopped: false,
            done: false,
            failed: false,
            usage_seen: false,
            completion_id: None,
            model: None,
        })
    }

    pub fn push(&mut self, bytes: &[u8]) -> Result<Vec<StreamEvent>, AiError> {
        if self.failed {
            return Err(AiError::InvalidResponse);
        }
        let result = self.push_inner(bytes);
        if result.is_err() {
            self.failed = true;
        }
        result
    }

    fn push_inner(&mut self, bytes: &[u8]) -> Result<Vec<StreamEvent>, AiError> {
        self.wire_bytes = self
            .wire_bytes
            .checked_add(bytes.len())
            .ok_or(AiError::ResponseLimit)?;
        if self.wire_bytes > self.limits.max_wire_bytes {
            return Err(AiError::ResponseLimit);
        }
        let mut events = Vec::new();
        for byte in bytes {
            if *byte == b'\n' && self.previous_cr {
                self.previous_cr = false;
                continue;
            }
            self.previous_cr = false;
            if *byte == b'\n' || *byte == b'\r' {
                self.previous_cr = *byte == b'\r';
                self.complete_line(&mut events)?;
            } else {
                if self.line.len() >= self.limits.max_line_bytes {
                    return Err(AiError::ResponseLimit);
                }
                self.line.push(*byte);
            }
        }
        Ok(events)
    }

    fn complete_line(&mut self, events: &mut Vec<StreamEvent>) -> Result<(), AiError> {
        let line = std::mem::take(&mut self.line);
        let mut line = std::str::from_utf8(&line).map_err(|_| AiError::InvalidResponse)?;
        if self.first_line {
            line = line.strip_prefix('\u{feff}').unwrap_or(line);
            self.first_line = false;
        }
        if line.contains('\0') {
            return Err(AiError::InvalidResponse);
        }
        if line.is_empty() {
            if !self.data.is_empty() {
                self.dispatch(events)?;
            }
            self.event = None;
            return Ok(());
        }
        if line.starts_with(':') {
            return Ok(());
        }
        if self.done {
            return Err(AiError::InvalidResponse);
        }
        let (field, value) = line.split_once(':').unwrap_or((line, ""));
        let value = value.strip_prefix(' ').unwrap_or(value);
        match field {
            "data" => {
                if self
                    .data
                    .len()
                    .saturating_add(value.len())
                    .saturating_add(1)
                    > self.limits.max_event_bytes
                {
                    return Err(AiError::ResponseLimit);
                }
                self.data.push_str(value);
                self.data.push('\n');
            }
            "event" => {
                if self.event.is_some() || value.len() > 64 {
                    return Err(AiError::InvalidResponse);
                }
                self.event = Some(value.into());
            }
            // These standard SSE transport fields are not analysis authority.
            "id" if value.len() <= 256 => {}
            "retry"
                if !value.is_empty()
                    && value.len() <= 10
                    && value.bytes().all(|byte| byte.is_ascii_digit()) => {}
            _ => return Err(AiError::InvalidResponse),
        }
        Ok(())
    }

    fn dispatch(&mut self, events: &mut Vec<StreamEvent>) -> Result<(), AiError> {
        self.event_count = self
            .event_count
            .checked_add(1)
            .ok_or(AiError::ResponseLimit)?;
        if self.event_count > self.limits.max_events {
            return Err(AiError::ResponseLimit);
        }
        if self
            .event
            .as_deref()
            .is_some_and(|event| !matches!(event, "" | "message"))
        {
            return Err(AiError::InvalidResponse);
        }
        let mut data = std::mem::take(&mut self.data);
        data.pop(); // Remove the final SSE data-line separator, not provider text.
        if data == "[DONE]" {
            if !self.stopped || self.done {
                return Err(AiError::TruncatedResponse);
            }
            self.done = true;
            events.push(StreamEvent::Finished);
            return Ok(());
        }
        let value = aps_json::parse_slice(
            data.as_bytes(),
            super::model::json_limits(self.limits.max_event_bytes as u64),
        )
        .map_err(|_| AiError::InvalidResponse)?;
        self.bind_metadata(&value)?;
        if value.get("error").is_some() {
            return Err(AiError::InvalidResponse);
        }
        let choices = value
            .get("choices")
            .and_then(Value::as_array)
            .ok_or(AiError::InvalidResponse)?;
        if choices.is_empty() {
            if !self.stopped || self.usage_seen || !value.get("usage").is_some_and(Value::is_object)
            {
                return Err(AiError::InvalidResponse);
            }
            self.usage_seen = true;
            return Ok(());
        }
        if choices.len() != 1 || self.stopped {
            return Err(AiError::InvalidResponse);
        }
        let choice = &choices[0];
        if choice.get("index").and_then(Value::as_u64) != Some(0) {
            return Err(AiError::InvalidResponse);
        }
        let delta = choice
            .get("delta")
            .and_then(Value::as_object)
            .ok_or(AiError::InvalidResponse)?;
        if delta.keys().any(|key| {
            !matches!(
                key.as_str(),
                "role" | "content" | "refusal" | "tool_calls" | "function_call"
            )
        }) {
            return Err(AiError::InvalidResponse);
        }
        if delta
            .get("role")
            .is_some_and(|value| value.as_str() != Some("assistant"))
        {
            return Err(AiError::InvalidResponse);
        }
        for field in ["refusal", "tool_calls", "function_call"] {
            if delta.get(field).is_some_and(|value| !value.is_null()) {
                return Err(AiError::InvalidResponse);
            }
        }
        if let Some(content) = delta.get("content").filter(|value| !value.is_null()) {
            let content = content.as_str().ok_or(AiError::InvalidResponse)?;
            self.content_bytes = self
                .content_bytes
                .checked_add(content.len())
                .ok_or(AiError::ResponseLimit)?;
            if self.content_bytes > self.limits.max_content_bytes {
                return Err(AiError::ResponseLimit);
            }
            if !content.is_empty() {
                events.push(StreamEvent::TextDelta(content.into()));
            }
        }
        match choice.get("finish_reason") {
            Some(Value::Null) => {}
            Some(Value::String(reason)) if reason == "stop" => {
                self.stopped = true;
            }
            Some(Value::String(reason)) if reason == "length" => {
                return Err(AiError::TruncatedResponse);
            }
            _ => return Err(AiError::InvalidResponse),
        }
        Ok(())
    }

    fn bind_metadata(&mut self, value: &Value) -> Result<(), AiError> {
        for (key, bound) in [("id", &mut self.completion_id), ("model", &mut self.model)] {
            let supplied = value
                .get(key)
                .and_then(Value::as_str)
                .ok_or(AiError::InvalidResponse)?;
            bounded_text(supplied, 256).map_err(|_| AiError::InvalidResponse)?;
            if bound.as_ref().is_some_and(|old| old != supplied) {
                return Err(AiError::InvalidResponse);
            }
            if bound.is_none() {
                *bound = Some(supplied.into());
            }
        }
        if value.get("object").and_then(Value::as_str) != Some("chat.completion.chunk") {
            return Err(AiError::InvalidResponse);
        }
        Ok(())
    }

    pub fn effective_model(&self) -> Option<&str> {
        self.model.as_deref()
    }
    pub fn content_bytes(&self) -> usize {
        self.content_bytes
    }

    pub fn finish(self) -> Result<(), AiError> {
        if self.failed {
            return Err(AiError::InvalidResponse);
        }
        if !self.done
            || !self.stopped
            || !self.line.is_empty()
            || !self.data.is_empty()
            || self.event.is_some()
        {
            return Err(AiError::TruncatedResponse);
        }
        Ok(())
    }
}
