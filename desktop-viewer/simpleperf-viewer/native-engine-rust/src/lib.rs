//! Experimental independent Simpleperf parser/projection; Kotlin remains unchanged.
mod analysis;
mod protocol;
mod query;
pub mod simpleperf;

pub use analysis::{analyze, AnalysisResult, CallTreeNode, ProfileSummary};
// Share the existing std-only codec without compiling the unrelated heap engine.
#[path = "../../../memory-profiler/hprof-engine-rust/src/wire.rs"]
#[rustfmt::skip]
pub mod wire;
pub use protocol::{
    analyze_path, input_limit_from_env, query_path, serve_binary, write_query_page,
};
pub use query::{project, Direction, Query, QueryPage, QueryRow, WeightType, MAX_PAGE_SIZE};
