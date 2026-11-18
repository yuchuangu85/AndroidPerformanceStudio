//! Experimental independent Simpleperf parser/projection; Kotlin remains unchanged.
mod analysis;
mod protocol;
mod query;
pub mod simpleperf;

pub use analysis::{analyze, AnalysisResult, CallTreeNode, ProfileSummary};
pub use aps_primitives::wire;
pub use protocol::{
    analyze_path, input_limit_from_env, query_path, serve_binary, write_query_page,
};
pub use query::{
    prepare, project, sample_leaf_node_id, Direction, PreparedQuery, Query, QueryPage, QueryRow,
    WeightType, MAX_PAGE_SIZE,
};
