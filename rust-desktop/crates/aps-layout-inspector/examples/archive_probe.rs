//! Read-only diagnostic: validate a capture and print counts, never runtime values.
use aps_layout_inspector::InspectorArchive;
use aps_platform::CancellationToken;

fn main() {
    let Some(path) = std::env::args_os().nth(1) else {
        eprintln!("usage: archive_probe <capture.zip>");
        std::process::exit(2);
    };
    // Mirror a background worker's stack rather than depending on the larger
    // main-thread stack for deeply nested archive deserialization.
    let archive = std::thread::spawn(move || {
        InspectorArchive::open(std::path::Path::new(&path), &CancellationToken::new())
    })
    .join()
    .expect("archive diagnostic worker failed")
    .unwrap_or_else(|error| {
        eprintln!("{error}");
        std::process::exit(1);
    });
    let nodes = archive
        .windows
        .iter()
        .map(|window| window.nodes.len())
        .sum::<usize>();
    let depth = archive
        .windows
        .iter()
        .flat_map(|window| &window.nodes)
        .map(|node| node.depth)
        .max()
        .unwrap_or(0);
    let compose = archive.compose.as_ref();
    println!(
        "archiveVersion={} windows={} nodes={} maxDepth={} composeNodes={} composeDetails={} coverageRecords={}",
        archive.archive_version,
        archive.windows.len(),
        nodes,
        depth,
        compose.map_or(0, |evidence| evidence.nodes.len()),
        compose.map_or(0, |evidence| evidence.details.len()),
        compose.map_or(0, |evidence| evidence.coverage_record_count)
    );
}
