//! Bounded SQLite conversation/results/audit/chat store.
//!
//! Compose parity with SqliteConversationRepository and ConversationHistory.kt.
//! The database is Rust-owned and must live at an explicitly supplied absolute
//! path whose parent already exists. The database file, its journal/sidecar
//! paths and every ancestor are rejected when they are symbolic links. Journal
//! mode is DELETE (Compose parity) with a 5 s busy timeout, foreign keys on and
//! a 1 MiB SQLite row limit. Every mutation runs in an immediate transaction;
//! every JSON document is decoded through bounded aps_json limits and checked
//! for identity before use. Errors are stable E_ codes only; SQL, paths and
//! stored text are never attached.

use crate::runtime::{RunStatus, SessionSummary};
use crate::{AnalysisResult, FindingSeverity};
use aps_json::{Map, Value};
use rusqlite::{Connection, OpenFlags, OptionalExtension, TransactionBehavior, params};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// Recommended file name for the Rust-owned database. Not enforced: callers
/// pass an explicit absolute path under their own Rust data directory.
pub const CONVERSATION_DATABASE_FILE_NAME: &str = "ai-conversations.sqlite3";

pub const MAX_CONVERSATIONS: usize = 10_000;
pub const MAX_PAGE_SIZE: usize = 1000;
pub const MAX_RECORD_BYTES: usize = 1_048_576;
pub const MAX_RESULT_BYTES: usize = 262_144;
pub const MAX_QUESTION_BYTES: usize = 4_096;
pub const MAX_TITLE_BYTES: usize = 120;
pub const MAX_AUDIT_PER_RUN: usize = 1_024;
pub const MAX_TURNS: usize = 1_000;
pub const MAX_FINDINGS: usize = 12;
pub const MAX_ARTIFACT_HASHES: usize = 8;
pub const MAX_EVIDENCE_IDS: usize = 100;

const CHAT_TURN_QUESTION_BYTES: usize = 16_384;
const CHAT_TURN_PARENT_BYTES: usize = 512;
const CHAT_TITLE_BYTES: usize = 480;
const SUMMARY_BYTES: usize = 16_384;
const TITLE_CHARS: usize = 120;
const DEFAULT_TITLE: &str = "Analysis session";

// ---------------------------------------------------------------------------
// Public records
// ---------------------------------------------------------------------------

/// One persisted run reference. No provider output, prompt or source text.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConversationTurn {
    pub run_id: String,
    pub generation: u64,
    pub status: RunStatus,
    pub evidence_ids: Vec<String>,
    pub candidate_ids: Vec<String>,
    pub finding_ids: Vec<String>,
    pub error_code: Option<String>,
}

/// Reference-only conversation document stored as bounded JSON.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConversationRecord {
    pub id: String,
    pub analysis_session_id: String,
    pub context_hash: String,
    pub parent_conversation_id: Option<String>,
    pub turns: Vec<ConversationTurn>,
}

impl ConversationRecord {
    pub fn new(
        id: impl Into<String>,
        analysis_session_id: impl Into<String>,
        context_hash: impl Into<String>,
    ) -> Result<Self, String> {
        let record = Self {
            id: id.into(),
            analysis_session_id: analysis_session_id.into(),
            context_hash: context_hash.into(),
            parent_conversation_id: None,
            turns: Vec::new(),
        };
        validate_record(&record)?;
        Ok(record)
    }

    pub fn with_parent(mut self, parent_conversation_id: Option<String>) -> Result<Self, String> {
        self.parent_conversation_id = parent_conversation_id;
        validate_record(&self)?;
        Ok(self)
    }

    pub fn last_turn(&self) -> Option<&ConversationTurn> {
        self.turns.last()
    }
}

/// Bounded display projection of one chat question. The question is already
/// projected/redacted by the caller before it reaches storage.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredChatTurn {
    pub conversation_id: String,
    pub run_id: String,
    pub generation: u64,
    pub question: Option<String>,
    pub created_at_millis: i64,
    pub parent_run_id: Option<String>,
}

/// Alias kept for callers that use the ConversationHistory.kt name.
pub type ChatTurnRecord = StoredChatTurn;

impl StoredChatTurn {
    pub fn new(
        conversation_id: impl Into<String>,
        run_id: impl Into<String>,
        generation: u64,
        question: Option<String>,
        created_at_millis: i64,
    ) -> Result<Self, String> {
        let turn = Self {
            conversation_id: conversation_id.into(),
            run_id: run_id.into(),
            generation,
            question,
            created_at_millis,
            parent_run_id: None,
        };
        validate_chat_turn(&turn)?;
        Ok(turn)
    }

    pub fn with_parent(mut self, parent_run_id: Option<String>) -> Result<Self, String> {
        self.parent_run_id = parent_run_id;
        validate_chat_turn(&self)?;
        Ok(self)
    }
}

/// Redacted, validated projection of one analysis finding.
#[derive(Debug, Clone, PartialEq)]
pub struct StoredFinding {
    pub id: String,
    pub severity: FindingSeverity,
    pub title: String,
    pub explanation: String,
    pub recommendation: String,
    pub confidence: f64,
    pub evidence_ids: Vec<String>,
    pub candidate_ids: Vec<String>,
}

/// Validated, redacted analysis result projection. Never a raw provider body.
#[derive(Debug, Clone, PartialEq)]
pub struct StoredResult {
    pub conversation_id: String,
    pub run_id: String,
    pub generation: u64,
    pub session_id: String,
    pub model: String,
    pub summary: String,
    pub findings: Vec<StoredFinding>,
    pub saved_at_millis: i64,
    pub question_hash: Option<String>,
    pub parent_run_id: Option<String>,
    pub artifact_hashes: Vec<String>,
}

impl StoredResult {
    /// Project a validated crate AnalysisResult into the persistence shape.
    pub fn from_analysis(
        conversation_id: &str,
        generation: u64,
        result: &AnalysisResult,
        saved_at_millis: i64,
        artifact_hashes: Vec<String>,
    ) -> Result<Self, String> {
        let stored = Self {
            conversation_id: conversation_id.to_owned(),
            run_id: result.run_id.clone(),
            generation,
            session_id: result.session_id.clone(),
            model: result.model.clone(),
            summary: result.summary.clone(),
            findings: result
                .findings
                .iter()
                .map(|finding| StoredFinding {
                    id: finding.id.clone(),
                    severity: finding.severity,
                    title: finding.title.clone(),
                    explanation: finding.explanation.clone(),
                    recommendation: finding.recommendation.clone(),
                    confidence: finding.analysis_confidence,
                    evidence_ids: finding.evidence_ids.clone(),
                    candidate_ids: finding.candidate_ids.clone(),
                })
                .collect(),
            saved_at_millis,
            question_hash: None,
            parent_run_id: None,
            artifact_hashes,
        };
        stored.validate()?;
        Ok(stored)
    }

    pub fn with_question_hash(mut self, question_hash: Option<String>) -> Result<Self, String> {
        self.question_hash = question_hash;
        self.validate()?;
        Ok(self)
    }

    pub fn with_parent_run(mut self, parent_run_id: Option<String>) -> Result<Self, String> {
        self.parent_run_id = parent_run_id;
        self.validate()?;
        Ok(self)
    }

    pub fn validate(&self) -> Result<(), String> {
        validate_result(self)
    }
}

/// Chat-row metadata: user title, pin/archive flags and last-touch time.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatMetadata {
    pub title: Option<String>,
    pub pinned: bool,
    pub updated_at_millis: i64,
    pub archived: bool,
}

impl Default for ChatMetadata {
    fn default() -> Self {
        Self {
            title: None,
            pinned: false,
            updated_at_millis: 0,
            archived: false,
        }
    }
}

