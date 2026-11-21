use crate::{ProfileSummary, Query, QueryRow, TopFunction};
use aps_simpleperf_engine::simpleperf::{
    FileRecord, Frame, Record, Sample, SimpleperfStream, MAX_CALLCHAIN_FRAMES,
};
use std::borrow::Cow;
use std::collections::{BTreeMap, HashMap};
use std::fs::File;
use std::io::Read;
use std::path::Path;

pub const MAX_CSV_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_FIREFOX_BYTES: usize = 64 * 1024 * 1024;
/// Folded stacks are one line per self-weighting call-tree row; bounded output.
pub const MAX_FOLDED_BYTES: usize = 32 * 1024 * 1024;
/// Machine-readable report JSON; bounded output.
pub const MAX_REPORT_JSON_BYTES: usize = 32 * 1024 * 1024;
/// Self-contained HTML report; bounded output.
pub const MAX_HTML_BYTES: usize = 32 * 1024 * 1024;
/// Whole self-weighted aggregation included in the JSON report.
pub const MAX_REPORT_TOP_FUNCTIONS: usize = 1_000;
/// Preorder call-tree rows included in the JSON report.
pub const MAX_REPORT_CALL_TREE_ROWS: usize = 5_000;
/// Top-function rows included in the HTML report.
pub const MAX_HTML_TOP_FUNCTIONS: usize = 200;
/// Preorder call-tree rows included in the HTML report.
pub const MAX_HTML_CALL_TREE_ROWS: usize = 1_000;
/// Hand-written gzip-compressed pprof protobuf; bounded output.
pub const MAX_PPROF_BYTES: usize = 64 * 1024 * 1024;
/// Hand-written store-only zip session package; bounded output.
pub const MAX_SESSION_PACKAGE_BYTES: usize = 256 * 1024 * 1024;

/// Native query CSV. Shared metric columns follow ReportExportService, but IDs
/// remain native ancestry IDs, not Kotlin node IDs. Thread counts are omitted
/// because the native tree projection does not expose that evidence.
pub(crate) fn call_tree_csv(rows: &[QueryRow], query: &Query) -> Result<String, String> {
    let mut csv = Csv::new(
        "id,parent_id,depth,symbol,file,inclusive_weight,exclusive_weight,sample_count,self_sample_count,weight_type,direction\n",
    );
    for row in rows {
        csv.row(&[
            &row.id,
            row.parent_id.as_deref().unwrap_or(""),
            &row.depth.to_string(),
            &row.name,
            &row.resource,
            &row.inclusive_weight.to_string(),
            &row.self_weight.to_string(),
            &row.sample_count.to_string(),
            &row.self_sample_count.to_string(),
            query.weight_type.as_str(),
            query.direction.as_str(),
        ])?;
    }
    Ok(csv.0)
}

/// Same leading columns and meanings as the existing top-functions exporter,
/// followed by explicit weight/direction and self-sample metadata.
pub(crate) fn top_functions_csv(rows: &[TopFunction], query: &Query) -> Result<String, String> {
    let mut csv = Csv::new(
        "symbol,file,inclusive_weight,exclusive_weight,sample_count,thread_count,self_sample_count,weight_type\n",
    );
    for row in rows {
        csv.row(&[
            &row.name,
            &row.resource,
            &row.inclusive_weight.to_string(),
            &row.self_weight.to_string(),
            &row.sample_count.to_string(),
            &row.thread_count.to_string(),
            &row.self_sample_count.to_string(),
            query.weight_type.as_str(),
        ])?;
    }
    Ok(csv.0)
}

/// Folded-stack text for flame-graph consumers: one root;branch;leaf weight
/// line per preorder call-tree row with self weight. Symbol-name delimiters are
/// normalised to spaces so every line stays parseable; rows without self weight
/// are skipped but still contribute ancestry to their children.
pub(crate) fn folded_stacks(rows: &[QueryRow]) -> Result<String, String> {
    let by_id: HashMap<&str, &QueryRow> = rows.iter().map(|row| (row.id.as_str(), row)).collect();
    let mut output = BoundedText::new(MAX_FOLDED_BYTES, "Folded-stack");
    let mut ancestry = Vec::new();
    for row in rows {
        if row.self_weight == 0 {
            continue;
        }
        ancestry.clear();
        let mut current = Some(row);
        while let Some(node) = current {
            ancestry.push(node.name.as_str());
            current = node
                .parent_id
                .as_deref()
                .and_then(|id| by_id.get(id).copied());
        }
        ancestry.reverse();
        for (index, name) in ancestry.iter().enumerate() {
            if index != 0 {
                output.push(";")?;
            }
            if name.contains([';', '\n', '\r']) {
                output.push(&name.replace([';', '\n', '\r'], " "))?;
            } else {
                output.push(name)?;
            }
        }
        output.push(" ")?;
        output.push(&row.self_weight.to_string())?;
        output.push("\n")?;
    }
    Ok(output.into_string())
}

