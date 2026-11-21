use aps_gpu::{GpuAnalysisPage, decode_analysis_page, summarize_analysis};
use aps_perfetto::{QueryPage, QueryPreset};

fn page(preset: QueryPreset, rows: Vec<Vec<Option<&str>>>) -> QueryPage {
    QueryPage {
        preset,
        source_digest: aps_contracts::SourceDigest::from_bytes([0; 32]),
        columns: Vec::new(),
        rows: rows
            .into_iter()
            .map(|row| {
                row.into_iter()
                    .map(|value| value.map(str::to_owned))
                    .collect()
            })
            .collect(),
        offset: 0,
        has_more: false,
        text_preview_characters: 4096,
    }
}

#[test]
fn decodes_fixed_gpu_query_shapes_and_summarizes_real_values() {
    let frequency = decode_analysis_page(&page(
        QueryPreset::GpuFrequency,
        vec![vec![Some("0"), Some("800000"), Some("3.5"), Some("10")]],
    ))
    .unwrap();
    let memory = decode_analysis_page(&page(
        QueryPreset::GpuMemory,
        vec![vec![Some("11"), Some("12"), Some("4096")]],
    ))
    .unwrap();
    let stages = decode_analysis_page(&page(
        QueryPreset::GpuRenderStages,
        vec![vec![
            Some("1"),
            Some("20"),
            Some("30"),
            Some("4"),
            Some("fragment"),
        ]],
    ))
    .unwrap();
    let summary = summarize_analysis(&[frequency.clone(), memory.clone(), stages.clone()]);
    assert_eq!(summary.frequency_samples, 1);
    assert_eq!(summary.memory_samples, 1);
    assert_eq!(summary.render_stages, 1);
    assert_eq!(summary.max_frequency_khz, Some(800000.0));
    assert_eq!(summary.max_memory_bytes, Some(4096.0));
    assert_eq!(summary.total_render_duration_ns, 30);
    assert!(matches!(frequency, GpuAnalysisPage::Frequency(_)));
    assert!(matches!(memory, GpuAnalysisPage::Memory(_)));
    assert!(matches!(stages, GpuAnalysisPage::RenderStages(_)));
}