/// Joined read projection for restoring a conversation (record turn + question
/// + stored result). Restore never replays a run.
#[derive(Debug, Clone)]
pub struct TranscriptTurn {
    pub run_id: String,
    pub generation: u64,
    pub status: RunStatus,
    pub question: Option<String>,
    pub created_at_millis: i64,
    pub result: Option<StoredResult>,
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

/// Single-process bounded conversation store. Writes are serialized through
/// SQLite immediate transactions on one connection.
pub struct ConversationStore {
    connection: Connection,
    path: PathBuf,
}

impl ConversationStore {
    /// Open or create the store. The path must be explicitly absolute, its
    /// parent must already exist, and neither the file nor its sidecars may be
    /// symbolic links.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, String> {
        let path = path.as_ref();
        if !path.is_absolute() {
            return error("E_STORAGE_PATH");
        }
        let parent = path.parent().ok_or_else(|| "E_STORAGE_PATH".to_owned())?;
        if !parent.is_dir() {
            return error("E_STORAGE_PATH");
        }
        verify_database_paths(path)?;
        if !path.exists() {
            let mut options = std::fs::OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            match options.open(path) {
                Ok(_) => {}
                Err(failure) if failure.kind() == std::io::ErrorKind::AlreadyExists => {}
                Err(_) => return error("E_STORAGE_PATH"),
            }
        }
        // SQLITE_OPEN_NOFOLLOW is deliberately not set: it rejects any path with
        // a linked ancestor (for example /var on macOS) and is unavailable on
        // this bundled build. The file/sidecar symlink checks above plus the
        // post-open re-check are the peer's (aps-storage) policy; this is
        // validation, not an OS race-proof sandbox.
        let mut connection = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE
                | OpenFlags::SQLITE_OPEN_CREATE
                | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
        verify_database_paths(path)?;
        connection
            .set_limit(
                rusqlite::limits::Limit::SQLITE_LIMIT_LENGTH,
                MAX_RECORD_BYTES as i32,
            )
            .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
        connection
            .busy_timeout(Duration::from_secs(5))
            .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
        connection
            .pragma_update(None, "journal_mode", "DELETE")
            .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
        connection
            .pragma_update(None, "foreign_keys", true)
            .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
        connection
            .pragma_update(None, "trusted_schema", false)
            .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
        initialize_schema(&mut connection)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
                .map_err(|_| "E_STORAGE_PATH".to_owned())?;
        }
        Ok(Self {
            connection,
            path: path.to_path_buf(),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Explicit startup recovery only. Queued/preparing/awaiting/running turns
    /// become Interrupted with E_RUN_INTERRUPTED; nothing is resumed or replayed.
    pub fn recover_interrupted(&mut self) -> Result<usize, String> {
        self.in_transaction(|transaction| {
            let ids = conversation_ids(transaction)?;
            let mut changed = 0;
            for id in ids {
                let record =
                    read_record(transaction, &id)?.ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?;
                let mut turns = record.turns.clone();
                let mut touched = false;
                for turn in &mut turns {
                    if !turn.status.terminal() {
                        turn.status = RunStatus::Interrupted;
                        turn.error_code = Some("E_RUN_INTERRUPTED".into());
                        touched = true;
                    }
                }
                if touched {
                    let next = ConversationRecord { turns, ..record };
                    validate_record(&next)?;
                    write_record(transaction, &next)?;
                    changed += 1;
                }
            }
            Ok(changed)
        })
    }

    /// Create the conversation document, or accept an identical re-creation.
    /// A conflicting identity (analysis session, context digest or parent) is
    /// refused with E_IMMUTABLE_CONTEXT.
    pub fn create_conversation(
        &mut self,
        id: &str,
        analysis_session_id: &str,
        context_hash: &str,
        parent_conversation_id: Option<&str>,
    ) -> Result<ConversationRecord, String> {
        let record = ConversationRecord {
            id: id.to_owned(),
            analysis_session_id: analysis_session_id.to_owned(),
            context_hash: context_hash.to_owned(),
            parent_conversation_id: parent_conversation_id.map(str::to_owned),
            turns: Vec::new(),
        };
        validate_record(&record)?;
        encode_record(&record)?;
        self.in_transaction(|transaction| {
            if let Some(existing) = read_record(transaction, &record.id)? {
                if existing.analysis_session_id != record.analysis_session_id
                    || existing.context_hash != record.context_hash
                    || existing.parent_conversation_id != record.parent_conversation_id
                {
                    return error("E_IMMUTABLE_CONTEXT");
                }
                return Ok(existing);
            }
            let count = conversation_count(transaction)?;
            if count >= MAX_CONVERSATIONS {
                return error("E_CONVERSATION_LIMIT");
            }
            write_record(transaction, &record)?;
            Ok(record.clone())
        })
    }

    pub fn record(&self, id: &str) -> Result<Option<ConversationRecord>, String> {
        validate_id(id)?;
        read_record(&self.connection, id)
    }

    /// Apply a whole-record update with the Compose state-machine rules.
    pub fn save_conversation(&mut self, record: &ConversationRecord) -> Result<(), String> {
        validate_record(record)?;
        encode_record(record)?;
        self.in_transaction(|transaction| save_unlocked(transaction, record))
    }

    /// Commit the prepared conversation document and its display chat turn in
    /// one transaction before approval is exposed. The last turn must already
    /// be AWAITING_APPROVAL and match the chat turn identity.
    pub fn save_prepared(
        &mut self,
        record: &ConversationRecord,
        turn: &StoredChatTurn,
    ) -> Result<(), String> {
        validate_record(record)?;
        encode_record(record)?;
        let matched = record.last_turn().is_some_and(|last| {
            record.id == turn.conversation_id
                && last.run_id == turn.run_id
                && last.generation == turn.generation
                && last.status == RunStatus::AwaitingApproval
        });
        if !matched {
            return error("E_CHAT_TURN");
        }
        self.in_transaction(|transaction| {
            save_unlocked(transaction, record)?;
            insert_chat_turn(transaction, record, turn)
        })
    }

    /// Insert one display chat turn against an existing conversation document.
    pub fn save_chat_turn(&mut self, turn: &StoredChatTurn) -> Result<(), String> {
        validate_chat_turn(turn)?;
        self.in_transaction(|transaction| {
            let record = read_record(transaction, &turn.conversation_id)?
                .ok_or_else(|| "E_CONVERSATION_UNAVAILABLE".to_owned())?;
            insert_chat_turn(transaction, &record, turn)
        })
    }

    /// Insert one validated result projection (bounded JSON).
    pub fn save_result(&mut self, result: &StoredResult) -> Result<(), String> {
        result.validate()?;
        encode_result(result)?;
        self.in_transaction(|transaction| save_result_unlocked(transaction, result))
    }

    /// Commit a succeeded conversation document and its result atomically.
    pub fn complete(
        &mut self,
        record: &ConversationRecord,
        result: &StoredResult,
    ) -> Result<(), String> {
        validate_record(record)?;
        encode_record(record)?;
        result.validate()?;
        encode_result(result)?;
        self.in_transaction(|transaction| {
            let previous = read_record(transaction, &record.id)?
                .ok_or_else(|| "E_CONVERSATION_UNAVAILABLE".to_owned())?;
            validate_update(&previous, record)?;
            let last = record
                .last_turn()
                .ok_or_else(|| "E_RESULT_TURN".to_owned())?;
            if result.conversation_id != record.id || result.run_id != last.run_id {
                return error("E_RESULT_TURN");
            }
            write_record(transaction, record)?;
            save_result_unlocked(transaction, result)
        })
    }

    /// Mark one run terminal after a failure/cancel. The error code is a stable
    /// E_ code, never provider text.
    pub fn save_failed(
        &mut self,
        conversation_id: &str,
        run_id: &str,
        status: RunStatus,
        error_code: &str,
    ) -> Result<(), String> {
        validate_id(conversation_id)?;
        validate_id(run_id)?;
        if !status.terminal() || !is_error_code(error_code) {
            return error("E_RUN_STATE");
        }
        self.in_transaction(|transaction| {
            let previous = read_record(transaction, conversation_id)?
                .ok_or_else(|| "E_CONVERSATION_UNAVAILABLE".to_owned())?;
            let mut next = previous.clone();
            let turn = next
                .turns
                .iter_mut()
                .find(|turn| turn.run_id == run_id)
                .ok_or_else(|| "E_RESULT_TURN".to_owned())?;
            turn.status = status;
            turn.error_code = Some(error_code.to_owned());
            validate_record(&next)?;
            validate_update(&previous, &next)?;
            write_record(transaction, &next)
        })
    }

    pub fn result(
        &self,
        conversation_id: &str,
        run_id: &str,
    ) -> Result<Option<StoredResult>, String> {
        validate_id(conversation_id)?;
        validate_id(run_id)?;
        read_result(&self.connection, conversation_id, run_id)
    }

    pub fn chat_turns(&self, conversation_id: &str) -> Result<Vec<StoredChatTurn>, String> {
        validate_id(conversation_id)?;
        let record = read_record(&self.connection, conversation_id)?;
        chat_turns_unlocked(&self.connection, conversation_id, record.as_ref())
    }

    pub fn transcript(&self, conversation_id: &str) -> Result<Vec<TranscriptTurn>, String> {
        validate_id(conversation_id)?;
        let Some(record) = read_record(&self.connection, conversation_id)? else {
            return Ok(Vec::new());
        };
        let questions: BTreeMap<String, StoredChatTurn> =
            chat_turns_unlocked(&self.connection, conversation_id, Some(&record))?
                .into_iter()
                .map(|turn| (turn.run_id.clone(), turn))
                .collect();
        let mut transcript = Vec::with_capacity(record.turns.len());
        for turn in &record.turns {
            let question = questions
                .get(&turn.run_id)
                .filter(|question| question.generation == turn.generation);
            let stored = read_result(&self.connection, conversation_id, &turn.run_id)?;
            let created_at_millis = question
                .map(|question| question.created_at_millis)
                .or_else(|| stored.as_ref().map(|result| result.saved_at_millis))
                .unwrap_or(0);
            transcript.push(TranscriptTurn {
                run_id: turn.run_id.clone(),
                generation: turn.generation,
                status: turn.status,
                question: question.and_then(|question| question.question.clone()),
                created_at_millis,
                result: stored,
            });
        }
        Ok(transcript)
    }

    pub fn chat_metadata(&self, conversation_id: &str) -> Result<Option<ChatMetadata>, String> {
        validate_id(conversation_id)?;
        read_chat_metadata(&self.connection, conversation_id)
    }

    /// Bounded page ordered by id (Compose ConversationRepository.list order).
    pub fn list(&self, limit: usize, offset: usize) -> Result<Vec<ConversationRecord>, String> {
        if !(1..=MAX_PAGE_SIZE).contains(&limit) || offset > MAX_CONVERSATIONS {
            return error("E_STORAGE_PAGE");
        }
        let mut statement = self
            .connection
            .prepare("SELECT record FROM conversation ORDER BY id LIMIT ?1 OFFSET ?2")
            .map_err(|_| "E_STORAGE_IO".to_owned())?;
        let values = statement
            .query_map(params![limit as i64, offset as i64], |row| {
                row.get::<_, String>(0)
            })
            .map_err(|_| "E_STORAGE_IO".to_owned())?;
        let mut records = Vec::new();
        for value in values {
            let encoded = value.map_err(|_| "E_STORAGE_IO".to_owned())?;
            if encoded.len() > MAX_RECORD_BYTES {
                return error("E_CONVERSATION_SIZE");
            }
            let id: String = self
                .connection
                .query_row(
                    "SELECT id FROM conversation WHERE record = ?1 LIMIT 1",
                    [&encoded],
                    |row| row.get(0),
                )
                .optional()
                .map_err(|_| "E_STORAGE_IO".to_owned())?
                .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?;
            records.push(decode_record(&id, &encoded)?);
        }
        Ok(records)
    }

    /// Sidebar summaries: metadata title, else first question, else first
    /// non-blank result summary, else "Analysis session". Pinned first, then
    /// most recently touched, then id. Graph-owned child conversations (a
    /// parent with a different analysis session) are hidden, matching Compose
    /// ApplicationAiService.chatHistory.
    pub fn summaries(&self, include_archived: bool) -> Result<Vec<SessionSummary>, String> {
        let records = self.all_records()?;
        let records_by_id: BTreeMap<&str, &ConversationRecord> = records
            .iter()
            .map(|record| (record.id.as_str(), record))
            .collect();
        let mut summaries = Vec::new();
        for record in &records {
            if let Some(parent) = record
                .parent_conversation_id
                .as_deref()
                .and_then(|parent| records_by_id.get(parent))
            {
                if parent.analysis_session_id != record.analysis_session_id {
                    continue;
                }
            }
            let metadata = read_chat_metadata(&self.connection, &record.id)?.unwrap_or_default();
            if !include_archived && metadata.archived {
                continue;
            }
            let first_question = chat_turns_unlocked(&self.connection, &record.id, Some(record))?
                .into_iter()
                .find_map(|turn| turn.question);
            let result_summary = if metadata.title.is_none() && first_question.is_none() {
                first_result_summary(&self.connection, record)?
            } else {
                None
            };
            let title = metadata.title.unwrap_or_else(|| {
                first_question
                    .or(result_summary)
                    .map(|title| take_chars(&title, TITLE_CHARS))
                    .unwrap_or_else(|| DEFAULT_TITLE.to_owned())
            });
            summaries.push(SessionSummary {
                id: record.id.clone(),
                title,
                updated_at_millis: metadata.updated_at_millis,
                pinned: metadata.pinned,
                turn_count: record.turns.len(),
                status: record.turns.last().map(|turn| turn.status),
                archived: metadata.archived,
            });
        }
        summaries.sort_by(|a, b| {
            b.pinned
                .cmp(&a.pinned)
                .then_with(|| b.updated_at_millis.cmp(&a.updated_at_millis))
                .then_with(|| a.id.cmp(&b.id))
        });
        Ok(summaries)
    }

    pub fn rename_conversation(
        &mut self,
        conversation_id: &str,
        title: &str,
    ) -> Result<(), String> {
        validate_id(conversation_id)?;
        if title.trim().is_empty() || title.chars().count() > MAX_TITLE_BYTES {
            return error("E_CHAT_TITLE");
        }
        let projected = take_chars(title.trim(), MAX_TITLE_BYTES);
        self.in_transaction(|transaction| {
            require_conversation(transaction, conversation_id)?;
            transaction
                .execute(
                    "INSERT INTO conversation_chat_meta(id, title, pinned) VALUES (?1, ?2, 0) ON CONFLICT(id) DO UPDATE SET title=excluded.title",
                    params![conversation_id, projected],
                )
                .map_err(|_| "E_STORAGE_IO".to_owned())?;
            Ok(())
        })
    }

    pub fn pin_conversation(&mut self, conversation_id: &str, pinned: bool) -> Result<(), String> {
        validate_id(conversation_id)?;
        self.in_transaction(|transaction| {
            require_conversation(transaction, conversation_id)?;
            transaction
                .execute(
                    "INSERT INTO conversation_chat_meta(id, title, pinned) VALUES (?1, NULL, ?2) ON CONFLICT(id) DO UPDATE SET pinned=excluded.pinned",
                    params![conversation_id, pinned],
                )
                .map_err(|_| "E_STORAGE_IO".to_owned())?;
            Ok(())
        })
    }

    pub fn archive_conversation(
        &mut self,
        conversation_id: &str,
        archived: bool,
    ) -> Result<(), String> {
        validate_id(conversation_id)?;
        self.in_transaction(|transaction| {
            require_conversation(transaction, conversation_id)?;
            transaction
                .execute(
                    "INSERT INTO conversation_chat_meta(id, archived) VALUES (?1, ?2) ON CONFLICT(id) DO UPDATE SET archived=excluded.archived",
                    params![conversation_id, archived],
                )
                .map_err(|_| "E_STORAGE_IO".to_owned())?;
            Ok(())
        })
    }

    /// Explicit chat deletion. Same-analysis-session descendants are cascaded;
    /// any child conversation with a different analysis session is a user
    /// branch and refuses deletion with E_CONVERSATION_HAS_CHILDREN. A
    /// non-terminal turn anywhere in the tree refuses with E_CONVERSATION_ACTIVE.
    pub fn delete_chat_conversation(&mut self, conversation_id: &str) -> Result<bool, String> {
        validate_id(conversation_id)?;
        self.in_transaction(|transaction| {
            let Some(root) = read_record(transaction, conversation_id)? else {
                return Ok(false);
            };
            let records = all_records_unlocked(transaction)?;
            let mut children: BTreeMap<&str, Vec<&ConversationRecord>> = BTreeMap::new();
            for record in &records {
                if let Some(parent) = record.parent_conversation_id.as_deref() {
                    children.entry(parent).or_default().push(record);
                }
            }
            let mut planned: Vec<&ConversationRecord> = vec![&root];
            let mut visited = BTreeSet::new();
            visited.insert(root.id.as_str());
            let mut index = 0;
            while index < planned.len() {
                let parent = planned[index];
                index += 1;
                let parent_session = parent.analysis_session_id.as_str();
                for child in children.get(parent.id.as_str()).into_iter().flatten() {
                    if child.analysis_session_id != parent_session {
                        return error("E_CONVERSATION_HAS_CHILDREN");
                    }
                    if !visited.insert(child.id.as_str()) {
                        return error("E_STORAGE_CORRUPT");
                    }
                    if planned.len() >= MAX_CONVERSATIONS {
                        return error("E_CONVERSATION_LIMIT");
                    }
                    planned.push(child);
                }
            }
            if planned
                .iter()
                .any(|record| record.turns.iter().any(|turn| !turn.status.terminal()))
            {
                return error("E_CONVERSATION_ACTIVE");
            }
            for record in planned.iter().rev() {
                let changed = transaction
                    .execute("DELETE FROM conversation WHERE id = ?1", [&record.id])
                    .map_err(|_| "E_STORAGE_IO".to_owned())?;
                if changed != 1 {
                    return error("E_STORAGE_CORRUPT");
                }
            }
            Ok(true)
        })
    }

    pub fn touch(&mut self, conversation_id: &str) -> Result<(), String> {
        validate_id(conversation_id)?;
        let changed = self
            .connection
            .execute(
                "UPDATE conversation_meta SET updated_at = ?1 WHERE id = ?2",
                params![now_millis(), conversation_id],
            )
            .map_err(|_| "E_STORAGE_IO".to_owned())?;
        if changed != 1 {
            return error("E_CONVERSATION_UNAVAILABLE");
        }
        Ok(())
    }

    /// Context digest and analysis session are immutable once a turn exists.
    pub fn set_context_digest(
        &mut self,
        conversation_id: &str,
        context_hash: &str,
    ) -> Result<(), String> {
        self.update_identity(conversation_id, |record| {
            record.context_hash = context_hash.to_owned();
        })
    }

    pub fn set_session_id(
        &mut self,
        conversation_id: &str,
        analysis_session_id: &str,
    ) -> Result<(), String> {
        validate_id(analysis_session_id)?;
        self.update_identity(conversation_id, |record| {
            record.analysis_session_id = analysis_session_id.to_owned();
        })
    }

    fn update_identity(
        &mut self,
        conversation_id: &str,
        edit: impl FnOnce(&mut ConversationRecord),
    ) -> Result<(), String> {
        validate_id(conversation_id)?;
        self.in_transaction(|transaction| {
            let previous = read_record(transaction, conversation_id)?
                .ok_or_else(|| "E_CONVERSATION_UNAVAILABLE".to_owned())?;
            if !previous.turns.is_empty() {
                return error("E_IMMUTABLE_CONTEXT");
            }
            let mut next = previous.clone();
            edit(&mut next);
            validate_record(&next)?;
            write_record(transaction, &next)
        })
    }

    /// Delete conversations outside the retention policy. Pinned conversations
    /// are only protected when keep_pinned is set. Conversations with a child
    /// or a non-terminal turn are skipped, matching Compose prune.
    pub fn retention(
        &mut self,
        max_conversations: usize,
        older_than_millis: i64,
        keep_pinned: bool,
    ) -> Result<usize, String> {
        if max_conversations > MAX_CONVERSATIONS || older_than_millis < 0 {
            return error("E_RETENTION_POLICY");
        }
        self.in_transaction(|transaction| {
            let mut statement = transaction
                .prepare(
                    "SELECT m.id, m.updated_at, COALESCE(c.pinned, 0) FROM conversation_meta m LEFT JOIN conversation_chat_meta c ON c.id = m.id ORDER BY m.updated_at DESC, m.id",
                )
                .map_err(|_| "E_STORAGE_IO".to_owned())?;
            let rows = statement
                .query_map([], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, i64>(2)?,
                    ))
                })
                .map_err(|_| "E_STORAGE_IO".to_owned())?;
            let mut entries = Vec::new();
            for row in rows {
                let (id, updated_at, pinned) = row.map_err(|_| "E_STORAGE_IO".to_owned())?;
                if entries.len() >= MAX_CONVERSATIONS {
                    return error("E_CONVERSATION_LIMIT");
                }
                entries.push((id, updated_at, pinned != 0));
            }
            drop(statement);
            let mut removed = 0;
            for (index, (id, updated_at, pinned)) in entries.into_iter().enumerate() {
                if !(index >= max_conversations || updated_at < older_than_millis) {
                    continue;
                }
                if keep_pinned && pinned {
                    continue;
                }
                let Some(record) = read_record(transaction, &id)? else {
                    continue;
                };
                if record.turns.iter().any(|turn| !turn.status.terminal()) {
                    continue;
                }
                let has_child = has_child(transaction, &id)?;
                if has_child {
                    continue;
                }
                let changed = transaction
                    .execute("DELETE FROM conversation WHERE id = ?1", [&id])
                    .map_err(|_| "E_STORAGE_IO".to_owned())?;
                if changed == 1 {
                    removed += 1;
                }
            }
            Ok(removed)
        })
    }

    /// Explicit clear-history. Refuses while any persisted turn is active.
    pub fn clear_history(&mut self) -> Result<(), String> {
        self.in_transaction(|transaction| {
            let records = all_records_unlocked(transaction)?;
            if records
                .iter()
                .any(|record| record.turns.iter().any(|turn| !turn.status.terminal()))
            {
                return error("E_CONVERSATION_ACTIVE");
            }
            transaction
                .execute("DELETE FROM conversation", [])
                .map_err(|_| "E_STORAGE_IO".to_owned())?;
            Ok(())
        })
    }

    fn all_records(&self) -> Result<Vec<ConversationRecord>, String> {
        all_records_unlocked(&self.connection)
    }

    fn in_transaction<T>(
        &mut self,
        operation: impl FnOnce(&Connection) -> Result<T, String>,
    ) -> Result<T, String> {
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "E_STORAGE_IO".to_owned())?;
        let value = operation(&transaction)?;
        transaction
            .commit()
            .map_err(|_| "E_STORAGE_IO".to_owned())?;
        Ok(value)
    }
}

