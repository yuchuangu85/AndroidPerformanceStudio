use super::*;

const MAGIC: u32 = 0x574f4c53;
const HEADER_SIZE: usize = 32;

pub(crate) fn parse(bytes: &[u8], limits: ImportLimits) -> Result<ParsedTrace, ImportError> {
    let mut reader = Reader::new(bytes, 0);
    if reader.u32()? != MAGIC {
        return Err(reader.error("Not a supported ART method trace (bad SLOW magic)"));
    }
    let version = reader.u16()?;
    if !(2..=5).contains(&version) {
        return Err(ImportError::new(
            ErrorKind::UnsupportedVersion,
            Some(4),
            format!("ART trace version {version} is unsupported; expected 2, 3, 4 or 5"),
        ));
    }
    let (start_time_nanos, data_offset, record_size) = if version >= 4 {
        let time = reader.u64()?;
        reader.take(HEADER_SIZE - 14)?;
        (time, 0, 0)
    } else {
        let offset = usize::from(reader.u16()?);
        let micros = reader.u64()?;
        let time = micros.checked_mul(1000).ok_or_else(|| {
            ImportError::new(
                ErrorKind::ArithmeticOverflow,
                Some(8),
                "Classic header microseconds overflow nanoseconds",
            )
        })?;
        let size = usize::from(reader.u16()?);
        reader.take(HEADER_SIZE - 18)?;
        (time, offset, size)
    };
    let clock_source = match version {
        2 => ClockSource::ThreadCpu,
        4 => ClockSource::UnspecifiedSingle,
        _ => ClockSource::WallAndThreadCpu,
    };
    let mut trace = ParsedTrace {
        header: TraceHeader {
            version,
            start_time_nanos,
            clock_source,
        },
        methods: BTreeMap::new(),
        threads: BTreeMap::new(),
        events: Vec::new(),
        diagnostics: Diagnostics::default(),
        raw_record_count: 0,
    };
    let mut table_bytes = 0;
    if version >= 4 {
        streaming(&mut reader, &mut trace, limits, &mut table_bytes)?;
    } else {
        classic(
            &mut reader,
            &mut trace,
            data_offset,
            record_size,
            limits,
            &mut table_bytes,
        )?;
    }
    Ok(trace)
}

fn streaming(
    reader: &mut Reader<'_>,
    trace: &mut ParsedTrace,
    limits: ImportLimits,
    table_bytes: &mut usize,
) -> Result<(), ImportError> {
    let dual = trace.header.version == 5;
    while reader.remaining() > 0 {
        let packet_offset = reader.offset();
        match reader.u8()? {
            0 => {
                let id = reader.u32()?;
                let length = usize::from(reader.u16()?);
                budget_text(length, table_bytes, limits, reader.offset())?;
                let name = reader.text(length)?.to_owned();
                insert_thread(trace, id, name, limits, packet_offset)?;
            }
            1 => {
                let id = reader.u64()?;
                let length = usize::from(reader.u16()?);
                budget_text(length, table_bytes, limits, reader.offset())?;
                let info = reader.text(length)?.trim_end_matches(['\n', '\r']);
                let mut fields = info.split('\t');
                let method = Method {
                    id,
                    class_name: fields.next().unwrap_or("").to_owned(),
                    method_name: fields.next().unwrap_or("").to_owned(),
                    signature: fields.next().unwrap_or("").to_owned(),
                    source_file: fields.next().unwrap_or("").to_owned(),
                    declared: true,
                };
                insert_method(trace, method, limits, packet_offset)?;
            }
            2 => {
                let thread_id = reader.u32()?;
                let records = reader.u24()? as usize;
                if records > limits.max_events.saturating_sub(trace.raw_record_count) {
                    return Err(ImportError::new(
                        ErrorKind::Limit,
                        Some(packet_offset),
                        "Entry block exceeds remaining event budget",
                    ));
                }
                let length = reader.u32()? as usize;
                let base = reader.offset();
                let mut block = Reader::new(reader.take(length)?, base);
                let mut method_word: u64 = 0;
                let mut primary: u64 = 0;
                let mut cpu: u64 = 0;
                for _ in 0..records {
                    let offset = block.offset();
                    let delta = block.sleb()?;
                    let next = i128::from(method_word) + i128::from(delta);
                    method_word = u64::try_from(next).map_err(|_| {
                        ImportError::new(
                            ErrorKind::ArithmeticOverflow,
                            Some(offset),
                            "Method-word delta overflow",
                        )
                    })?;
                    primary = primary.checked_add(block.uleb()?).ok_or_else(|| {
                        ImportError::new(
                            ErrorKind::ArithmeticOverflow,
                            Some(offset),
                            "Primary timestamp delta overflow",
                        )
                    })?;
                    if dual {
                        cpu = cpu.checked_add(block.uleb()?).ok_or_else(|| {
                            ImportError::new(
                                ErrorKind::ArithmeticOverflow,
                                Some(offset),
                                "CPU timestamp delta overflow",
                            )
                        })?;
                    }
                    add_event(
                        trace,
                        thread_id,
                        method_word,
                        primary,
                        dual.then_some(cpu),
                        offset,
                        limits,
                    )?;
                }
                if block.remaining() != 0 {
                    diagnostic(
                        trace,
                        DiagnosticKind::TrailingBytes,
                        Some(thread_id),
                        Some(block.offset()),
                        format!(
                            "Entry block contains {} unconsumed bytes",
                            block.remaining()
                        ),
                        limits,
                    );
                }
            }
            3 => {
                let length = usize::from(reader.u16()?);
                budget_text(length, table_bytes, limits, reader.offset())?;
                let summary = reader.text(length)?;
                detect_capture_overflow(trace, summary, packet_offset, limits);
            }
            packet => {
                diagnostic(
                    trace,
                    DiagnosticKind::UnknownPacket,
                    None,
                    Some(packet_offset),
                    format!(
                        "Unknown streaming packet {packet}; remaining bytes were not interpreted"
                    ),
                    limits,
                );
                break;
            }
        }
    }
    Ok(())
}