/// One hand-written JSON object, bounded before publication. No serialization
/// dependency is added: escaping and numeric formatting are local and explicit.
pub(crate) fn report_json(
    summary: &ProfileSummary,
    query: &Query,
    top: &[TopFunction],
    tree: &[QueryRow],
) -> Result<String, String> {
    let mut json = BoundedText::new(MAX_REPORT_JSON_BYTES, "CPU report JSON");
    json.push("{\"kind\":\"aps-cpu-report-v1\",\"summary\":{\"samples\":")?;
    json.push(&summary.sample_count.to_string())?;
    json.push(",\"lostSamples\":")?;
    json.push(&summary.lost_sample_count.to_string())?;
    json.push(",\"reportedSamples\":")?;
    json.push(&summary.reported_sample_count.to_string())?;
    json.push(",\"totalEvents\":")?;
    json.push(&summary.total_event_count.to_string())?;
    json.push(",\"threads\":")?;
    json.push(&summary.thread_count.to_string())?;
    json.push(",\"files\":")?;
    json.push(&summary.file_count.to_string())?;
    json.push(",\"firstTimestampNanos\":")?;
    optional_number(&mut json, summary.first_timestamp_nanos)?;
    json.push(",\"lastTimestampNanos\":")?;
    optional_number(&mut json, summary.last_timestamp_nanos)?;
    json.push(",\"eventTypes\":[")?;
    for (index, event) in summary.event_types.iter().enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.string(event)?;
    }
    json.push("]},\"query\":{\"direction\":")?;
    json.string(query.direction.as_str())?;
    json.push(",\"weightType\":")?;
    json.string(query.weight_type.as_str())?;
    json.push(",\"threadIds\":[")?;
    for (index, thread) in query.thread_ids.iter().enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.push(&thread.to_string())?;
    }
    json.push("],\"eventTypes\":[")?;
    for (index, event) in query.event_types.iter().enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.string(event)?;
    }
    json.push("],\"startNanos\":")?;
    optional_number(&mut json, query.start)?;
    json.push(",\"endNanos\":")?;
    optional_number(&mut json, query.end)?;
    json.push("},\"topFunctions\":[")?;
    for (index, row) in top.iter().take(MAX_REPORT_TOP_FUNCTIONS).enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.push("{\"symbol\":")?;
        json.string(&row.name)?;
        json.push(",\"library\":")?;
        json.string(&row.resource)?;
        json.push(",\"inclusive\":")?;
        json.push(&row.inclusive_weight.to_string())?;
        json.push(",\"self\":")?;
        json.push(&row.self_weight.to_string())?;
        json.push(",\"samples\":")?;
        json.push(&row.sample_count.to_string())?;
        json.push(",\"threads\":")?;
        json.push(&row.thread_count.to_string())?;
        json.push("}")?;
    }
    json.push("],\"callTree\":[")?;
    for (index, row) in tree.iter().take(MAX_REPORT_CALL_TREE_ROWS).enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.push("{\"depth\":")?;
        json.push(&row.depth.to_string())?;
        json.push(",\"symbol\":")?;
        json.string(&row.name)?;
        json.push(",\"library\":")?;
        json.string(&row.resource)?;
        json.push(",\"inclusive\":")?;
        json.push(&row.inclusive_weight.to_string())?;
        json.push(",\"self\":")?;
        json.push(&row.self_weight.to_string())?;
        json.push(",\"samples\":")?;
        json.push(&row.sample_count.to_string())?;
        json.push("}")?;
    }
    json.push("],\"topFunctionsTotal\":")?;
    json.push(&top.len().to_string())?;
    json.push(",\"topFunctionsTruncated\":")?;
    json.push(if top.len() > MAX_REPORT_TOP_FUNCTIONS {
        "true"
    } else {
        "false"
    })?;
    json.push(",\"callTreeTotal\":")?;
    json.push(&tree.len().to_string())?;
    json.push(",\"callTreeTruncated\":")?;
    json.push(if tree.len() > MAX_REPORT_CALL_TREE_ROWS {
        "true"
    } else {
        "false"
    })?;
    json.push("}")?;
    Ok(json.into_string())
}

fn optional_number(json: &mut BoundedText, value: Option<u64>) -> Result<(), String> {
    match value {
        Some(value) => json.push(&value.to_string()),
        None => json.push("null"),
    }
}

/// Self-contained UTF-8 HTML report: inline CSS, dark theme, no scripts or
/// external assets. Every symbol, library, and event string is escaped.
pub(crate) fn html_report(
    summary: &ProfileSummary,
    top: &[TopFunction],
    tree: &[QueryRow],
) -> Result<String, String> {
    let mut html = BoundedText::new(MAX_HTML_BYTES, "CPU report HTML");
    html.push(HTML_PREFIX)?;
    html.push(
        "<h2>Summary</h2>\n<table><thead><tr><th>Metric</th><th>Value</th></tr></thead><tbody>\n",
    )?;
    summary_row(&mut html, "Samples", &summary.sample_count.to_string())?;
    summary_row(
        &mut html,
        "Lost samples",
        &summary.lost_sample_count.to_string(),
    )?;
    summary_row(
        &mut html,
        "Reported samples",
        &summary.reported_sample_count.to_string(),
    )?;
    summary_row(
        &mut html,
        "Total events",
        &summary.total_event_count.to_string(),
    )?;
    summary_row(&mut html, "Threads", &summary.thread_count.to_string())?;
    summary_row(&mut html, "Files", &summary.file_count.to_string())?;
    summary_row(
        &mut html,
        "First timestamp (ns)",
        &optional_number_text(summary.first_timestamp_nanos),
    )?;
    summary_row(
        &mut html,
        "Last timestamp (ns)",
        &optional_number_text(summary.last_timestamp_nanos),
    )?;
    summary_row(&mut html, "Event types", &summary.event_types.join(", "))?;
    html.push("</tbody></table>\n<h2>Top functions</h2>\n")?;
    html.push("<table><thead><tr><th>Symbol</th><th>Library</th><th>Inclusive</th><th>Self</th><th>Samples</th><th>Threads</th></tr></thead><tbody>\n")?;
    for row in top.iter().take(MAX_HTML_TOP_FUNCTIONS) {
        html.push("<tr><td class=\"sym\">")?;
        push_html(&mut html, &row.name)?;
        html.push("</td><td class=\"sym\">")?;
        push_html(&mut html, &row.resource)?;
        html_number_cell(&mut html, row.inclusive_weight)?;
        html_number_cell(&mut html, row.self_weight)?;
        html_number_cell(&mut html, row.sample_count)?;
        html_number_cell(&mut html, row.thread_count as u64)?;
        html.push("</tr>\n")?;
    }
    html.push("</tbody></table>\n")?;
    truncation_notice(
        &mut html,
        "Top functions",
        top.len(),
        MAX_HTML_TOP_FUNCTIONS,
    )?;
    html.push("<h2>Call tree</h2>\n")?;
    html.push("<table><thead><tr><th>Symbol</th><th>Library</th><th>Inclusive</th><th>Self</th><th>Samples</th></tr></thead><tbody>\n")?;
    for row in tree.iter().take(MAX_HTML_CALL_TREE_ROWS) {
        let indent = row.depth.saturating_mul(12);
        html.push("<tr><td class=\"sym\" style=\"padding-left:")?;
        html.push(&indent.to_string())?;
        html.push("px\">")?;
        push_html(&mut html, &row.name)?;
        html.push("</td><td class=\"sym\">")?;
        push_html(&mut html, &row.resource)?;
        html_number_cell(&mut html, row.inclusive_weight)?;
        html_number_cell(&mut html, row.self_weight)?;
        html_number_cell(&mut html, row.sample_count)?;
        html.push("</tr>\n")?;
    }
    html.push("</tbody></table>\n")?;
    truncation_notice(&mut html, "Call tree", tree.len(), MAX_HTML_CALL_TREE_ROWS)?;
    html.push("</body>\n</html>\n")?;
    Ok(html.into_string())
}