// ---------------------------------------------------------------------------
// Schema and path safety
// ---------------------------------------------------------------------------

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS conversation(id TEXT PRIMARY KEY NOT NULL, record TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS conversation_meta(id TEXT PRIMARY KEY REFERENCES conversation(id) ON DELETE CASCADE, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS conversation_result(conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, run_id TEXT NOT NULL, record TEXT NOT NULL, PRIMARY KEY(conversation_id, run_id));
CREATE TABLE IF NOT EXISTS conversation_audit(conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, run_id TEXT NOT NULL, sequence INTEGER NOT NULL, record TEXT NOT NULL, PRIMARY KEY(conversation_id, run_id, sequence));
CREATE TABLE IF NOT EXISTS conversation_chat_turn(conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, run_id TEXT NOT NULL, generation INTEGER NOT NULL, question TEXT, created_at INTEGER NOT NULL, parent_run_id TEXT, PRIMARY KEY(conversation_id, run_id));
CREATE TABLE IF NOT EXISTS conversation_chat_meta(id TEXT PRIMARY KEY NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, title TEXT, pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0);
";

fn initialize_schema(connection: &mut Connection) -> Result<(), String> {
    let version: i64 = connection
        .query_row("PRAGMA user_version", [], |row| row.get(0))
        .map_err(|_| "E_STORAGE_SCHEMA".to_owned())?;
    if !(0..=1).contains(&version) {
        return error("E_STORAGE_SCHEMA");
    }
    let transaction = connection
        .transaction_with_behavior(TransactionBehavior::Immediate)
        .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
    transaction
        .execute_batch(SCHEMA)
        .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
    if !has_column(&transaction, "conversation_chat_meta", "archived")? {
        transaction
            .execute_batch(
                "ALTER TABLE conversation_chat_meta ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
            )
            .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
    }
    if !has_column(&transaction, "conversation_chat_turn", "parent_run_id")? {
        transaction
            .execute_batch("ALTER TABLE conversation_chat_turn ADD COLUMN parent_run_id TEXT")
            .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
    }
    transaction
        .execute(
            "INSERT OR IGNORE INTO conversation_meta(id, updated_at) SELECT id, 0 FROM conversation",
            [],
        )
        .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
    transaction
        .pragma_update(None, "user_version", 1)
        .map_err(|_| "E_STORAGE_OPEN".to_owned())?;
    transaction
        .commit()
        .map_err(|_| "E_STORAGE_OPEN".to_owned())
}

fn has_column(connection: &Connection, table: &str, column: &str) -> Result<bool, String> {
    let mut statement = connection
        .prepare(&format!("PRAGMA table_info({table})"))
        .map_err(|_| "E_STORAGE_SCHEMA".to_owned())?;
    let names = statement
        .query_map([], |row| row.get::<_, String>(1))
        .map_err(|_| "E_STORAGE_SCHEMA".to_owned())?;
    for name in names {
        if name.map_err(|_| "E_STORAGE_SCHEMA".to_owned())? == column {
            return Ok(true);
        }
    }
    Ok(false)
}

fn verify_database_paths(path: &Path) -> Result<(), String> {
    // Peer convention (crates/aps-storage): the database file and its sidecars
    // must be regular non-symlink files. The caller already verified the parent
    // directory exists; ancestor links are not followed for the database file
    // because SQLITE_OPEN_NOFOLLOW rejects a linked database at open time.
    match std::fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() => {
            return error("E_STORAGE_SYMLINK");
        }
        Ok(metadata) if !metadata.is_file() => return error("E_STORAGE_PATH"),
        Ok(_) => {}
        Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => return error("E_STORAGE_PATH"),
    }
    for suffix in ["-journal", "-wal", "-shm"] {
        let mut sidecar = path.as_os_str().to_os_string();
        sidecar.push(suffix);
        let sidecar = PathBuf::from(sidecar);
        match std::fs::symlink_metadata(&sidecar) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return error("E_STORAGE_SYMLINK");
            }
            Ok(_) => {}
            Err(failure) if failure.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return error("E_STORAGE_PATH"),
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Record JSON
// ---------------------------------------------------------------------------

fn record_limits() -> aps_json::Limits {
    aps_json::Limits {
        max_bytes: MAX_RECORD_BYTES as u64,
        max_depth: 8,
        max_nodes: 64 * 1024,
        max_string_bytes: 512,
        max_collection_items: MAX_TURNS + 1,
    }
}

fn result_limits() -> aps_json::Limits {
    aps_json::Limits {
        max_bytes: MAX_RESULT_BYTES as u64,
        max_depth: 8,
        max_nodes: 4096,
        max_string_bytes: SUMMARY_BYTES,
        max_collection_items: MAX_EVIDENCE_IDS + 1,
    }
}

fn object<const N: usize>(pairs: [(&str, Value); N]) -> Value {
    let mut map = Map::new();
    for (key, value) in pairs {
        map.insert(key.into(), value);
    }
    Value::Object(map)
}

fn turn_value(turn: &ConversationTurn) -> Value {
    object([
        ("runId", turn.run_id.clone().into()),
        ("generation", turn.generation.into()),
        ("status", turn.status.id().into()),
        (
            "evidenceIds",
            Value::Array(turn.evidence_ids.iter().cloned().map(Value::from).collect()),
        ),
        (
            "candidateIds",
            Value::Array(
                turn.candidate_ids
                    .iter()
                    .cloned()
                    .map(Value::from)
                    .collect(),
            ),
        ),
        (
            "findingIds",
            Value::Array(turn.finding_ids.iter().cloned().map(Value::from).collect()),
        ),
        (
            "errorCode",
            turn.error_code.clone().map_or(Value::Null, Value::from),
        ),
    ])
}

fn encode_record(record: &ConversationRecord) -> Result<String, String> {
    validate_record(record)?;
    let root = object([
        ("schemaVersion", Value::from(1u64)),
        ("id", record.id.clone().into()),
        (
            "analysisSessionId",
            record.analysis_session_id.clone().into(),
        ),
        ("contextHash", record.context_hash.clone().into()),
        (
            "parentConversationId",
            record
                .parent_conversation_id
                .clone()
                .map_or(Value::Null, Value::from),
        ),
        (
            "turns",
            Value::Array(record.turns.iter().map(turn_value).collect()),
        ),
    ]);
    let bytes =
        aps_json::to_vec(&root, record_limits()).map_err(|_| "E_CONVERSATION_SIZE".to_owned())?;
    if bytes.len() > MAX_RECORD_BYTES {
        return error("E_CONVERSATION_SIZE");
    }
    let encoded = String::from_utf8(bytes).map_err(|_| "E_STORAGE_CORRUPT".to_owned())?;
    decode_record(&record.id, &encoded)?;
    Ok(encoded)
}

fn decode_record(id: &str, encoded: &str) -> Result<ConversationRecord, String> {
    if encoded.len() > MAX_RECORD_BYTES {
        return error("E_CONVERSATION_SIZE");
    }
    let root = aps_json::parse_slice(encoded.as_bytes(), record_limits())
        .map_err(|_| "E_STORAGE_CORRUPT".to_owned())?;
    exact_keys(
        &root,
        &[
            "schemaVersion",
            "id",
            "analysisSessionId",
            "contextHash",
            "parentConversationId",
            "turns",
        ],
    )?;
    if root.get("schemaVersion").and_then(Value::as_u64) != Some(1) {
        return error("E_STORAGE_CORRUPT");
    }
    let stored_id = string_field(&root, "id")?;
    if stored_id != id {
        return error("E_STORAGE_CORRUPT");
    }
    let turns = root
        .get("turns")
        .and_then(Value::as_array)
        .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?;
    if turns.len() > MAX_TURNS {
        return error("E_CONVERSATION_LIMIT");
    }
    let mut decoded_turns = Vec::with_capacity(turns.len());
    for turn in turns {
        decoded_turns.push(decode_turn(turn)?);
    }
    let parent = match root.get("parentConversationId") {
        Some(Value::Null) => None,
        Some(Value::String(value)) => Some(value.clone()),
        _ => return error("E_STORAGE_CORRUPT"),
    };
    let record = ConversationRecord {
        id: stored_id.to_owned(),
        analysis_session_id: string_field(&root, "analysisSessionId")?.to_owned(),
        context_hash: string_field(&root, "contextHash")?.to_owned(),
        parent_conversation_id: parent,
        turns: decoded_turns,
    };
    validate_record(&record)?;
    Ok(record)
}

fn decode_turn(value: &Value) -> Result<ConversationTurn, String> {
    exact_keys(
        value,
        &[
            "runId",
            "generation",
            "status",
            "evidenceIds",
            "candidateIds",
            "findingIds",
            "errorCode",
        ],
    )?;
    let status = parse_status(string_field(value, "status")?)?;
    let error_code = match value.get("errorCode") {
        Some(Value::Null) => None,
        Some(Value::String(code)) => Some(code.clone()),
        _ => return error("E_STORAGE_CORRUPT"),
    };
    Ok(ConversationTurn {
        run_id: string_field(value, "runId")?.to_owned(),
        generation: value
            .get("generation")
            .and_then(Value::as_u64)
            .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?,
        status,
        evidence_ids: string_array(value, "evidenceIds")?,
        candidate_ids: string_array(value, "candidateIds")?,
        finding_ids: string_array(value, "findingIds")?,
        error_code,
    })
}

fn validate_record(record: &ConversationRecord) -> Result<(), String> {
    validate_id(&record.id)?;
    validate_id(&record.analysis_session_id)?;
    if !is_hex64(&record.context_hash) {
        return error("E_IMMUTABLE_CONTEXT");
    }
    if let Some(parent) = &record.parent_conversation_id {
        validate_id(parent)?;
        if parent == &record.id {
            return error("E_CONVERSATION_ID");
        }
    }
    validate_turns(&record.turns)
}

fn validate_turns(turns: &[ConversationTurn]) -> Result<(), String> {
    if turns.len() > MAX_TURNS {
        return error("E_CONVERSATION_LIMIT");
    }
    let mut run_ids = BTreeSet::new();
    for (index, turn) in turns.iter().enumerate() {
        validate_id(&turn.run_id)?;
        if !run_ids.insert(turn.run_id.as_str()) {
            return error("E_RUN_REFERENCES");
        }
        if turn.generation > i64::MAX as u64 {
            return error("E_RUN_GENERATION");
        }
        if index > 0 && turns[index - 1].generation >= turn.generation {
            return error("E_RUN_GENERATION");
        }
        if index + 1 < turns.len() && !turn.status.terminal() {
            return error("E_RUN_STATE");
        }
        validate_reference_ids(&turn.evidence_ids, MAX_EVIDENCE_IDS)?;
        validate_reference_ids(&turn.candidate_ids, MAX_EVIDENCE_IDS)?;
        validate_reference_ids(&turn.finding_ids, MAX_FINDINGS)?;
        if let Some(error_code) = &turn.error_code {
            if !is_error_code(error_code) {
                return error("E_RUN_STATE");
            }
        }
    }
    Ok(())
}

fn validate_update(previous: &ConversationRecord, next: &ConversationRecord) -> Result<(), String> {
    if previous.analysis_session_id != next.analysis_session_id
        || previous.context_hash != next.context_hash
        || previous.parent_conversation_id != next.parent_conversation_id
    {
        return error("E_IMMUTABLE_CONTEXT");
    }
    if next.turns.len() < previous.turns.len() {
        return error("E_STALE_CONVERSATION");
    }
    for (index, older) in previous.turns.iter().enumerate() {
        let newer = next
            .turns
            .get(index)
            .ok_or_else(|| "E_STALE_CONVERSATION".to_owned())?;
        if older.run_id != newer.run_id
            || older.generation != newer.generation
            || older.evidence_ids != newer.evidence_ids
            || older.candidate_ids != newer.candidate_ids
        {
            return error("E_STALE_CONVERSATION");
        }
        if older.status.terminal()
            || older.status == newer.status
            || index + 1 < previous.turns.len()
        {
            if older != newer {
                return error("E_STALE_CONVERSATION");
            }
        } else if !valid_transition(older.status, newer.status) {
            return error("E_RUN_STATE");
        }
    }
    Ok(())
}

fn valid_transition(previous: RunStatus, next: RunStatus) -> bool {
    if previous == next || next.terminal() {
        return true;
    }
    let active = [
        RunStatus::Queued,
        RunStatus::Preparing,
        RunStatus::AwaitingApproval,
        RunStatus::Running,
    ];
    match (
        active.iter().position(|status| *status == previous),
        active.iter().position(|status| *status == next),
    ) {
        (Some(previous), Some(next)) => next > previous,
        (None, Some(_)) => true,
        (_, None) => false,
    }
}

// ---------------------------------------------------------------------------
// Chat rows
// ---------------------------------------------------------------------------

fn validate_chat_turn(turn: &StoredChatTurn) -> Result<(), String> {
    validate_id(&turn.conversation_id)?;
    validate_id(&turn.run_id)?;
    if turn.generation > i64::MAX as u64 {
        return error("E_RUN_GENERATION");
    }
    if turn.created_at_millis < 0 {
        return error("E_CHAT_TURN");
    }
    if let Some(parent) = &turn.parent_run_id {
        validate_id(parent)?;
    }
    if let Some(question) = &turn.question {
        if question.trim().is_empty()
            || question.chars().count() > MAX_QUESTION_BYTES
            || question
                .chars()
                .any(|character| character.is_control() && !matches!(character, '\n' | '\r' | '\t'))
        {
            return error("E_CHAT_TURN");
        }
    }
    Ok(())
}

fn insert_chat_turn(
    transaction: &Connection,
    record: &ConversationRecord,
    turn: &StoredChatTurn,
) -> Result<(), String> {
    validate_chat_turn(turn)?;
    if turn.conversation_id != record.id {
        return error("E_CHAT_TURN");
    }
    if let Some(parent_run_id) = &turn.parent_run_id {
        let local = record.turns.iter().any(|candidate| {
            candidate.run_id == *parent_run_id
                && candidate.generation < turn.generation
                && candidate.status.terminal()
        });
        let parent = if record.turns.len() == 1 {
            record
                .parent_conversation_id
                .as_deref()
                .map(|parent_id| read_record(transaction, parent_id))
                .transpose()?
                .flatten()
        } else {
            None
        };
        let fork_parent = parent.is_some_and(|source| {
            source.analysis_session_id == record.analysis_session_id
                && source.context_hash == record.context_hash
                && source.turns.iter().any(|candidate| {
                    candidate.run_id == *parent_run_id && candidate.status.terminal()
                })
        });
        if !local && !fork_parent {
            return error("E_PARENT_TURN");
        }
    }
    let existing = chat_turn_for_run(transaction, &turn.conversation_id, &turn.run_id)?;
    if let Some(existing) = existing {
        if existing != *turn {
            return error("E_IMMUTABLE_CHAT_TURN");
        }
        return Ok(());
    }
    transaction
        .execute(
            "INSERT OR IGNORE INTO conversation_chat_turn(conversation_id, run_id, generation, question, created_at, parent_run_id) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                turn.conversation_id,
                turn.run_id,
                turn.generation as i64,
                turn.question,
                turn.created_at_millis,
                turn.parent_run_id,
            ],
        )
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    Ok(())
}

fn chat_turns_unlocked(
    connection: &Connection,
    conversation_id: &str,
    record: Option<&ConversationRecord>,
) -> Result<Vec<StoredChatTurn>, String> {
    let canonical: BTreeMap<&str, u64> = record.map_or_else(BTreeMap::new, |record| {
        record
            .turns
            .iter()
            .map(|turn| (turn.run_id.as_str(), turn.generation))
            .collect()
    });
    let mut statement = connection
        .prepare(
            "SELECT run_id, generation, question, created_at, parent_run_id FROM conversation_chat_turn WHERE conversation_id = ?1 ORDER BY generation",
        )
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    let rows = statement
        .query_map([conversation_id], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, Option<String>>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, Option<String>>(4)?,
            ))
        })
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    let mut turns = Vec::new();
    for row in rows {
        if turns.len() >= MAX_TURNS {
            return error("E_CHAT_LIMIT");
        }
        let (run_id, generation, question, created_at, parent_run_id) =
            row.map_err(|_| "E_STORAGE_IO".to_owned())?;
        if question
            .as_ref()
            .is_some_and(|question| question.len() > CHAT_TURN_QUESTION_BYTES)
            || parent_run_id
                .as_ref()
                .is_some_and(|parent| parent.len() > CHAT_TURN_PARENT_BYTES)
        {
            return error("E_CHAT_LIMIT");
        }
        let generation = u64::try_from(generation).map_err(|_| "E_STORAGE_CORRUPT".to_owned())?;
        if canonical.get(run_id.as_str()) != Some(&generation) {
            return error("E_CHAT_TURN");
        }
        turns.push(StoredChatTurn {
            conversation_id: conversation_id.to_owned(),
            run_id,
            generation,
            question,
            created_at_millis: created_at,
            parent_run_id,
        });
    }
    Ok(turns)
}

