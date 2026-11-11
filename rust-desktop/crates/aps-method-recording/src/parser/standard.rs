use super::*;

// AOSP ART runtime/trace.cc at de0d0de0f4: FinishTracing, GetTraceVersion,
// GetMethodLine, DumpThreadList, ReadClocks, and LogMethodTraceEvent.
pub(super) fn parse(bytes: &[u8], limits: ImportLimits) -> Result<ParsedTrace, ImportError> {
    let text = summary(bytes, limits)?;
    let base = text.len();
    let mut reader = Reader::new(&bytes[base..], base);
    if reader.u32()? != MAGIC {
        return Err(format_error(base, "Expected SLOW header after *end"));
    }
    let version = reader.u16()?;
    if !matches!(version, 2 | 3) {
        return Err(ImportError::new(
            ErrorKind::UnsupportedVersion,
            Some(base + 4),
            "Standard ART trace requires binary version 2 or 3",
        ));
    }
    // The offset is relative to this binary header, not the text or file start.
    let data_offset = usize::from(reader.u16()?);
    if data_offset < HEADER_SIZE || data_offset > reader.bytes.len() {
        return Err(format_error(base + 6, "Invalid standard trace data offset"));
    }
    let start_time_nanos = reader.u64()?.checked_mul(1000).ok_or_else(|| {
        ImportError::new(
            ErrorKind::ArithmeticOverflow,
            Some(base + 8),
            "Standard header microseconds overflow nanoseconds",
        )
    })?;
    // ART writes the record-size field only for v3. The v2 bytes are padding.
    let record_size = if version == 3 {
        usize::from(reader.u16()?)
    } else {
        10
    };
    reader.take(data_offset - reader.position)?;
    if record_size != if version == 3 { 14 } else { 10 } {
        return Err(format_error(
            base + 16,
            "Invalid standard trace record size",
        ));
    }
    let mut trace = ParsedTrace {
        header: TraceHeader {
            version,
            start_time_nanos,
            clock_source: ClockSource::UnspecifiedSingle,
        },
        methods: BTreeMap::new(),
        threads: BTreeMap::new(),
        events: Vec::new(),
        diagnostics: Diagnostics::default(),
        raw_record_count: 0,
    };
    tables(&mut trace, text, limits)?;
    classic_records(&mut reader, &mut trace, limits)?;
    Ok(trace)
}

fn summary(bytes: &[u8], limits: ImportLimits) -> Result<&str, ImportError> {
    let mut offset = 0;
    // Bound scanning before decoding UTF-8 or allocating catalog strings. Only
    // an entire *end line terminates text; names can contain that substring.
    for raw in bytes[..bytes.len().min(limits.max_table_bytes + 1)].split_inclusive(|b| *b == b'\n')
    {
        if raw.len() > limits.max_table_bytes.saturating_sub(offset) {
            return Err(ImportError::new(
                ErrorKind::Limit,
                Some(offset),
                "Standard trace summary exceeds text budget",
            ));
        }
        let line = raw.strip_suffix(b"\n").unwrap_or(raw);
        let line = line.strip_suffix(b"\r").unwrap_or(line);
        if line.len() > limits.max_string_bytes {
            return Err(ImportError::new(
                ErrorKind::Limit,
                Some(offset),
                "Standard trace summary line exceeds string limit",
            ));
        }
        if !raw.ends_with(b"\n") {
            return Err(format_error(
                offset,
                "Unterminated standard trace summary line",
            ));
        }
        std::str::from_utf8(line)
            .map_err(|_| format_error(offset, "Invalid UTF-8 in standard trace summary"))?;
        offset += raw.len();
        if line == b"*end" {
            return Ok(std::str::from_utf8(&bytes[..offset]).unwrap());
        }
    }
    Err(format_error(
        offset,
        "Standard trace summary has no *end line",
    ))
}