fn truncation_notice(
    html: &mut BoundedText,
    label: &str,
    total: usize,
    limit: usize,
) -> Result<(), String> {
    if total > limit {
        html.push("<p>")?;
        html.push(label)?;
        html.push(" truncated: showing ")?;
        html.push(&limit.to_string())?;
        html.push(" of ")?;
        html.push(&total.to_string())?;
        html.push(" rows.</p>\n")?;
    }
    Ok(())
}

fn summary_row(html: &mut BoundedText, label: &str, value: &str) -> Result<(), String> {
    html.push("<tr><th>")?;
    html.push(label)?;
    html.push("</th><td>")?;
    push_html(html, value)?;
    html.push("</td></tr>\n")
}

fn html_number_cell(html: &mut BoundedText, value: u64) -> Result<(), String> {
    html.push("<td class=\"n\">")?;
    html.push(&value.to_string())?;
    html.push("</td>")
}

fn optional_number_text(value: Option<u64>) -> String {
    value.map_or_else(|| "\u{2014}".into(), |value| value.to_string())
}

fn push_html(html: &mut BoundedText, value: &str) -> Result<(), String> {
    let escaped = html_escape(value);
    html.push(escaped.as_ref())
}

fn html_escape(value: &str) -> Cow<'_, str> {
    if !value.contains(['&', '<', '>', '"', '\'']) {
        return Cow::Borrowed(value);
    }
    let mut escaped = String::with_capacity(value.len() + 16);
    for character in value.chars() {
        match character {
            '&' => escaped.push_str("&amp;"),
            '<' => escaped.push_str("&lt;"),
            '>' => escaped.push_str("&gt;"),
            '"' => escaped.push_str("&quot;"),
            '\'' => escaped.push_str("&#39;"),
            other => escaped.push(other),
        }
    }
    Cow::Owned(escaped)
}