fn chat_turn_for_run(
    connection: &Connection,
    conversation_id: &str,
    run_id: &str,
) -> Result<Option<StoredChatTurn>, String> {
    let row = connection
        .query_row(
            "SELECT generation, question, created_at, parent_run_id FROM conversation_chat_turn WHERE conversation_id = ?1 AND run_id = ?2",
            params![conversation_id, run_id],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, Option<String>>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, Option<String>>(3)?,
                ))
            },
        )
        .optional()
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    match row {
        None => Ok(None),
        Some((generation, question, created_at, parent_run_id)) => Ok(Some(StoredChatTurn {
            conversation_id: conversation_id.to_owned(),
            run_id: run_id.to_owned(),
            generation: u64::try_from(generation).map_err(|_| "E_STORAGE_CORRUPT".to_owned())?,
            question,
            created_at_millis: created_at,
            parent_run_id,
        })),
    }
}

fn read_chat_metadata(
    connection: &Connection,
    conversation_id: &str,
) -> Result<Option<ChatMetadata>, String> {
    let row = connection
        .query_row(
            "SELECT length(CAST(c.title AS BLOB)), c.title, c.pinned, m.updated_at, c.archived FROM conversation_meta m LEFT JOIN conversation_chat_meta c ON c.id = m.id WHERE m.id = ?1",
            [conversation_id],
            |row| {
                Ok((
                    row.get::<_, Option<i64>>(0)?,
                    row.get::<_, Option<String>>(1)?,
                    row.get::<_, Option<i64>>(2)?,
                    row.get::<_, i64>(3)?,
                    row.get::<_, Option<i64>>(4)?,
                ))
            },
        )
        .optional()
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    let Some((title_bytes, title, pinned, updated_at, archived)) = row else {
        return Ok(None);
    };
    if title_bytes.is_some_and(|bytes| bytes > CHAT_TITLE_BYTES as i64)
        || pinned.is_some_and(|pinned| !matches!(pinned, 0 | 1))
        || archived.is_some_and(|archived| !matches!(archived, 0 | 1))
    {
        return error("E_STORAGE_CORRUPT");
    }
    if let Some(title) = &title {
        if title.trim().is_empty()
            || title.chars().count() > MAX_TITLE_BYTES
            || title.chars().any(char::is_control)
        {
            return error("E_STORAGE_CORRUPT");
        }
    }
    Ok(Some(ChatMetadata {
        title,
        pinned: pinned == Some(1),
        updated_at_millis: updated_at,
        archived: archived == Some(1),
    }))
}

