//! Bounded GPU analysis projections for verified Perfetto query pages.
//!
//! This module intentionally consumes already-validated columns/rows from the
//! pinned Trace Processor adapter. It does not parse AGI binaries or accept SQL.

use aps_perfetto::{QueryPage, QueryPreset};

#[derive(Clone, Debug, PartialEq)]
pub struct GpuFrequencySample {
    pub gpu_id: String,
    pub frequency_khz: f64,
    pub duration_ms: f64,
    pub timestamp_ns: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct GpuMemorySample {
    pub timestamp_ns: u64,
    pub duration_ns: u64,
    pub memory_bytes: f64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct GpuRenderStage {
    pub id: u64,
    pub timestamp_ns: u64,
    pub duration_ns: u64,
    pub track_id: u64,
    pub name: String,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct GpuAnalysisSummary {
    pub frequency_samples: usize,
    pub memory_samples: usize,
    pub render_stages: usize,
    pub max_frequency_khz: Option<f64>,
    pub max_memory_bytes: Option<f64>,
    pub total_render_duration_ns: u128,
}

#[derive(Clone, Debug, PartialEq)]
pub enum GpuAnalysisPage {
    Frequency(Vec<GpuFrequencySample>),
    Memory(Vec<GpuMemorySample>),
    RenderStages(Vec<GpuRenderStage>),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GpuAnalysisError {
    WrongPreset,
    InvalidResult,
}

impl std::fmt::Display for GpuAnalysisError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::WrongPreset => "GPU query page uses the wrong preset",
            Self::InvalidResult => "GPU query page contains an invalid numeric value",
        })
    }
}
impl std::error::Error for GpuAnalysisError {}

pub fn decode(page: &QueryPage) -> Result<GpuAnalysisPage, GpuAnalysisError> {
    match page.preset {
        QueryPreset::GpuFrequency => Ok(GpuAnalysisPage::Frequency(
            page.rows
                .iter()
                .map(|row| decode_frequency(row))
                .collect::<Result<_, _>>()?,
        )),
        QueryPreset::GpuMemory => Ok(GpuAnalysisPage::Memory(
            page.rows
                .iter()
                .map(|row| decode_memory(row))
                .collect::<Result<_, _>>()?,
        )),
        QueryPreset::GpuRenderStages => Ok(GpuAnalysisPage::RenderStages(
            page.rows
                .iter()
                .map(|row| decode_stage(row))
                .collect::<Result<_, _>>()?,
        )),
        _ => Err(GpuAnalysisError::WrongPreset),
    }
}

pub fn summarize(pages: &[GpuAnalysisPage]) -> GpuAnalysisSummary {
    let mut summary = GpuAnalysisSummary::default();
    for page in pages {
        match page {
            GpuAnalysisPage::Frequency(rows) => {
                summary.frequency_samples += rows.len();
                for row in rows {
                    summary.max_frequency_khz = Some(
                        summary
                            .max_frequency_khz
                            .map_or(row.frequency_khz, |max| max.max(row.frequency_khz)),
                    );
                }
            }
            GpuAnalysisPage::Memory(rows) => {
                summary.memory_samples += rows.len();
                for row in rows {
                    summary.max_memory_bytes = Some(
                        summary
                            .max_memory_bytes
                            .map_or(row.memory_bytes, |max| max.max(row.memory_bytes)),
                    );
                }
            }
            GpuAnalysisPage::RenderStages(rows) => {
                summary.render_stages += rows.len();
                summary.total_render_duration_ns += rows
                    .iter()
                    .map(|row| u128::from(row.duration_ns))
                    .sum::<u128>();
            }
        }
    }
    summary
}

fn decode_frequency(row: &[Option<String>]) -> Result<GpuFrequencySample, GpuAnalysisError> {
    if row.len() != 4 {
        return Err(GpuAnalysisError::InvalidResult);
    }
    Ok(GpuFrequencySample {
        gpu_id: text(&row[0])?,
        frequency_khz: number(&row[1])?,
        duration_ms: number(&row[2])?,
        timestamp_ns: integer(&row[3])?,
    })
}
fn decode_memory(row: &[Option<String>]) -> Result<GpuMemorySample, GpuAnalysisError> {
    if row.len() != 3 {
        return Err(GpuAnalysisError::InvalidResult);
    }
    Ok(GpuMemorySample {
        timestamp_ns: integer(&row[0])?,
        duration_ns: integer(&row[1])?,
        memory_bytes: number(&row[2])?,
    })
}
fn decode_stage(row: &[Option<String>]) -> Result<GpuRenderStage, GpuAnalysisError> {
    if row.len() != 5 {
        return Err(GpuAnalysisError::InvalidResult);
    }
    Ok(GpuRenderStage {
        id: integer(&row[0])?,
        timestamp_ns: integer(&row[1])?,
        duration_ns: integer(&row[2])?,
        track_id: integer(&row[3])?,
        name: text(&row[4])?,
    })
}
fn text(value: &Option<String>) -> Result<String, GpuAnalysisError> {
    value.clone().ok_or(GpuAnalysisError::InvalidResult)
}
fn number(value: &Option<String>) -> Result<f64, GpuAnalysisError> {
    value
        .as_deref()
        .ok_or(GpuAnalysisError::InvalidResult)?
        .parse()
        .map_err(|_| GpuAnalysisError::InvalidResult)
}
fn integer(value: &Option<String>) -> Result<u64, GpuAnalysisError> {
    value
        .as_deref()
        .ok_or(GpuAnalysisError::InvalidResult)?
        .parse()
        .map_err(|_| GpuAnalysisError::InvalidResult)
}