const HTML_PREFIX: &str = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>CPU Profiler report</title>\n<style>\n:root { color-scheme: dark; }\nbody { margin: 0; padding: 24px; background: #11151c; color: #e6edf3; font: 14px/1.5 ui-sans-serif, system-ui, -apple-system, \"Segoe UI\", sans-serif; }\nh1 { margin: 0 0 16px; font-size: 20px; }\nh2 { margin: 24px 0 8px; font-size: 16px; }\ntable { width: 100%; margin: 0 0 8px; border-collapse: collapse; }\nth, td { padding: 6px 10px; text-align: left; vertical-align: top; border-bottom: 1px solid #2a3441; }\nth { background: #1a2230; color: #9fb0c3; font-weight: 600; }\ntd.n { text-align: right; font-variant-numeric: tabular-nums; }\n.sym { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }\n</style>\n</head>\n<body>\n<h1>CPU Profiler report</h1>\n";

struct Csv(String);
impl Csv {
    fn new(header: &str) -> Self {
        Self(header.into())
    }
    fn push(&mut self, value: &str) -> Result<(), String> {
        if value.len() > MAX_CSV_BYTES.saturating_sub(self.0.len()) {
            return Err(format!(
                "CSV export exceeds {MAX_CSV_BYTES} byte output limit"
            ));
        }
        self.0.push_str(value);
        Ok(())
    }
    fn field(&mut self, value: &str) -> Result<(), String> {
        if !value.contains([',', '"', '\r', '\n']) {
            return self.push(value);
        }
        self.push("\"")?;
        // Avoid allocating an escaped copy of a potentially large symbol/path.
        let mut begin = 0;
        for (offset, character) in value.char_indices() {
            if character == '"' {
                self.push(&value[begin..offset])?;
                self.push("\"\"")?;
                begin = offset + 1;
            }
        }
        self.push(&value[begin..])?;
        self.push("\"")
    }
    fn row(&mut self, fields: &[&str]) -> Result<(), String> {
        for (index, field) in fields.iter().enumerate() {
            if index != 0 {
                self.push(",")?;
            }
            self.field(field)?;
        }
        self.push("\n")
    }
}

/// Exports the complete parsed stream as an AOSP Gecko v24 profile. The gzip
/// writer deliberately uses stored DEFLATE blocks: it is portable, bounded,
/// and avoids adding a compression dependency to the native engine.
pub(crate) fn firefox_profile(stream: &SimpleperfStream) -> Result<Vec<u8>, String> {
    let mut files = BTreeMap::<u32, &FileRecord>::new();
    let mut threads = BTreeMap::<i32, ThreadMeta>::new();
    let mut samples = BTreeMap::<i32, Vec<&Sample>>::new();
    for record in &stream.records {
        match record {
            Record::File(file) => {
                files.insert(file.id, file);
            }
            Record::Thread(thread) => {
                if let Ok(id) = i32::try_from(thread.thread_id) {
                    threads.insert(
                        id,
                        ThreadMeta {
                            pid: thread.process_id,
                            name: thread.name.clone(),
                        },
                    );
                }
            }
            Record::Sample(sample) => {
                threads
                    .entry(sample.thread_id)
                    .or_insert_with(|| ThreadMeta {
                        pid: sample.thread_id.max(0) as u32,
                        name: format!("thread#{}", sample.thread_id),
                    });
                samples.entry(sample.thread_id).or_default().push(sample);
            }
            _ => {}
        }
    }

    let mut json = BoundedJson::new(MAX_FIREFOX_BYTES);
    json.push(PROFILE_PREFIX)?;
    let mut wrote_thread = false;
    for (thread_id, meta) in threads {
        if wrote_thread {
            json.push(",")?;
        }
        wrote_thread = true;
        let mut thread_samples = samples.remove(&thread_id).unwrap_or_default();
        // Record streams need not be chronological. Preserve input order for
        // ties while publishing the ordered sample table Firefox expects.
        thread_samples.sort_by_key(|sample| sample.time);
        write_thread(&mut json, thread_id, &meta, &thread_samples, &files)?;
    }
    json.push(PROFILE_SUFFIX)?;
    gzip_stored(json.as_bytes())
}

#[derive(Clone)]
struct ThreadMeta {
    pid: u32,
    name: String,
}

fn write_thread(
    json: &mut BoundedJson,
    thread_id: i32,
    meta: &ThreadMeta,
    samples: &[&Sample],
    files: &BTreeMap<u32, &FileRecord>,
) -> Result<(), String> {
    let mut builder = ThreadBuilder::default();
    for sample in samples {
        builder.add(sample, files)?;
    }
    json.push("{\"tid\":")?;
    json.push(&thread_id.to_string())?;
    json.push(",\"pid\":")?;
    json.push(&meta.pid.to_string())?;
    json.push(",\"name\":")?;
    json.string(&meta.name)?;
    json.push(MARKERS_JSON)?;
    json.push(",\"samples\":{\"schema\":{\"stack\":0,\"time\":1,\"responsiveness\":2},\"data\":[")?;
    for (index, sample) in builder.samples.iter().enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.push("[")?;
        match sample.stack_id {
            Some(id) => json.push(&id.to_string())?,
            None => json.push("null")?,
        }
        json.push(",")?;
        json.push(&millis(sample.time_nanos))?;
        json.push(",0]")?;
    }
    json.push(" ]")?;
    // Keep the schema/data shape identical to the Kotlin exporter; remove the
    // cosmetic space above before publication to avoid changing the contract.
    json.replace_last_space_before(b']')?;
    json.push("},\"frameTable\":{\"schema\":")?;
    json.push(FRAME_SCHEMA_JSON)?;
    json.push(",\"data\":[")?;
    for (index, frame) in builder.frames.iter().enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.push("[")?;
        json.push(&frame.string_id.to_string())?;
        json.push(",false,0,null,null,null,null,")?;
        json.push(&frame.category.to_string())?;
        json.push(",0]")?;
    }
    json.push(" ]")?;
    json.replace_last_space_before(b']')?;
    json.push("},\"stackTable\":{\"schema\":{\"prefix\":0,\"frame\":1,\"category\":2},\"data\":[")?;
    for (index, stack) in builder.stacks.iter().enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.push("[")?;
        match stack.prefix_id {
            Some(id) => json.push(&id.to_string())?,
            None => json.push("null")?,
        }
        json.push(",")?;
        json.push(&stack.frame_id.to_string())?;
        json.push(",0]")?;
    }
    json.push(" ]")?;
    json.replace_last_space_before(b']')?;
    json.push("},\"stringTable\":[")?;
    for (index, value) in builder.strings.iter().enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.string(value)?;
    }
    json.push("],\"registerTime\":0,\"unregisterTime\":null,\"processType\":\"default\"}")
}

#[derive(Default)]
struct ThreadBuilder {
    strings: Vec<String>,
    string_ids: HashMap<String, usize>,
    frames: Vec<GeckoFrame>,
    frame_ids: HashMap<String, usize>,
    stacks: Vec<GeckoStack>,
    stack_ids: HashMap<(Option<usize>, usize), usize>,
    samples: Vec<GeckoSample>,
}

impl ThreadBuilder {
    fn add(&mut self, sample: &Sample, files: &BTreeMap<u32, &FileRecord>) -> Result<(), String> {
        // The engine stores callchains leaf-to-root; the Gecko stackTable stores
        // each stack as (caller prefix, frame), so it must be built root-to-leaf
        // or Firefox imports the exported profile upside down.
        let mut prefix = None;
        for frame in sample.callchain.iter().rev() {
            let (symbol, resource) = labels(files, frame);
            let display = firefox_symbol(&symbol, &resource, frame.virtual_address);
            let frame_id = self.frame(&display, &symbol, &resource);
            prefix = Some(self.stack(prefix, frame_id));
        }
        self.samples.push(GeckoSample {
            stack_id: prefix,
            time_nanos: sample.time,
        });
        Ok(())
    }

    fn frame(&mut self, display: &str, symbol: &str, resource: &str) -> usize {
        if let Some(id) = self.frame_ids.get(display) {
            return *id;
        }
        let id = self.frames.len();
        let string_id = self.string(display);
        self.frames.push(GeckoFrame {
            string_id,
            category: firefox_category(symbol, resource),
        });
        self.frame_ids.insert(display.to_owned(), id);
        id
    }