fn classic(
    reader: &mut Reader<'_>,
    trace: &mut ParsedTrace,
    data_offset: usize,
    record_size: usize,
    limits: ImportLimits,
    table_bytes: &mut usize,
) -> Result<(), ImportError> {
    let dual = trace.header.version == 3;
    let expected = if dual { 14 } else { 10 };
    if record_size != 0 && record_size != expected {
        return Err(ImportError::new(
            ErrorKind::Format,
            Some(16),
            format!("Classic record size {record_size} does not match supported size {expected}"),
        ));
    }
    let rest = &reader.bytes[reader.position..];
    // Only look for a table terminator when this actually begins a text table;
    // searching arbitrary binary records could accidentally consume event data.
    if [
        b"*threads\n".as_slice(),
        b"*methods\n".as_slice(),
        b"*version\n".as_slice(),
    ]
    .iter()
    .any(|prefix| rest.starts_with(prefix))
    {
        let marker = b"*end\n";
        let search = &rest[..rest
            .len()
            .min(limits.max_table_bytes.saturating_add(marker.len()))];
        if let Some(index) = search
            .windows(marker.len())
            .position(|window| window == marker)
        {
            let length = index + marker.len();
            if length > limits.max_table_bytes {
                return Err(ImportError::new(
                    ErrorKind::Limit,
                    Some(reader.offset()),
                    "Classic tables exceed text budget",
                ));
            }
            *table_bytes = length;
            let offset = reader.offset();
            classic_tables(trace, reader.text(length)?, offset, limits)?;
        } else {
            return Err(ImportError::new(
                ErrorKind::Format,
                Some(reader.offset()),
                "Classic text table has no bounded *end terminator",
            ));
        }
    } else {
        let offset = data_offset.max(HEADER_SIZE);
        if offset > reader.bytes.len() {
            return Err(ImportError::new(
                ErrorKind::Format,
                Some(6),
                "Classic data offset is beyond end of input",
            ));
        }
        reader.position = offset;
    }
    let count = reader.remaining() / expected;
    if count > limits.max_events {
        return Err(ImportError::new(
            ErrorKind::Limit,
            Some(reader.offset()),
            "Classic records exceed event budget",
        ));
    }
    for _ in 0..count {
        let offset = reader.offset();
        let thread_id = u32::from(reader.u16()?);
        let word = u64::from(reader.u32()?);
        let cpu = u64::from(reader.u32()?) * 1000;
        let wall = if dual {
            u64::from(reader.u32()?) * 1000
        } else {
            cpu
        };
        add_event(
            trace,
            thread_id,
            word,
            wall,
            dual.then_some(cpu),
            offset,
            limits,
        )?;
    }
    if reader.remaining() != 0 {
        diagnostic(
            trace,
            DiagnosticKind::TrailingBytes,
            None,
            Some(reader.offset()),
            format!(
                "Classic trace has {} trailing bytes, not a complete record",
                reader.remaining()
            ),
            limits,
        );
    }
    Ok(())
}

