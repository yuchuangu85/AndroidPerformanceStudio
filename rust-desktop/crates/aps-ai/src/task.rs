//! Shared analysis lifecycle projection. These facts do not grant execution,
//! credential or publication authority to an observer.
use crate::report::{ReportError, ReportReceipt};
use crate::{AiError, AnalysisResult, ReportFormat};

/// Stable wire/storage IDs; the lifecycle describes the model run, not report I/O.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RunStatus {
    Queued,
    Preparing,
    AwaitingApproval,
    Running,
    Succeeded,
    Cancelled,
    RetryableFailure,
    PermanentFailure,
    Interrupted,
}

impl RunStatus {
    pub fn terminal(self) -> bool {
        matches!(
            self,
            Self::Succeeded
                | Self::Cancelled
                | Self::RetryableFailure
                | Self::PermanentFailure
                | Self::Interrupted
        )
    }
    pub fn allows_chat_retry(self) -> bool {
        matches!(
            self,
            Self::RetryableFailure | Self::PermanentFailure | Self::Interrupted
        )
    }
    pub fn id(self) -> &'static str {
        match self {
            Self::Queued => "queued",
            Self::Preparing => "preparing",
            Self::AwaitingApproval => "awaiting_approval",
            Self::Running => "running",
            Self::Succeeded => "succeeded",
            Self::Cancelled => "cancelled",
            Self::RetryableFailure => "retryable_failure",
            Self::PermanentFailure => "permanent_failure",
            Self::Interrupted => "interrupted",
        }
    }
    pub fn from_id(value: &str) -> Self {
        match value {
            "queued" => Self::Queued,
            "preparing" => Self::Preparing,
            "awaiting_approval" => Self::AwaitingApproval,
            "running" => Self::Running,
            "succeeded" => Self::Succeeded,
            "cancelled" => Self::Cancelled,
            "retryable_failure" => Self::RetryableFailure,
            "interrupted" => Self::Interrupted,
            _ => Self::PermanentFailure,
        }
    }
}

impl AiError {
    pub fn run_status(self) -> RunStatus {
        match self {
            Self::Cancelled => RunStatus::Cancelled,
            Self::Transport | Self::Timeout => RunStatus::RetryableFailure,
            Self::HttpStatus(status) if status == 408 || status == 429 || status >= 500 => {
                RunStatus::RetryableFailure
            }
            _ => RunStatus::PermanentFailure,
        }
    }
}

/// Receiving this outcome means the provider response was accepted; reporting is
/// a separate operation and must not trigger another network run on failure.
pub struct AnalysisOutcome {
    pub result: AnalysisResult,
    pub report: ReportOutcome,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReportOutcome {
    NotRequested,
    Written(ReportReceipt),
    Failed {
        format: ReportFormat,
        error: ReportError,
    },
}

impl ReportOutcome {
    pub fn from_publication(
        format: ReportFormat,
        publication: Result<ReportReceipt, ReportError>,
    ) -> Self {
        match publication {
            Ok(receipt) => Self::Written(receipt),
            Err(error) => Self::Failed { format, error },
        }
    }
}

impl AnalysisOutcome {
    /// Retry only a previously requested *write*, from this retained answer.
    /// The host supplies a freshly selected/authorized destination with its own
    /// overwrite policy. No credentials, provider, or new analysis are involved.
    /// Disabled, completed and render/limit outcomes are left untouched.
    pub fn retry_report_write(
        &mut self,
        write: impl FnOnce(&str, &[u8]) -> Result<(), ReportError>,
    ) -> &ReportOutcome {
        if let ReportOutcome::Failed {
            format,
            error: ReportError::Write,
        } = self.report
        {
            self.report = ReportOutcome::from_publication(
                format,
                crate::report::publish(&self.result, format, write),
            );
        }
        &self.report
    }
}