    fn string(&mut self, value: &str) -> usize {
        if let Some(id) = self.string_ids.get(value) {
            return *id;
        }
        let id = self.strings.len();
        self.strings.push(value.to_owned());
        self.string_ids.insert(value.to_owned(), id);
        id
    }

    fn stack(&mut self, prefix_id: Option<usize>, frame_id: usize) -> usize {
        if let Some(id) = self.stack_ids.get(&(prefix_id, frame_id)) {
            return *id;
        }
        let id = self.stacks.len();
        self.stacks.push(GeckoStack {
            prefix_id,
            frame_id,
        });
        self.stack_ids.insert((prefix_id, frame_id), id);
        id
    }
}

struct GeckoFrame {
    string_id: usize,
    category: u8,
}
struct GeckoStack {
    prefix_id: Option<usize>,
    frame_id: usize,
}
struct GeckoSample {
    stack_id: Option<usize>,
    time_nanos: u64,
}

fn labels(files: &BTreeMap<u32, &FileRecord>, frame: &Frame) -> (String, String) {
    let file = files.get(&frame.file_id);
    let symbol = file
        .and_then(|file| {
            usize::try_from(frame.symbol_id)
                .ok()
                .and_then(|id| file.symbols.get(id))
        })
        .cloned()
        .unwrap_or_else(|| "<unknown-symbol>".into());
    let resource = file
        .map(|file| file.path.clone())
        .unwrap_or_else(|| format!("<unknown-file:{}>", frame.file_id));
    (symbol, resource)
}

fn firefox_symbol(symbol: &str, resource: &str, address: u64) -> String {
    if symbol == "<unknown-symbol>" || symbol.is_empty() {
        let file = resource.rsplit(['/', '\\']).next().unwrap_or(resource);
        format!("{file}+0x{address:x} (in {resource})")
    } else {
        format!("{symbol} (in {resource})")
    }
}

pub(crate) fn firefox_category(symbol: &str, resource: &str) -> u8 {
    if resource.contains("kallsyms") || resource.ends_with(".ko") {
        if symbol == "__schedule" || symbol.starts_with("__schedule ") {
            5
        } else {
            1
        }
    } else if resource.ends_with(".so") {
        2
    } else if resource.ends_with(".vdex") {
        3
    } else if resource.ends_with(".oat") {
        4
    } else if resource == "[JIT app cache]" {
        7
    } else {
        0
    }
}

fn millis(nanos: u64) -> String {
    let value = nanos as f64 / 1_000_000.0;
    if value.fract() == 0.0 {
        format!("{value:.1}")
    } else {
        value.to_string()
    }
}

/// Shared bounded append target. JSON string escaping is defined once so every
/// export escapes identically and each append enforces its own byte budget.
trait Bounded {
    fn push(&mut self, value: &str) -> Result<(), String>;

    fn string(&mut self, value: &str) -> Result<(), String> {
        self.push("\"")?;
        let mut start = 0;
        for (index, character) in value.char_indices() {
            let escape = match character {
                '"' => Some("\\\""),
                '\\' => Some("\\\\"),
                '\n' => Some("\\n"),
                '\r' => Some("\\r"),
                '\t' => Some("\\t"),
                '\u{08}' => Some("\\b"),
                '\u{0c}' => Some("\\f"),
                _ if character.is_control() => {
                    self.push(&value[start..index])?;
                    self.push(&format!("\\u{:04x}", character as u32))?;
                    start = index + character.len_utf8();
                    None
                }
                _ => None,
            };
            if let Some(escape) = escape {
                self.push(&value[start..index])?;
                self.push(escape)?;
                start = index + character.len_utf8();
            }
        }
        self.push(&value[start..])?;
        self.push("\"")
    }
}

struct BoundedJson {
    bytes: Vec<u8>,
    limit: usize,
}

impl BoundedJson {
    fn new(limit: usize) -> Self {
        Self {
            bytes: Vec::new(),
            limit,
        }
    }

    fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }

    fn replace_last_space_before(&mut self, delimiter: u8) -> Result<(), String> {
        if self.bytes.last().copied() == Some(delimiter) {
            return Ok(());
        }
        let Some(index) = self.bytes.iter().rposition(|byte| *byte == delimiter) else {
            return Err("Firefox export JSON delimiter is malformed".into());
        };
        if index == 0 || self.bytes[index - 1] != b' ' {
            return Err("Firefox export JSON delimiter is malformed".into());
        }
        self.bytes.remove(index - 1);
        Ok(())
    }
}

impl Bounded for BoundedJson {
    fn push(&mut self, value: &str) -> Result<(), String> {
        if value.len() > self.limit.saturating_sub(self.bytes.len()) {
            return Err(format!(
                "Firefox export exceeds {MAX_FIREFOX_BYTES} byte limit"
            ));
        }
        self.bytes.extend_from_slice(value.as_bytes());
        Ok(())
    }
}

/// String-backed bounded output for the text report formats.
struct BoundedText {
    text: String,
    limit: usize,
    label: &'static str,
}

impl BoundedText {
    fn new(limit: usize, label: &'static str) -> Self {
        Self {
            text: String::new(),
            limit,
            label,
        }
    }

    fn into_string(self) -> String {
        self.text
    }
}

impl Bounded for BoundedText {
    fn push(&mut self, value: &str) -> Result<(), String> {
        if value.len() > self.limit.saturating_sub(self.text.len()) {
            return Err(format!(
                "{} export exceeds {} byte output limit",
                self.label, self.limit
            ));
        }
        self.text.push_str(value);
        Ok(())
    }
}

fn gzip_stored(input: &[u8]) -> Result<Vec<u8>, String> {
    let output = gzip_stored_bytes(input)?;
    if output.len() > MAX_FIREFOX_BYTES {
        return Err(format!(
            "Firefox gzip export exceeds {MAX_FIREFOX_BYTES} byte limit"
        ));
    }
    Ok(output)
}