// ---------------------------------------------------------------------------
// Result JSON
// ---------------------------------------------------------------------------

fn severity_id(severity: FindingSeverity) -> &'static str {
    match severity {
        FindingSeverity::Info => "INFO",
        FindingSeverity::Warning => "WARNING",
        FindingSeverity::Error => "ERROR",
    }
}

fn parse_severity(value: &str) -> Result<FindingSeverity, String> {
    match value {
        "INFO" => Ok(FindingSeverity::Info),
        "WARNING" => Ok(FindingSeverity::Warning),
        "ERROR" => Ok(FindingSeverity::Error),
        _ => error("E_STORAGE_CORRUPT"),
    }
}

fn finding_value(finding: &StoredFinding) -> Value {
    object([
        ("id", finding.id.clone().into()),
        ("severity", severity_id(finding.severity).into()),
        ("title", finding.title.clone().into()),
        ("explanation", finding.explanation.clone().into()),
        ("recommendation", finding.recommendation.clone().into()),
        ("confidence", Value::from(finding.confidence)),
        (
            "evidenceIds",
            Value::Array(
                finding
                    .evidence_ids
                    .iter()
                    .cloned()
                    .map(Value::from)
                    .collect(),
            ),
        ),
        (
            "candidateIds",
            Value::Array(
                finding
                    .candidate_ids
                    .iter()
                    .cloned()
                    .map(Value::from)
                    .collect(),
            ),
        ),
    ])
}

fn encode_result(result: &StoredResult) -> Result<String, String> {
    validate_result(result)?;
    let root = object([
        ("conversationId", result.conversation_id.clone().into()),
        ("runId", result.run_id.clone().into()),
        ("generation", result.generation.into()),
        ("sessionId", result.session_id.clone().into()),
        ("model", result.model.clone().into()),
        ("summary", result.summary.clone().into()),
        (
            "findings",
            Value::Array(result.findings.iter().map(finding_value).collect()),
        ),
        ("savedAtEpochMillis", result.saved_at_millis.into()),
        (
            "questionHash",
            result
                .question_hash
                .clone()
                .map_or(Value::Null, Value::from),
        ),
        (
            "parentTurnRunId",
            result
                .parent_run_id
                .clone()
                .map_or(Value::Null, Value::from),
        ),
        (
            "artifactHashes",
            Value::Array(
                result
                    .artifact_hashes
                    .iter()
                    .cloned()
                    .map(Value::from)
                    .collect(),
            ),
        ),
    ]);
    let bytes =
        aps_json::to_vec(&root, result_limits()).map_err(|_| "E_RESULT_LIMIT".to_owned())?;
    if bytes.len() > MAX_RESULT_BYTES {
        return error("E_RESULT_LIMIT");
    }
    let encoded = String::from_utf8(bytes).map_err(|_| "E_STORAGE_CORRUPT".to_owned())?;
    decode_result(&encoded)?;
    Ok(encoded)
}

fn decode_result(encoded: &str) -> Result<StoredResult, String> {
    if encoded.len() > MAX_RESULT_BYTES {
        return error("E_RESULT_LIMIT");
    }
    let root = aps_json::parse_slice(encoded.as_bytes(), result_limits())
        .map_err(|_| "E_STORAGE_CORRUPT".to_owned())?;
    exact_keys(
        &root,
        &[
            "conversationId",
            "runId",
            "generation",
            "sessionId",
            "model",
            "summary",
            "findings",
            "savedAtEpochMillis",
            "questionHash",
            "parentTurnRunId",
            "artifactHashes",
        ],
    )?;
    let findings = root
        .get("findings")
        .and_then(Value::as_array)
        .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?;
    if findings.len() > MAX_FINDINGS {
        return error("E_RESULT_LIMIT");
    }
    let mut decoded_findings = Vec::with_capacity(findings.len());
    for finding in findings {
        decoded_findings.push(decode_finding(finding)?);
    }
    let optional_string = |field: &str| -> Result<Option<String>, String> {
        match root.get(field) {
            Some(Value::Null) => Ok(None),
            Some(Value::String(value)) => Ok(Some(value.clone())),
            _ => error("E_STORAGE_CORRUPT"),
        }
    };
    let result = StoredResult {
        conversation_id: string_field(&root, "conversationId")?.to_owned(),
        run_id: string_field(&root, "runId")?.to_owned(),
        generation: root
            .get("generation")
            .and_then(Value::as_u64)
            .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?,
        session_id: string_field(&root, "sessionId")?.to_owned(),
        model: string_field(&root, "model")?.to_owned(),
        summary: string_field(&root, "summary")?.to_owned(),
        findings: decoded_findings,
        saved_at_millis: root
            .get("savedAtEpochMillis")
            .and_then(Value::as_i64)
            .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?,
        question_hash: optional_string("questionHash")?,
        parent_run_id: optional_string("parentTurnRunId")?,
        artifact_hashes: string_array(&root, "artifactHashes")?,
    };
    validate_result(&result)?;
    Ok(result)
}

fn decode_finding(value: &Value) -> Result<StoredFinding, String> {
    exact_keys(
        value,
        &[
            "id",
            "severity",
            "title",
            "explanation",
            "recommendation",
            "confidence",
            "evidenceIds",
            "candidateIds",
        ],
    )?;
    Ok(StoredFinding {
        id: string_field(value, "id")?.to_owned(),
        severity: parse_severity(string_field(value, "severity")?)?,
        title: string_field(value, "title")?.to_owned(),
        explanation: string_field(value, "explanation")?.to_owned(),
        recommendation: string_field(value, "recommendation")?.to_owned(),
        confidence: value
            .get("confidence")
            .and_then(Value::as_f64)
            .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?,
        evidence_ids: string_array(value, "evidenceIds")?,
        candidate_ids: string_array(value, "candidateIds")?,
    })
}

