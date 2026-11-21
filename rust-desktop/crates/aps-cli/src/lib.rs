//! Headless host adapters. The CLI and MCP share analysis, not authority.
mod analysis;
mod benchmark_source;
mod capabilities;
pub mod cli;
pub mod input;
pub mod mcp;

// Preserve existing library paths as well as the two executable entry points.
pub use analysis::{
    AnalyzeOptions, EVIDENCE_SCHEMA, FrameScope, MAX_INPUT_BYTES, OutputFormat, analyze_path,
    analyze_path_with_scope, frame_scope,
};
pub use capabilities::{DEFERRED_CAPTURE, DEFERRED_INPUT, capabilities};
pub use cli::{ai, benchmark_binary, cpu_capture, run_macrobenchmark, scenario, write_new};