fn gzip_stored_bounded(input: &[u8], limit: usize, label: &str) -> Result<Vec<u8>, String> {
    let output = gzip_stored_bytes(input)?;
    if output.len() > limit {
        return Err(format!("{label} export exceeds {limit} byte limit"));
    }
    Ok(output)
}

/// Portable stored-block gzip stream. Callers bound `input` and the compressed
/// size independently; the writer only owns container framing and trailer.
fn gzip_stored_bytes(input: &[u8]) -> Result<Vec<u8>, String> {
    let mut output = Vec::with_capacity(input.len() + input.len() / 65_535 * 5 + 32);
    output.extend_from_slice(&[0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 3]);
    if input.is_empty() {
        output.extend_from_slice(&[1, 0, 0, 0xff, 0xff]);
    } else {
        for (index, chunk) in input.chunks(65_535).enumerate() {
            let final_block = index == input.chunks(65_535).len() - 1;
            output.push(u8::from(final_block));
            let length = u16::try_from(chunk.len()).map_err(|_| "gzip block is too large")?;
            output.extend_from_slice(&length.to_le_bytes());
            output.extend_from_slice(&(!length).to_le_bytes());
            output.extend_from_slice(chunk);
        }
    }
    output.extend_from_slice(&crc32(input).to_le_bytes());
    output.extend_from_slice(&(input.len() as u32).to_le_bytes());
    Ok(output)
}

fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = u32::MAX;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ 0xedb8_8320
            } else {
                crc >> 1
            };
        }
    }
    !crc
}

/// Hand-written pprof `Profile` protobuf: one sample per self-weighting
/// preorder row. Function and location ids are assigned in deterministic
/// first-encounter order; no mappings, line numbers or addresses are invented.
/// Pprof orders `Sample.location_id` leaf-first, so each chain is emitted as
/// the reverse of the root-to-leaf preorder ancestry. The stream is gzip
/// compressed with stored blocks, like the Firefox export.
pub(crate) fn pprof(rows: &[QueryRow]) -> Result<Vec<u8>, String> {
    let by_id: HashMap<&str, &QueryRow> = rows.iter().map(|row| (row.id.as_str(), row)).collect();
    let mut functions: Vec<(&str, &str)> = Vec::new();
    let mut function_ids: HashMap<(&str, &str), u64> = HashMap::new();
    let mut locations: HashMap<&str, u64> = HashMap::new();
    let mut profile = BoundedProto::new(MAX_PPROF_BYTES);
    // Profile.sample_type: type "samples" (string 1), unit "count" (string 2).
    let mut sample_type = Vec::new();
    put_field_varint(&mut sample_type, 1, 1);
    put_field_varint(&mut sample_type, 2, 2);
    profile.field_bytes(1, &sample_type)?;
    for row in rows {
        if row.self_weight == 0 {
            continue;
        }
        let mut ancestry: Vec<&QueryRow> = Vec::new();
        let mut current = Some(row);
        while let Some(node) = current {
            ancestry.push(node);
            if ancestry.len() > MAX_CALLCHAIN_FRAMES {
                return Err("pprof call chain exceeds 512 frames".into());
            }
            current = node
                .parent_id
                .as_deref()
                .and_then(|id| by_id.get(id).copied());
        }
        // Deterministic identity: first-encounter order, root to leaf.
        for node in ancestry.iter().rev() {
            let key = (node.name.as_str(), node.resource.as_str());
            let next = functions.len() as u64 + 1;
            let id = *function_ids.entry(key).or_insert(next);
            if id == next {
                functions.push(key);
            }
            locations.insert(node.id.as_str(), id);
        }
        // Location ids are leaf-first; ancestry is currently leaf-first too.
        let mut packed_locations = Vec::new();
        for node in &ancestry {
            let id = locations
                .get(node.id.as_str())
                .ok_or("pprof location id disappeared")?;
            put_varint(&mut packed_locations, *id);
        }
        let mut sample = Vec::new();
        put_field_bytes(&mut sample, 1, &packed_locations);
        let mut values = Vec::new();
        put_varint(&mut values, row.self_weight);
        put_field_bytes(&mut sample, 2, &values);
        profile.field_bytes(2, &sample)?;
    }
    let mut strings = vec![String::new(), "samples".to_owned(), "count".to_owned()];
    let mut string_ids: HashMap<String, usize> = strings
        .iter()
        .cloned()
        .enumerate()
        .map(|(index, value)| (value, index))
        .collect();
    for (index, _) in functions.iter().enumerate() {
        let id = index as u64 + 1;
        let mut location = Vec::new();
        put_field_varint(&mut location, 1, id);
        let mut line = Vec::new();
        put_field_varint(&mut line, 1, id);
        put_field_bytes(&mut location, 4, &line);
        profile.field_bytes(4, &location)?;
    }
    for (index, (name, resource)) in functions.iter().enumerate() {
        let id = index as u64 + 1;
        let mut function = Vec::new();
        put_field_varint(&mut function, 1, id);
        let name_id = pprof_string(&mut strings, &mut string_ids, name);
        put_field_varint(&mut function, 2, name_id as u64);
        if !resource.is_empty() {
            let file_id = pprof_string(&mut strings, &mut string_ids, resource);
            put_field_varint(&mut function, 4, file_id as u64);
        }
        profile.field_bytes(5, &function)?;
    }
    for value in &strings {
        profile.field_bytes(6, value.as_bytes())?;
    }
    gzip_stored_bounded(&profile.bytes, MAX_PPROF_BYTES, "pprof")
}

fn pprof_string(strings: &mut Vec<String>, ids: &mut HashMap<String, usize>, value: &str) -> usize {
    if let Some(id) = ids.get(value) {
        return *id;
    }
    let id = strings.len();
    strings.push(value.to_owned());
    ids.insert(value.to_owned(), id);
    id
}