fn validate_result(result: &StoredResult) -> Result<(), String> {
    validate_id(&result.conversation_id)?;
    validate_id(&result.run_id)?;
    validate_id(&result.session_id)?;
    if result.saved_at_millis < 0 {
        return error("E_RESULT_LIMIT");
    }
    if result.model.is_empty()
        || result.model.chars().count() > 128
        || result.model.chars().any(char::is_control)
    {
        return error("E_RESULT_LIMIT");
    }
    if result.summary.chars().count() > SUMMARY_BYTES {
        return error("E_RESULT_LIMIT");
    }
    if result.findings.len() > MAX_FINDINGS {
        return error("E_RESULT_LIMIT");
    }
    let mut finding_ids = BTreeSet::new();
    for finding in &result.findings {
        if finding.id.is_empty()
            || finding.id.len() > 256
            || finding.title.chars().count() > 512
            || finding.explanation.chars().count() > 8192
            || finding.recommendation.chars().count() > 8192
            || !finding.confidence.is_finite()
            || !(0.0..=1.0).contains(&finding.confidence)
            || finding.evidence_ids.is_empty()
        {
            return error("E_RESULT_LIMIT");
        }
        validate_reference_ids(&finding.evidence_ids, MAX_EVIDENCE_IDS)?;
        validate_reference_ids(&finding.candidate_ids, MAX_EVIDENCE_IDS)?;
        if !finding_ids.insert(finding.id.as_str()) {
            return error("E_RESULT_LIMIT");
        }
    }
    if result
        .question_hash
        .as_deref()
        .is_some_and(|hash| !is_hex64(hash))
    {
        return error("E_RESULT_LIMIT");
    }
    if let Some(parent) = &result.parent_run_id {
        validate_id(parent)?;
    }
    if result.artifact_hashes.len() > MAX_ARTIFACT_HASHES {
        return error("E_RESULT_LIMIT");
    }
    let mut hashes = BTreeSet::new();
    for hash in &result.artifact_hashes {
        if !is_hex64(hash) || !hashes.insert(hash.as_str()) {
            return error("E_RESULT_LIMIT");
        }
    }
    Ok(())
}

fn save_result_unlocked(transaction: &Connection, result: &StoredResult) -> Result<(), String> {
    let conversation = read_record(transaction, &result.conversation_id)?
        .ok_or_else(|| "E_CONVERSATION_UNAVAILABLE".to_owned())?;
    let turn = conversation
        .turns
        .iter()
        .find(|turn| turn.run_id == result.run_id)
        .ok_or_else(|| "E_RESULT_TURN".to_owned())?;
    if turn.status != RunStatus::Succeeded
        || turn.generation != result.generation
        || conversation.analysis_session_id != result.session_id
    {
        return error("E_RESULT_TURN");
    }
    let finding_ids: Vec<&str> = result
        .findings
        .iter()
        .map(|finding| finding.id.as_str())
        .collect();
    if finding_ids
        != turn
            .finding_ids
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>()
    {
        return error("E_RESULT_REFERENCES");
    }
    for finding in &result.findings {
        if finding
            .evidence_ids
            .iter()
            .any(|id| !turn.evidence_ids.contains(id))
            || finding
                .candidate_ids
                .iter()
                .any(|id| !turn.candidate_ids.contains(id))
        {
            return error("E_RESULT_REFERENCES");
        }
    }
    if let Some(parent_run_id) = &result.parent_run_id {
        let local = conversation.turns.iter().any(|candidate| {
            candidate.run_id == *parent_run_id
                && candidate.generation < turn.generation
                && candidate.status.terminal()
        });
        let fork_parent = if conversation
            .turns
            .first()
            .is_some_and(|first| first.run_id == turn.run_id)
        {
            conversation
                .parent_conversation_id
                .as_deref()
                .map(|parent_id| read_record(transaction, parent_id))
                .transpose()?
                .flatten()
        } else {
            None
        };
        let parent_matches = fork_parent.is_some_and(|source| {
            source.analysis_session_id == conversation.analysis_session_id
                && source.context_hash == conversation.context_hash
                && source.turns.iter().any(|candidate| {
                    candidate.run_id == *parent_run_id && candidate.status.terminal()
                })
        });
        if !local && !parent_matches {
            return error("E_PARENT_TURN");
        }
    }
    let existing = read_result(transaction, &result.conversation_id, &result.run_id)?;
    if let Some(existing) = existing {
        if existing != *result {
            return error("E_IMMUTABLE_RESULT");
        }
        return Ok(());
    }
    let encoded = encode_result(result)?;
    transaction
        .execute(
            "INSERT OR IGNORE INTO conversation_result(conversation_id, run_id, record) VALUES (?1, ?2, ?3)",
            params![result.conversation_id, result.run_id, encoded],
        )
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    Ok(())
}

fn read_result(
    connection: &Connection,
    conversation_id: &str,
    run_id: &str,
) -> Result<Option<StoredResult>, String> {
    let encoded = connection
        .query_row(
            "SELECT record FROM conversation_result WHERE conversation_id = ?1 AND run_id = ?2",
            params![conversation_id, run_id],
            |row| row.get::<_, String>(0),
        )
        .optional()
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    let Some(encoded) = encoded else {
        return Ok(None);
    };
    if encoded.len() > MAX_RESULT_BYTES {
        return error("E_RESULT_LIMIT");
    }
    let result = decode_result(&encoded)?;
    if result.conversation_id != conversation_id || result.run_id != run_id {
        return error("E_STORAGE_CORRUPT");
    }
    Ok(Some(result))
}

// ---------------------------------------------------------------------------
// Shared storage helpers
// ---------------------------------------------------------------------------

fn read_record(connection: &Connection, id: &str) -> Result<Option<ConversationRecord>, String> {
    let encoded = connection
        .query_row(
            "SELECT record FROM conversation WHERE id = ?1",
            [id],
            |row| row.get::<_, String>(0),
        )
        .optional()
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    match encoded {
        None => Ok(None),
        Some(encoded) => {
            if encoded.len() > MAX_RECORD_BYTES {
                return error("E_CONVERSATION_SIZE");
            }
            decode_record(id, &encoded).map(Some)
        }
    }
}

fn write_record(connection: &Connection, record: &ConversationRecord) -> Result<(), String> {
    let encoded = encode_record(record)?;
    connection
        .execute(
            "INSERT INTO conversation(id, record) VALUES (?1, ?2) ON CONFLICT(id) DO UPDATE SET record=excluded.record",
            params![record.id, encoded],
        )
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    connection
        .execute(
            "INSERT INTO conversation_meta(id, updated_at) VALUES (?1, ?2) ON CONFLICT(id) DO UPDATE SET updated_at=excluded.updated_at",
            params![record.id, now_millis()],
        )
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    Ok(())
}

fn save_unlocked(connection: &Connection, record: &ConversationRecord) -> Result<(), String> {
    match read_record(connection, &record.id)? {
        None => {
            if conversation_count(connection)? >= MAX_CONVERSATIONS {
                return error("E_CONVERSATION_LIMIT");
            }
        }
        Some(previous) => validate_update(&previous, record)?,
    }
    write_record(connection, record)
}

fn conversation_ids(connection: &Connection) -> Result<Vec<String>, String> {
    let mut statement = connection
        .prepare("SELECT id FROM conversation ORDER BY id")
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    let rows = statement
        .query_map([], |row| row.get::<_, String>(0))
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    let mut ids = Vec::new();
    for row in rows {
        if ids.len() >= MAX_CONVERSATIONS {
            return error("E_CONVERSATION_LIMIT");
        }
        ids.push(row.map_err(|_| "E_STORAGE_IO".to_owned())?);
    }
    Ok(ids)
}

fn all_records_unlocked(connection: &Connection) -> Result<Vec<ConversationRecord>, String> {
    let ids = conversation_ids(connection)?;
    let mut records = Vec::with_capacity(ids.len());
    for id in ids {
        records.push(read_record(connection, &id)?.ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?);
    }
    Ok(records)
}

fn conversation_count(connection: &Connection) -> Result<usize, String> {
    let count: i64 = connection
        .query_row("SELECT COUNT(*) FROM conversation", [], |row| row.get(0))
        .map_err(|_| "E_STORAGE_IO".to_owned())?;
    usize::try_from(count).map_err(|_| "E_CONVERSATION_LIMIT".to_owned())
}

fn require_conversation(connection: &Connection, conversation_id: &str) -> Result<(), String> {
    match read_record(connection, conversation_id)? {
        Some(_) => Ok(()),
        None => error("E_CONVERSATION_UNAVAILABLE"),
    }
}

fn has_child(connection: &Connection, conversation_id: &str) -> Result<bool, String> {
    for id in conversation_ids(connection)? {
        if id == conversation_id {
            continue;
        }
        let Some(record) = read_record(connection, &id)? else {
            continue;
        };
        if record.parent_conversation_id.as_deref() == Some(conversation_id) {
            return Ok(true);
        }
    }
    Ok(false)
}

fn first_result_summary(
    connection: &Connection,
    record: &ConversationRecord,
) -> Result<Option<String>, String> {
    for turn in &record.turns {
        let Some(result) = read_result(connection, &record.id, &turn.run_id)? else {
            continue;
        };
        if !result.summary.trim().is_empty() {
            return Ok(Some(result.summary));
        }
    }
    Ok(None)
}

// ---------------------------------------------------------------------------
// Validation helpers
// ---------------------------------------------------------------------------

fn validate_id(id: &str) -> Result<(), String> {
    if id.is_empty() || id.chars().count() > 128 || id.chars().any(char::is_control) {
        return error("E_CONVERSATION_ID");
    }
    Ok(())
}

fn validate_reference_ids(ids: &[String], limit: usize) -> Result<(), String> {
    if ids.len() > limit {
        return error("E_RUN_REFERENCES");
    }
    let mut seen = BTreeSet::new();
    for id in ids {
        if id.is_empty() || id.chars().count() > 256 || id.chars().any(char::is_control) {
            return error("E_RUN_REFERENCES");
        }
        if !seen.insert(id.as_str()) {
            return error("E_RUN_REFERENCES");
        }
    }
    Ok(())
}

fn is_hex64(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn is_error_code(value: &str) -> bool {
    let mut bytes = value.bytes();
    if !value.starts_with("E_") || !(3..=83).contains(&value.len()) {
        return false;
    }
    bytes.all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || byte == b'_')
}

fn take_chars(value: &str, limit: usize) -> String {
    value.chars().take(limit).collect()
}

fn parse_status(value: &str) -> Result<RunStatus, String> {
    let status = RunStatus::from_id(value);
    if status.id() == value {
        Ok(status)
    } else {
        error("E_STORAGE_CORRUPT")
    }
}

fn string_field<'a>(value: &'a Value, field: &str) -> Result<&'a str, String> {
    value
        .get(field)
        .and_then(Value::as_str)
        .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())
}

fn string_array(value: &Value, field: &str) -> Result<Vec<String>, String> {
    let values = value
        .get(field)
        .and_then(Value::as_array)
        .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?;
    values
        .iter()
        .map(|entry| {
            entry
                .as_str()
                .map(str::to_owned)
                .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())
        })
        .collect()
}

fn exact_keys(value: &Value, keys: &[&str]) -> Result<(), String> {
    let object = value
        .as_object()
        .ok_or_else(|| "E_STORAGE_CORRUPT".to_owned())?;
    if object.len() != keys.len() || keys.iter().any(|key| !object.contains_key(*key)) {
        return error("E_STORAGE_CORRUPT");
    }
    Ok(())
}

fn now_millis() -> i64 {
    crate::platform::now_millis()
}

fn error<T>(code: &'static str) -> Result<T, String> {
    Err(code.to_owned())
}