fn classic_tables(
    trace: &mut ParsedTrace,
    text: &str,
    offset: usize,
    limits: ImportLimits,
) -> Result<(), ImportError> {
    let mut section = "";
    let mut line_offset = offset;
    detect_capture_overflow(trace, text, offset, limits);
    for raw in text.split_inclusive('\n') {
        let line = raw.trim();
        if line.len() > limits.max_string_bytes {
            return Err(ImportError::new(
                ErrorKind::Limit,
                Some(line_offset),
                "Classic table line exceeds string limit",
            ));
        }
        if line.starts_with('*') {
            section = match line {
                "*threads" => "threads",
                "*methods" => "methods",
                _ => "",
            };
        } else if !line.is_empty() && (section == "threads" || section == "methods") {
            let mut fields = line.split('\t').filter(|field| !field.is_empty());
            let id_text = fields.next().unwrap_or("");
            let id = parse_classic_id(id_text);
            match (section, id) {
                ("threads", Some(id)) if id <= u64::from(u32::MAX) => {
                    let name = fields.collect::<Vec<_>>().join(" ");
                    insert_thread(trace, id as u32, name, limits, line_offset)?;
                }
                ("methods", Some(word)) => {
                    let method = Method {
                        id: word >> 2,
                        class_name: fields.next().unwrap_or("").to_owned(),
                        method_name: fields.next().unwrap_or("").to_owned(),
                        signature: fields.next().unwrap_or("").to_owned(),
                        source_file: fields.next().unwrap_or("").to_owned(),
                        declared: true,
                    };
                    insert_method(trace, method, limits, line_offset)?;
                }
                _ => diagnostic(
                    trace,
                    DiagnosticKind::MalformedTable,
                    None,
                    Some(line_offset),
                    "Skipped invalid classic table ID".into(),
                    limits,
                ),
            }
        }
        line_offset += raw.len();
    }
    Ok(())
}

fn parse_classic_id(value: &str) -> Option<u64> {
    // Matches the existing importer: bare tokens are hexadecimal first.
    let hex = value
        .strip_prefix("0x")
        .or_else(|| value.strip_prefix("0X"))
        .unwrap_or(value);
    u64::from_str_radix(hex, 16)
        .ok()
        .or_else(|| value.parse().ok())
}
fn budget_text(
    length: usize,
    used: &mut usize,
    limits: ImportLimits,
    offset: usize,
) -> Result<(), ImportError> {
    if length > limits.max_string_bytes || length > limits.max_table_bytes.saturating_sub(*used) {
        return Err(ImportError::new(
            ErrorKind::Limit,
            Some(offset),
            "Method trace text exceeds string/table budget",
        ));
    }
    *used += length;
    Ok(())
}
fn insert_thread(
    trace: &mut ParsedTrace,
    id: ThreadId,
    name: String,
    limits: ImportLimits,
    offset: usize,
) -> Result<(), ImportError> {
    if let Some(previous) = trace.threads.get(&id) {
        if previous.name != name {
            diagnostic(
                trace,
                DiagnosticKind::DuplicateThread,
                Some(id),
                Some(offset),
                "Thread name was replaced by a later declaration".into(),
                limits,
            );
        }
    } else if trace.threads.len() >= limits.max_threads {
        return Err(ImportError::new(
            ErrorKind::Limit,
            Some(offset),
            "Thread catalog exceeds limit",
        ));
    }
    trace.threads.insert(
        id,
        Thread {
            id,
            name,
            declared: true,
            event_count: 0,
            first_time_nanos: None,
            last_time_nanos: None,
        },
    );
    Ok(())
}
fn insert_method(
    trace: &mut ParsedTrace,
    method: Method,
    limits: ImportLimits,
    offset: usize,
) -> Result<(), ImportError> {
    if let Some(previous) = trace.methods.get(&method.id) {
        if previous != &method {
            diagnostic(
                trace,
                DiagnosticKind::DuplicateMethod,
                None,
                Some(offset),
                format!(
                    "Method 0x{:x} was replaced by a later declaration",
                    method.id
                ),
                limits,
            );
        }
    } else if trace.methods.len() >= limits.max_methods {
        return Err(ImportError::new(
            ErrorKind::Limit,
            Some(offset),
            "Method catalog exceeds limit",
        ));
    }
    trace.methods.insert(method.id, method);
    Ok(())
}
fn add_event(
    trace: &mut ParsedTrace,
    thread_id: ThreadId,
    word: u64,
    time_nanos: u64,
    cpu_nanos: Option<u64>,
    offset: usize,
    limits: ImportLimits,
) -> Result<(), ImportError> {
    if trace.raw_record_count >= limits.max_events {
        return Err(ImportError::new(
            ErrorKind::Limit,
            Some(offset),
            "Method record count exceeds limit",
        ));
    }
    trace.raw_record_count += 1;
    let action = match word & 3 {
        0 => Action::Enter,
        1 => Action::Exit,
        2 => Action::Unroll,
        _ => {
            diagnostic(
                trace,
                DiagnosticKind::UnusedAction,
                Some(thread_id),
                Some(offset),
                "Ignored method record with reserved action 3".into(),
                limits,
            );
            return Ok(());
        }
    };
    if trace.events.len() >= limits.max_events {
        return Err(ImportError::new(
            ErrorKind::Limit,
            Some(offset),
            "Method event count exceeds limit",
        ));
    }
    trace.events.push(MethodEvent {
        thread_id,
        method_id: word >> 2,
        action,
        time_nanos,
        cpu_nanos,
        source_offset: offset,
    });
    Ok(())
}
fn detect_capture_overflow(
    trace: &mut ParsedTrace,
    text: &str,
    offset: usize,
    limits: ImportLimits,
) {
    if text.lines().any(|line| {
        let line = line.trim();
        matches!(
            line.split_once('=')
                .map(|(key, value)| (key.trim(), value.trim())),
            Some(("data-file-overflow", "true" | "1"))
        )
    }) {
        diagnostic(
            trace,
            DiagnosticKind::CaptureOverflow,
            None,
            Some(offset),
            "Producer reports data-file-overflow; calls are missing".into(),
            limits,
        );
    }
}
fn diagnostic(
    trace: &mut ParsedTrace,
    kind: DiagnosticKind,
    thread_id: Option<ThreadId>,
    offset: Option<usize>,
    message: String,
    limits: ImportLimits,
) {
    trace.diagnostics.record(
        Diagnostic {
            kind,
            thread_id,
            event_index: None,
            offset,
            message,
        },
        limits.max_diagnostics,
    );
}