/// Byte-bounded protobuf writer. Nested messages are encoded into small local
/// buffers and appended through the same budget.
struct BoundedProto {
    bytes: Vec<u8>,
    limit: usize,
}

impl BoundedProto {
    fn new(limit: usize) -> Self {
        Self {
            bytes: Vec::new(),
            limit,
        }
    }

    fn raw(&mut self, value: &[u8]) -> Result<(), String> {
        if value.len() > self.limit.saturating_sub(self.bytes.len()) {
            return Err(format!("pprof export exceeds {MAX_PPROF_BYTES} byte limit"));
        }
        self.bytes.extend_from_slice(value);
        Ok(())
    }

    fn varint(&mut self, mut value: u64) -> Result<(), String> {
        let mut encoded = [0u8; 10];
        let mut length = 0;
        loop {
            let byte = (value & 0x7f) as u8;
            value >>= 7;
            if value == 0 {
                encoded[length] = byte;
                length += 1;
                break;
            }
            encoded[length] = byte | 0x80;
            length += 1;
        }
        self.raw(&encoded[..length])
    }

    fn field_bytes(&mut self, field: u32, value: &[u8]) -> Result<(), String> {
        self.varint((u64::from(field) << 3) | 2)?;
        self.varint(value.len() as u64)?;
        self.raw(value)
    }
}

fn put_varint(output: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        if value == 0 {
            output.push(byte);
            return;
        }
        output.push(byte | 0x80);
    }
}

fn put_field_varint(output: &mut Vec<u8>, field: u32, value: u64) {
    put_varint(output, (u64::from(field) << 3) | 0);
    put_varint(output, value);
}

fn put_field_bytes(output: &mut Vec<u8>, field: u32, value: &[u8]) {
    put_varint(output, (u64::from(field) << 3) | 2);
    put_varint(output, value.len() as u64);
    output.extend_from_slice(value);
}

/// Returns a bounded, deterministic store-only zip package: manifest, raw
/// profile bytes and the report JSON exported by the same query.
pub(crate) fn session_package(
    summary: &ProfileSummary,
    query: &Query,
    top: &[TopFunction],
    tree: &[QueryRow],
    profile: &[u8],
) -> Result<Vec<u8>, String> {
    let report = report_json(summary, query, top, tree)?;
    let manifest = session_manifest(summary, query, profile.len())?;
    store_zip(&[
        ("manifest.json", manifest.as_bytes()),
        ("profile.simpleperf.pb", profile),
        ("report.json", report.as_bytes()),
    ])
}

fn session_manifest(
    summary: &ProfileSummary,
    query: &Query,
    profile_bytes: usize,
) -> Result<String, String> {
    let mut json = BoundedText::new(MAX_SESSION_PACKAGE_BYTES, "CPU session manifest");
    json.push(
        "{\"kind\":\"aps-cpu-session-v1\",\"createdBy\":\"aps-rust-desktop\",\"sampleCount\":",
    )?;
    json.push(&summary.sample_count.to_string())?;
    json.push(",\"threadCount\":")?;
    json.push(&summary.thread_count.to_string())?;
    json.push(",\"lostSampleCount\":")?;
    json.push(&summary.lost_sample_count.to_string())?;
    json.push(",\"reportedSampleCount\":")?;
    json.push(&summary.reported_sample_count.to_string())?;
    json.push(",\"query\":{\"direction\":")?;
    json.string(query.direction.as_str())?;
    json.push(",\"weightType\":")?;
    json.string(query.weight_type.as_str())?;
    json.push(",\"threadIds\":[")?;
    for (index, thread) in query.thread_ids.iter().enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.push(&thread.to_string())?;
    }
    json.push("],\"eventTypes\":[")?;
    for (index, event) in query.event_types.iter().enumerate() {
        if index != 0 {
            json.push(",")?;
        }
        json.string(event)?;
    }
    json.push("],\"startNanos\":")?;
    optional_number(&mut json, query.start)?;
    json.push(",\"endNanos\":")?;
    optional_number(&mut json, query.end)?;
    json.push("},\"profileFileName\":\"profile.simpleperf.pb\",\"profileBytes\":")?;
    json.push(&profile_bytes.to_string())?;
    json.push("}")?;
    Ok(json.into_string())
}

/// Re-reads the immutable profile source for packaging. The session keeps only
/// decoded records, so package export follows the same bounded read contract as
/// `CpuSession::open_with_limit`: regular file, at most `limit` bytes.
pub(crate) fn read_profile_bytes(path: &Path, limit: usize) -> Result<Vec<u8>, String> {
    let mut file = File::open(path).map_err(|error| format!("open CPU profile: {error}"))?;
    let metadata = file
        .metadata()
        .map_err(|error| format!("stat CPU profile: {error}"))?;
    if !metadata.is_file() {
        return Err("CPU profile must be a regular file".into());
    }
    if metadata.len() > limit as u64 {
        return Err(format!("CPU profile exceeds input limit {limit} bytes"));
    }
    let mut bytes = Vec::with_capacity(metadata.len().min(1024 * 1024) as usize);
    (&mut file)
        .take((limit as u64).saturating_add(1))
        .read_to_end(&mut bytes)
        .map_err(|error| format!("read CPU profile: {error}"))?;
    if bytes.len() > limit {
        return Err(format!("CPU profile exceeds input limit {limit} bytes"));
    }
    Ok(bytes)
}