// The bounded conversation_audit table exists for schema parity with
// ConversationRepository. MAX_AUDIT_PER_RUN documents its per-run bound; audit
// rows are not part of this store's public surface yet.

#[cfg(test)]
mod tests {
    use super::*;
    use aps_platform::TempWorkspace;

    fn hash(byte: u8) -> String {
        std::iter::repeat(char::from(byte)).take(64).collect()
    }

    fn workspace() -> TempWorkspace {
        let root = std::env::temp_dir().canonicalize().unwrap();
        TempWorkspace::create(&root).unwrap()
    }

    fn database(workspace: &TempWorkspace) -> PathBuf {
        workspace.path().join(CONVERSATION_DATABASE_FILE_NAME)
    }

    fn open(workspace: &TempWorkspace) -> ConversationStore {
        ConversationStore::open(database(workspace)).unwrap()
    }

    fn conversation(id: &str, session: &str, parent: Option<&str>) -> ConversationRecord {
        ConversationRecord::new(id, session, hash(b'a'))
            .unwrap()
            .with_parent(parent.map(str::to_owned))
            .unwrap()
    }

    fn run_turn(
        run_id: &str,
        generation: u64,
        status: RunStatus,
        evidence: &[&str],
        findings: &[&str],
    ) -> ConversationTurn {
        ConversationTurn {
            run_id: run_id.into(),
            generation,
            status,
            evidence_ids: evidence.iter().map(|id| (*id).to_owned()).collect(),
            candidate_ids: Vec::new(),
            finding_ids: findings.iter().map(|id| (*id).to_owned()).collect(),
            error_code: None,
        }
    }

    fn prepared(id: &str, session: &str) -> (ConversationRecord, StoredChatTurn) {
        let mut record = conversation(id, session, None);
        record.turns.push(run_turn(
            "run-1",
            1,
            RunStatus::AwaitingApproval,
            &["ev-1"],
            &[],
        ));
        let turn = StoredChatTurn::new(id, "run-1", 1, Some("Why is the frame slow?".into()), 1000)
            .unwrap();
        (record, turn)
    }

    fn succeeded(mut record: ConversationRecord) -> ConversationRecord {
        let turn = record.turns.last_mut().unwrap();
        turn.status = RunStatus::Succeeded;
        turn.finding_ids = vec!["finding-1".into()];
        record
    }

    fn stored_result(record: &ConversationRecord) -> StoredResult {
        let turn = record.last_turn().unwrap();
        StoredResult {
            conversation_id: record.id.clone(),
            run_id: turn.run_id.clone(),
            generation: turn.generation,
            session_id: record.analysis_session_id.clone(),
            model: "gpt-5".into(),
            summary: "Observed one slow frame".into(),
            findings: vec![StoredFinding {
                id: "finding-1".into(),
                severity: FindingSeverity::Warning,
                title: "Slow frame".into(),
                explanation: "One frame exceeded budget".into(),
                recommendation: "Collect more evidence".into(),
                confidence: 0.5,
                evidence_ids: vec!["ev-1".into()],
                candidate_ids: Vec::new(),
            }],
            saved_at_millis: 2000,
            question_hash: None,
            parent_run_id: None,
            artifact_hashes: vec![hash(b'c')],
        }
    }

    #[test]
    fn open_requires_absolute_path_with_existing_parent() {
        assert_eq!(
            ConversationStore::open("relative.sqlite3").err().unwrap(),
            "E_STORAGE_PATH"
        );
        let workspace = workspace();
        let missing_parent = workspace.path().join("missing").join("db.sqlite3");
        assert_eq!(
            ConversationStore::open(&missing_parent).err().unwrap(),
            "E_STORAGE_PATH"
        );
    }

    #[cfg(unix)]
    #[test]
    fn open_rejects_symlinked_database_and_sidecars() {
        use std::os::unix::fs::symlink;
        let workspace = workspace();
        let real = workspace.path().join("real.sqlite3");
        std::fs::write(&real, []).unwrap();
        let link = workspace.path().join("linked.sqlite3");
        symlink(&real, &link).unwrap();
        assert_eq!(
            ConversationStore::open(&link).err().unwrap(),
            "E_STORAGE_SYMLINK"
        );

        let path = database(&workspace);
        drop(ConversationStore::open(&path).unwrap());
        std::fs::write(workspace.path().join("target.bin"), []).unwrap();
        let sidecar = workspace
            .path()
            .join(format!("{CONVERSATION_DATABASE_FILE_NAME}-wal"));
        symlink(workspace.path().join("target.bin"), &sidecar).unwrap();
        assert_eq!(
            ConversationStore::open(&path).err().unwrap(),
            "E_STORAGE_SYMLINK"
        );
    }

    #[test]
    fn reopen_preserves_prepared_result_and_transcript() {
        let workspace = workspace();
        let path = database(&workspace);
        let mut store = ConversationStore::open(&path).unwrap();
        let (record, prepared_turn) = prepared("conversation-1", "session-1");
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        store.save_prepared(&record, &prepared_turn).unwrap();
        let completed = succeeded(record.clone());
        let result = stored_result(&completed);
        store.complete(&completed, &result).unwrap();
        drop(store);

        let store = ConversationStore::open(&path).unwrap();
        let stored = store.record("conversation-1").unwrap().unwrap();
        assert_eq!(stored.turns.len(), 1);
        assert_eq!(stored.turns[0].status, RunStatus::Succeeded);
        assert_eq!(
            store.chat_turns("conversation-1").unwrap(),
            vec![prepared_turn]
        );
        assert_eq!(
            store.result("conversation-1", "run-1").unwrap().unwrap(),
            result
        );
        let transcript = store.transcript("conversation-1").unwrap();
        assert_eq!(transcript.len(), 1);
        assert_eq!(
            transcript[0].question.as_deref(),
            Some("Why is the frame slow?")
        );
        assert_eq!(transcript[0].created_at_millis, 1000);
        assert!(transcript[0].result.is_some());
    }

    #[test]
    fn rename_pin_archive_and_summary_titles() {
        let workspace = workspace();
        let mut store = open(&workspace);
        for (id, session) in [
            ("conversation-a", "session-a"),
            ("conversation-b", "session-b"),
            ("conversation-c", "session-c"),
        ] {
            store
                .create_conversation(id, session, &hash(b'a'), None)
                .unwrap();
        }
        store
            .rename_conversation("conversation-a", "Frame timing")
            .unwrap();
        store.pin_conversation("conversation-b", true).unwrap();
        store.archive_conversation("conversation-c", true).unwrap();
        let summaries = store.summaries(false).unwrap();
        assert_eq!(summaries.len(), 2);
        assert_eq!(summaries[0].id, "conversation-b");
        assert!(summaries[0].pinned);
        assert_eq!(summaries[1].title, "Frame timing");
        assert_eq!(summaries[1].updated_at_millis > 0, true);
        let archived = store.summaries(true).unwrap();
        assert_eq!(archived.len(), 3);
        assert!(
            archived
                .iter()
                .any(|summary| summary.id == "conversation-c" && summary.archived)
        );
        assert_eq!(archived.iter().filter(|row| row.pinned).count(), 1);
    }

    #[test]
    fn summaries_derive_titles_from_question_and_result() {
        let workspace = workspace();
        let mut store = open(&workspace);
        let (record, prepared_turn) = prepared("conversation-1", "session-1");
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        store.save_prepared(&record, &prepared_turn).unwrap();
        let summaries = store.summaries(false).unwrap();
        assert_eq!(summaries[0].title, "Why is the frame slow?");
        assert_eq!(summaries[0].turn_count, 1);
        assert_eq!(summaries[0].status, Some(RunStatus::AwaitingApproval));

        let completed = succeeded(record);
        let result = stored_result(&completed);
        store.complete(&completed, &result).unwrap();
        store
            .rename_conversation("conversation-1", "Renamed")
            .unwrap();
        store
            .create_conversation("conversation-2", "session-2", &hash(b'a'), None)
            .unwrap();
        let mut derived = conversation("conversation-2", "session-2", None);
        derived.turns.push(run_turn(
            "run-2",
            1,
            RunStatus::Succeeded,
            &["ev-2"],
            &["finding-2"],
        ));
        let derived_result = StoredResult {
            conversation_id: "conversation-2".into(),
            run_id: "run-2".into(),
            generation: 1,
            session_id: "session-2".into(),
            model: "gpt-5".into(),
            summary: "Derived from the result summary".into(),
            findings: vec![StoredFinding {
                id: "finding-2".into(),
                severity: FindingSeverity::Info,
                title: "Bounded".into(),
                explanation: "Bounded evidence".into(),
                recommendation: "Collect more".into(),
                confidence: 0.25,
                evidence_ids: vec!["ev-2".into()],
                candidate_ids: Vec::new(),
            }],
            saved_at_millis: 3000,
            question_hash: None,
            parent_run_id: None,
            artifact_hashes: Vec::new(),
        };
        derived_result.validate().unwrap();
        store.complete(&derived, &derived_result).unwrap();
        let summaries = store.summaries(false).unwrap();
        let titles: BTreeMap<&str, &str> = summaries
            .iter()
            .map(|summary| (summary.id.as_str(), summary.title.as_str()))
            .collect();
        assert_eq!(titles["conversation-1"], "Renamed");
        assert_eq!(titles["conversation-2"], "Derived from the result summary");

        store
            .create_conversation("conversation-3", "session-3", &hash(b'a'), None)
            .unwrap();
        let summaries = store.summaries(false).unwrap();
        assert_eq!(
            summaries
                .iter()
                .find(|summary| summary.id == "conversation-3")
                .unwrap()
                .title,
            DEFAULT_TITLE
        );
    }

    #[test]
    fn summaries_hide_graph_owned_children_but_keep_user_forks() {
        let workspace = workspace();
        let mut store = open(&workspace);
        store
            .create_conversation("parent", "session-1", &hash(b'a'), None)
            .unwrap();
        store
            .create_conversation("fork", "session-1", &hash(b'a'), Some("parent"))
            .unwrap();
        store
            .create_conversation("graph-child", "session-2", &hash(b'a'), Some("parent"))
            .unwrap();
        let summaries = store.summaries(true).unwrap();
        assert_eq!(summaries.len(), 2);
        assert!(summaries.iter().any(|row| row.id == "parent"));
        assert!(summaries.iter().any(|row| row.id == "fork"));
        assert!(!summaries.iter().any(|row| row.id == "graph-child"));
    }

    #[test]
    fn delete_refuses_user_branches_and_cascades_session_children() {
        let workspace = workspace();
        let mut store = open(&workspace);
        store
            .create_conversation("root", "session-1", &hash(b'a'), None)
            .unwrap();
        store
            .create_conversation("same-session-child", "session-1", &hash(b'a'), Some("root"))
            .unwrap();
        store
            .create_conversation("branch", "session-2", &hash(b'a'), Some("root"))
            .unwrap();
        store.rename_conversation("root", "Root").unwrap();
        assert_eq!(
            store.delete_chat_conversation("root").err().unwrap(),
            "E_CONVERSATION_HAS_CHILDREN"
        );
        assert!(store.delete_chat_conversation("branch").unwrap());
        assert!(store.record("branch").unwrap().is_none());
        assert!(store.delete_chat_conversation("root").unwrap());
        assert!(store.record("root").unwrap().is_none());
        assert!(store.record("same-session-child").unwrap().is_none());
        assert!(store.chat_metadata("root").unwrap().is_none());
        assert!(store.list(10, 0).unwrap().is_empty());
        assert!(!store.delete_chat_conversation("missing").unwrap());
    }