struct Reader<'a> {
    bytes: &'a [u8],
    position: usize,
    base: usize,
}
impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8], base: usize) -> Self {
        Self {
            bytes,
            position: 0,
            base,
        }
    }
    fn offset(&self) -> usize {
        self.base + self.position
    }
    fn remaining(&self) -> usize {
        self.bytes.len() - self.position
    }
    fn error(&self, message: &str) -> ImportError {
        ImportError::new(ErrorKind::Format, Some(self.offset()), message)
    }
    fn take(&mut self, length: usize) -> Result<&'a [u8], ImportError> {
        if length > self.remaining() {
            return Err(self.error("Truncated method trace"));
        }
        let start = self.position;
        self.position += length;
        Ok(&self.bytes[start..self.position])
    }
    fn text(&mut self, length: usize) -> Result<&'a str, ImportError> {
        let offset = self.offset();
        std::str::from_utf8(self.take(length)?).map_err(|_| {
            ImportError::new(
                ErrorKind::Format,
                Some(offset),
                "Invalid UTF-8 in method trace catalog",
            )
        })
    }
    fn u8(&mut self) -> Result<u8, ImportError> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, ImportError> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u24(&mut self) -> Result<u32, ImportError> {
        let b = self.take(3)?;
        Ok(u32::from(b[0]) | (u32::from(b[1]) << 8) | (u32::from(b[2]) << 16))
    }
    fn u32(&mut self) -> Result<u32, ImportError> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn u64(&mut self) -> Result<u64, ImportError> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }
    fn uleb(&mut self) -> Result<u64, ImportError> {
        let offset = self.offset();
        let mut value = 0;
        for index in 0..10 {
            let byte = self.u8()?;
            let payload = byte & 0x7f;
            if index == 9 && (payload > 1 || byte & 0x80 != 0) {
                return Err(ImportError::new(
                    ErrorKind::ArithmeticOverflow,
                    Some(offset),
                    "ULEB128 exceeds u64",
                ));
            }
            value |= u64::from(payload) << (index * 7);
            if byte & 0x80 == 0 {
                return Ok(value);
            }
        }
        unreachable!()
    }
    fn sleb(&mut self) -> Result<i64, ImportError> {
        let offset = self.offset();
        let mut value: i128 = 0;
        for index in 0..10 {
            let byte = self.u8()?;
            let shift = (index + 1) * 7;
            value |= i128::from(byte & 0x7f) << (index * 7);
            if byte & 0x80 == 0 {
                if byte & 0x40 != 0 {
                    value |= -(1i128 << shift);
                }
                return i64::try_from(value).map_err(|_| {
                    ImportError::new(
                        ErrorKind::ArithmeticOverflow,
                        Some(offset),
                        "SLEB128 exceeds i64",
                    )
                });
            }
        }
        Err(ImportError::new(
            ErrorKind::ArithmeticOverflow,
            Some(offset),
            "SLEB128 exceeds ten bytes",
        ))
    }
}