/// Minimal store-only zip writer: deterministic entry order, no compression,
/// no timestamps beyond the fixed 1980-01-01 DOS epoch, and one bounded output
/// allocation checked before any bytes are written.
fn store_zip(entries: &[(&str, &[u8])]) -> Result<Vec<u8>, String> {
    let mut total = 22usize;
    for (name, data) in entries {
        total = total
            .checked_add(30)
            .and_then(|total| total.checked_add(name.len()))
            .and_then(|total| total.checked_add(data.len()))
            .and_then(|total| total.checked_add(46))
            .and_then(|total| total.checked_add(name.len()))
            .ok_or("CPU session package size overflows usize")?;
    }
    if total > MAX_SESSION_PACKAGE_BYTES {
        return Err(format!(
            "CPU session package exceeds {MAX_SESSION_PACKAGE_BYTES} byte limit"
        ));
    }
    let entry_count =
        u16::try_from(entries.len()).map_err(|_| "CPU session package has too many entries")?;
    let mut output = Vec::with_capacity(total);
    let mut offsets = Vec::with_capacity(entries.len());
    for (name, data) in entries {
        let offset =
            u32::try_from(output.len()).map_err(|_| "CPU session package offset exceeds u32")?;
        offsets.push(offset);
        let name_length =
            u16::try_from(name.len()).map_err(|_| "CPU session package entry name is too long")?;
        let size =
            u32::try_from(data.len()).map_err(|_| "CPU session package entry exceeds u32")?;
        write_zip_local_header(&mut output, name, name_length, size, crc32(data));
        output.extend_from_slice(data);
    }
    let central_start =
        u32::try_from(output.len()).map_err(|_| "CPU session package offset exceeds u32")?;
    for ((name, data), offset) in entries.iter().zip(&offsets) {
        let name_length =
            u16::try_from(name.len()).map_err(|_| "CPU session package entry name is too long")?;
        let size =
            u32::try_from(data.len()).map_err(|_| "CPU session package entry exceeds u32")?;
        write_zip_central_header(&mut output, name, name_length, size, crc32(data), *offset);
    }
    let central_size = u32::try_from(output.len())
        .ok()
        .and_then(|end| end.checked_sub(central_start))
        .ok_or("CPU session package directory exceeds u32")?;
    push_u32(&mut output, 0x0605_4b50);
    push_u16(&mut output, 0);
    push_u16(&mut output, 0);
    push_u16(&mut output, entry_count);
    push_u16(&mut output, entry_count);
    push_u32(&mut output, central_size);
    push_u32(&mut output, central_start);
    push_u16(&mut output, 0);
    if output.len() != total || output.len() > MAX_SESSION_PACKAGE_BYTES {
        return Err("CPU session package output is inconsistent".into());
    }
    Ok(output)
}

fn write_zip_local_header(output: &mut Vec<u8>, name: &str, name_length: u16, size: u32, crc: u32) {
    push_u32(output, 0x0403_4b50);
    push_u16(output, 20);
    push_u16(output, 0);
    push_u16(output, 0);
    push_u16(output, 0);
    push_u16(output, 0x0021);
    push_u32(output, crc);
    push_u32(output, size);
    push_u32(output, size);
    push_u16(output, name_length);
    push_u16(output, 0);
    output.extend_from_slice(name.as_bytes());
}

fn write_zip_central_header(
    output: &mut Vec<u8>,
    name: &str,
    name_length: u16,
    size: u32,
    crc: u32,
    offset: u32,
) {
    push_u32(output, 0x0201_4b50);
    push_u16(output, 0x0014);
    push_u16(output, 20);
    push_u16(output, 0);
    push_u16(output, 0);
    push_u16(output, 0);
    push_u16(output, 0x0021);
    push_u32(output, crc);
    push_u32(output, size);
    push_u32(output, size);
    push_u16(output, name_length);
    push_u16(output, 0);
    push_u16(output, 0);
    push_u16(output, 0);
    push_u16(output, 0);
    push_u32(output, 0);
    push_u32(output, offset);
    output.extend_from_slice(name.as_bytes());
}

fn push_u16(output: &mut Vec<u8>, value: u16) {
    output.extend_from_slice(&value.to_le_bytes());
}

fn push_u32(output: &mut Vec<u8>, value: u32) {
    output.extend_from_slice(&value.to_le_bytes());
}

const PROFILE_PREFIX: &str = "{\"meta\":{\"interval\":1,\"processType\":0,\"product\":\"Android Performance Studio\",\"device\":null,\"platform\":null,\"stackwalk\":1,\"debug\":0,\"gcpoison\":0,\"asyncstack\":1,\"startTime\":0,\"shutdownTime\":null,\"version\":24,\"presymbolicated\":true,\"categories\":[{\"name\":\"User\",\"color\":\"yellow\",\"subcategories\":[\"Other\"]},{\"name\":\"Kernel\",\"color\":\"orange\",\"subcategories\":[\"Other\"]},{\"name\":\"Native\",\"color\":\"yellow\",\"subcategories\":[\"Other\"]},{\"name\":\"DEX\",\"color\":\"green\",\"subcategories\":[\"Other\"]},{\"name\":\"OAT\",\"color\":\"green\",\"subcategories\":[\"Other\"]},{\"name\":\"Off-CPU\",\"color\":\"blue\",\"subcategories\":[\"Other\"]},{\"name\":\"Other\",\"color\":\"grey\",\"subcategories\":[\"Other\"]},{\"name\":\"JIT\",\"color\":\"green\",\"subcategories\":[\"Other\"]}],\"markerSchema\":[],\"abi\":null,\"oscpu\":null,\"appBuildID\":null},\"libs\":[],\"threads\":[";

const PROFILE_SUFFIX: &str = "],\"processes\":[],\"pausedRanges\":[]}";
const MARKERS_JSON: &str = ",\"markers\":{\"schema\":{\"name\":0,\"startTime\":1,\"endTime\":2,\"phase\":3,\"category\":4,\"data\":5},\"data\":[]}";
const FRAME_SCHEMA_JSON: &str = "{\"location\":0,\"relevantForJS\":1,\"innerWindowID\":2,\"implementation\":3,\"optimizations\":4,\"line\":5,\"column\":6,\"category\":7,\"subcategory\":8}";