    #[test]
    fn delete_refuses_active_conversations() {
        let workspace = workspace();
        let mut store = open(&workspace);
        let (record, turn) = prepared("conversation-1", "session-1");
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        store.save_prepared(&record, &turn).unwrap();
        assert_eq!(
            store
                .delete_chat_conversation("conversation-1")
                .err()
                .unwrap(),
            "E_CONVERSATION_ACTIVE"
        );
        assert_eq!(
            store.clear_history().err().unwrap(),
            "E_CONVERSATION_ACTIVE"
        );
    }

    #[test]
    fn bounded_paging_and_question_title_limits() {
        let workspace = workspace();
        let mut store = open(&workspace);
        for id in ["conversation-a", "conversation-b", "conversation-c"] {
            store
                .create_conversation(id, id, &hash(b'a'), None)
                .unwrap();
        }
        assert_eq!(store.list(0, 0).err().unwrap(), "E_STORAGE_PAGE");
        assert_eq!(
            store.list(MAX_PAGE_SIZE + 1, 0).err().unwrap(),
            "E_STORAGE_PAGE"
        );
        assert_eq!(
            store.list(1, MAX_CONVERSATIONS + 1).err().unwrap(),
            "E_STORAGE_PAGE"
        );
        let page = store.list(2, 0).unwrap();
        assert_eq!(page.len(), 2);
        assert_eq!(page[0].id, "conversation-a");
        assert_eq!(store.list(2, 2).unwrap()[0].id, "conversation-c");

        assert_eq!(
            StoredChatTurn::new("conversation-a", "run-1", 1, Some(String::new()), 1)
                .err()
                .unwrap(),
            "E_CHAT_TURN"
        );
        assert_eq!(
            StoredChatTurn::new("conversation-a", "run-1", 1, Some("   ".into()), 1)
                .err()
                .unwrap(),
            "E_CHAT_TURN"
        );
        assert_eq!(
            StoredChatTurn::new(
                "conversation-a",
                "run-1",
                1,
                Some("x".repeat(MAX_QUESTION_BYTES + 1)),
                1
            )
            .err()
            .unwrap(),
            "E_CHAT_TURN"
        );
        assert!(
            StoredChatTurn::new("conversation-a", "run-1", 1, Some("line\nbreak".into()), 1)
                .is_ok()
        );
        assert_eq!(
            store
                .rename_conversation("conversation-a", "  ")
                .err()
                .unwrap(),
            "E_CHAT_TITLE"
        );
        assert_eq!(
            store
                .rename_conversation("conversation-a", &"x".repeat(MAX_TITLE_BYTES + 1))
                .err()
                .unwrap(),
            "E_CHAT_TITLE"
        );
    }

    #[test]
    fn retention_respects_pins_and_age() {
        let workspace = workspace();
        let mut store = open(&workspace);
        for id in ["conversation-a", "conversation-b", "conversation-c"] {
            store
                .create_conversation(id, id, &hash(b'a'), None)
                .unwrap();
        }
        store.pin_conversation("conversation-c", true).unwrap();
        assert_eq!(store.retention(2, 0, true).unwrap(), 1);
        assert!(store.record("conversation-a").unwrap().is_none());
        assert!(store.record("conversation-c").unwrap().is_some());
        let future = now_millis() + 1_000;
        assert_eq!(store.retention(100, future, true).unwrap(), 1);
        assert!(store.record("conversation-c").unwrap().is_some());
        assert_eq!(store.retention(100, future, false).unwrap(), 1);
        assert!(store.list(10, 0).unwrap().is_empty());
        assert_eq!(
            store
                .retention(MAX_CONVERSATIONS + 1, 0, false)
                .err()
                .unwrap(),
            "E_RETENTION_POLICY"
        );
        assert_eq!(
            store.retention(0, -1, false).err().unwrap(),
            "E_RETENTION_POLICY"
        );
    }

    #[test]
    fn corrupt_rows_report_storage_corrupt_without_panicking() {
        let workspace = workspace();
        let path = database(&workspace);
        let mut store = ConversationStore::open(&path).unwrap();
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        drop(store);
        let connection = rusqlite::Connection::open(&path).unwrap();
        connection
            .execute("UPDATE conversation SET record = '{'", [])
            .unwrap();
        drop(connection);
        let store = ConversationStore::open(&path).unwrap();
        assert_eq!(
            store.record("conversation-1").err().unwrap(),
            "E_STORAGE_CORRUPT"
        );
        assert_eq!(store.summaries(false).err().unwrap(), "E_STORAGE_CORRUPT");
    }

    #[test]
    fn recover_interrupted_never_replays_runs() {
        let workspace = workspace();
        let mut store = open(&workspace);
        let (record, turn) = prepared("conversation-1", "session-1");
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        store.save_prepared(&record, &turn).unwrap();
        assert_eq!(store.recover_interrupted().unwrap(), 1);
        let recovered = store.record("conversation-1").unwrap().unwrap();
        assert_eq!(recovered.turns[0].status, RunStatus::Interrupted);
        assert_eq!(
            recovered.turns[0].error_code.as_deref(),
            Some("E_RUN_INTERRUPTED")
        );
        assert_eq!(store.chat_turns("conversation-1").unwrap().len(), 1);
        assert_eq!(store.recover_interrupted().unwrap(), 0);
    }

    #[test]
    fn identity_and_history_are_immutable_after_the_first_turn() {
        let workspace = workspace();
        let mut store = open(&workspace);
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        store
            .set_context_digest("conversation-1", &hash(b'b'))
            .unwrap();
        store
            .set_session_id("conversation-1", "session-updated")
            .unwrap();
        let (mut record, turn) = prepared("conversation-1", "session-updated");
        record.context_hash = hash(b'b');
        store.save_prepared(&record, &turn).unwrap();
        assert_eq!(
            store
                .set_context_digest("conversation-1", &hash(b'c'))
                .err()
                .unwrap(),
            "E_IMMUTABLE_CONTEXT"
        );
        assert_eq!(
            store
                .set_session_id("conversation-1", "session-other")
                .err()
                .unwrap(),
            "E_IMMUTABLE_CONTEXT"
        );
        assert_eq!(
            store
                .create_conversation("conversation-1", "session-other", &hash(b'b'), None)
                .err()
                .unwrap(),
            "E_IMMUTABLE_CONTEXT"
        );
        let completed = succeeded(record.clone());
        let result = stored_result(&completed);
        store.complete(&completed, &result).unwrap();
        let mut stale = completed.clone();
        stale.turns.clear();
        assert_eq!(
            store.save_conversation(&stale).err().unwrap(),
            "E_STALE_CONVERSATION"
        );
        let mut changed = completed.clone();
        changed.context_hash = hash(b'd');
        assert_eq!(
            store.save_conversation(&changed).err().unwrap(),
            "E_IMMUTABLE_CONTEXT"
        );
        assert_eq!(
            store.save_conversation(&record).err().unwrap(),
            "E_STALE_CONVERSATION"
        );
    }

    #[test]
    fn results_validate_turn_references_and_are_immutable() {
        let workspace = workspace();
        let mut store = open(&workspace);
        let (record, turn) = prepared("conversation-1", "session-1");
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        store.save_prepared(&record, &turn).unwrap();
        let result = stored_result(&record);
        assert_eq!(store.save_result(&result).err().unwrap(), "E_RESULT_TURN");
        let completed = succeeded(record);
        let result = stored_result(&completed);
        store.complete(&completed, &result).unwrap();
        assert_eq!(store.save_result(&result).unwrap(), ());
        let mut changed = result.clone();
        changed.summary = "Different summary".into();
        assert_eq!(
            store.save_result(&changed).err().unwrap(),
            "E_IMMUTABLE_RESULT"
        );
        let mut unknown_evidence = result.clone();
        unknown_evidence.findings[0].evidence_ids = vec!["ev-2".into()];
        assert_eq!(
            store.save_result(&unknown_evidence).err().unwrap(),
            "E_RESULT_REFERENCES"
        );
        let mut unknown_finding = result.clone();
        unknown_finding.findings[0].id = "finding-2".into();
        assert_eq!(
            store.save_result(&unknown_finding).err().unwrap(),
            "E_RESULT_REFERENCES"
        );
        let mut oversized = result;
        oversized.summary = "x".repeat(SUMMARY_BYTES + 1);
        assert_eq!(
            store.save_result(&oversized).err().unwrap(),
            "E_RESULT_LIMIT"
        );
    }

    #[test]
    fn chat_turns_are_immutable_and_failures_are_terminal() {
        let workspace = workspace();
        let mut store = open(&workspace);
        let (record, turn) = prepared("conversation-1", "session-1");
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        store.save_prepared(&record, &turn).unwrap();
        assert!(store.save_prepared(&record, &turn).is_ok());
        let mut changed = turn.clone();
        changed.question = Some("Different question".into());
        assert_eq!(
            store.save_prepared(&record, &changed).err().unwrap(),
            "E_IMMUTABLE_CHAT_TURN"
        );
        assert_eq!(
            store
                .save_failed(
                    "conversation-1",
                    "run-1",
                    RunStatus::Running,
                    "E_AI_TIMEOUT"
                )
                .err()
                .unwrap(),
            "E_RUN_STATE"
        );
        store
            .save_failed(
                "conversation-1",
                "run-1",
                RunStatus::RetryableFailure,
                "E_AI_TIMEOUT",
            )
            .unwrap();
        let failed = store.record("conversation-1").unwrap().unwrap();
        assert_eq!(failed.turns[0].status, RunStatus::RetryableFailure);
        assert_eq!(failed.turns[0].error_code.as_deref(), Some("E_AI_TIMEOUT"));
    }

    #[test]
    fn clear_history_removes_every_row() {
        let workspace = workspace();
        let mut store = open(&workspace);
        let (record, turn) = prepared("conversation-1", "session-1");
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        store.save_prepared(&record, &turn).unwrap();
        store
            .save_failed(
                "conversation-1",
                "run-1",
                RunStatus::RetryableFailure,
                "E_AI_TIMEOUT",
            )
            .unwrap();
        store
            .create_conversation("conversation-2", "session-2", &hash(b'a'), None)
            .unwrap();
        store.clear_history().unwrap();
        assert!(store.list(10, 0).unwrap().is_empty());
        assert!(store.chat_turns("conversation-1").unwrap().is_empty());
        assert!(store.chat_metadata("conversation-1").unwrap().is_none());
    }

    #[test]
    fn parent_rules_reject_unknown_parent_turns() {
        let workspace = workspace();
        let mut store = open(&workspace);
        let (record, _) = prepared("conversation-1", "session-1");
        store
            .create_conversation("conversation-1", "session-1", &hash(b'a'), None)
            .unwrap();
        let turn = StoredChatTurn::new("conversation-1", "run-1", 1, None, 1)
            .unwrap()
            .with_parent(Some("run-missing".into()))
            .unwrap();
        assert_eq!(
            store.save_prepared(&record, &turn).err().unwrap(),
            "E_PARENT_TURN"
        );
    }
}