fn tables(trace: &mut ParsedTrace, text: &str, limits: ImportLimits) -> Result<(), ImportError> {
    let mut lines = text.split_inclusive('\n');
    let first = lines.next().unwrap();
    let raw_version = lines
        .next()
        .ok_or_else(|| format_error(first.len(), "Missing standard trace text version"))?;
    let version = raw_version
        .trim_end_matches(['\r', '\n'])
        .parse::<u16>()
        .map_err(|_| format_error(first.len(), "Invalid standard trace text version"))?;
    if !matches!(version, 2 | 3) {
        return Err(ImportError::new(
            ErrorKind::UnsupportedVersion,
            Some(first.len()),
            "Standard ART trace requires text version 2 or 3",
        ));
    }
    if version != trace.header.version {
        return Err(format_error(
            first.len(),
            "Text and binary trace versions differ",
        ));
    }
    let mut offset = first.len() + raw_version.len();
    let mut section = "options";
    let mut clock = None;
    for raw in lines {
        let line = raw.trim_end_matches(['\r', '\n']);
        match (section, line) {
            ("options", "*threads") => section = "threads",
            ("threads", "*methods") => section = "methods",
            ("methods", "*end") => section = "end",
            _ if line.starts_with('*') => {
                return Err(format_error(offset, "Unexpected standard trace section"));
            }
            ("options", _) => {
                let (key, value) = line
                    .split_once('=')
                    .filter(|(key, value)| !key.is_empty() && !value.is_empty())
                    .ok_or_else(|| format_error(offset, "Invalid standard trace option"))?;
                if key == "clock" {
                    let source = match value {
                        "wall" => ClockSource::Wall,
                        "thread-cpu" => ClockSource::ThreadCpu,
                        "dual" => ClockSource::WallAndThreadCpu,
                        _ => return Err(format_error(offset, "Unknown standard trace clock")),
                    };
                    if clock.replace(source).is_some() {
                        return Err(format_error(offset, "Duplicate standard trace clock"));
                    }
                }
            }
            ("threads", _) => {
                let (id, name) = line
                    .split_once('\t')
                    .ok_or_else(|| format_error(offset, "Invalid standard thread row"))?;
                // Unlike binary-first fixtures, ART thread catalog IDs are decimal.
                let id = id
                    .parse::<u32>()
                    .map_err(|_| format_error(offset, "Invalid decimal standard thread ID"))?;
                insert_thread(trace, id, name.to_owned(), limits, offset)?;
            }
            ("methods", _) => method(trace, line, offset, limits)?,
            _ => {
                return Err(format_error(
                    offset,
                    "Unexpected standard trace summary content",
                ))
            }
        }
        offset += raw.len();
    }
    let clock = clock.ok_or_else(|| format_error(0, "Standard trace has no clock declaration"))?;
    if section != "end" || (version == 3) != (clock == ClockSource::WallAndThreadCpu) {
        return Err(format_error(
            0,
            "Standard trace version/clock/sections are inconsistent",
        ));
    }
    trace.header.clock_source = clock;
    detect_capture_overflow(trace, text, 0, limits);
    Ok(())
}

fn method(
    trace: &mut ParsedTrace,
    line: &str,
    offset: usize,
    limits: ImportLimits,
) -> Result<(), ImportError> {
    let mut fields = line.split('\t');
    let id = fields.next().unwrap_or("");
    let id = id
        .strip_prefix("0x")
        .or_else(|| id.strip_prefix("0X"))
        .unwrap_or(id);
    let word = u32::from_str_radix(id, 16)
        .map_err(|_| format_error(offset, "Invalid hexadecimal standard method ID"))?;
    if word & 3 != 0 {
        return Err(format_error(
            offset,
            "Standard catalog method ID contains action bits",
        ));
    }
    let mut field = || {
        fields
            .next()
            .ok_or_else(|| format_error(offset, "Incomplete standard method row"))
    };
    let class_name = field()?;
    let method_name = field()?;
    let signature = field()?;
    let source_file = field()?;
    if fields.next().is_some()
        || class_name.is_empty()
        || method_name.is_empty()
        || !signature.starts_with('(')
    {
        return Err(format_error(offset, "Invalid standard ART method row"));
    }
    insert_method(
        trace,
        Method {
            id: u64::from(word >> 2),
            class_name: class_name.to_owned(),
            method_name: method_name.to_owned(),
            signature: signature.to_owned(),
            source_file: source_file.to_owned(),
            declared: true,
        },
        limits,
        offset,
    )
}

fn format_error(offset: usize, message: &str) -> ImportError {
    ImportError::new(ErrorKind::Format, Some(offset), message)
}
