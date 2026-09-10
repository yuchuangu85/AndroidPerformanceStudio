import {
  EMPTY_NETWORK_USAGE,
  type BatteryDeviceState,
  type BatteryHistoryEvent,
  type BatteryHistoryEventKind,
  type EnergyEstimate,
  type EvidenceConfidence,
  type NetworkUsage,
  type ResourceTimer,
  type UidBatteryStats,
} from './model.js';

export interface ParsedBatteryStats {
  readonly statsPeriodId?: string;
  readonly uidStats: UidBatteryStats;
  readonly history: readonly BatteryHistoryEvent[];
  readonly warnings: readonly string[];
}

const MICROSECONDS_PER_MILLISECOND = 1_000;
const TIMER_SECTION_WIDTH = 5;
const PER_USER_UID_RANGE = 100_000;
const FIRST_APPLICATION_UID = 10_000;
const TIMER_MARKERS = new Set(['f', 'p', 'w', 'b']);
const PERIOD_TYPES = new Set(['start']);

const UID_ENERGY = /\b(?:Uid|UID)\s+((?:u(\d+)?a(\d+))|(\d+))\s*:\s*([0-9]+(?:\.[0-9]+)?)/i;
const COMPONENT_ENERGY = /([A-Za-z][A-Za-z0-9_-]*)=([0-9]+(?:\.[0-9]+)?)/g;
const HISTORY_TIME = /(?:^|,)\s*(\d+)[:;,]/;
const HISTORY_MARKER = /([+-])([A-Za-z][A-Za-z0-9_.:/-]*(?:=[^, ]+)?)/;
const HISTORY_UID = /(?:uid=|u)(\d+)/;

interface CheckinRecord {
  readonly lineNumber: number;
  readonly uid?: number;
  readonly aggregation: string;
  readonly type: string;
  readonly fields: readonly string[];
  readonly raw: string;
}

export function splitCheckinCsv(line: string): string[] {
  const values: string[] = [];
  let current = '';
  let quoted = false;
  for (let index = 0; index < line.length; index += 1) {
    const character = line[index] as string;
    if (character === '"' && quoted && line[index + 1] === '"') {
      current += '"';
      index += 1;
    } else if (character === '"') {
      quoted = !quoted;
    } else if (character === ',' && !quoted) {
      values.push(current);
      current = '';
    } else {
      current += character;
    }
  }
  values.push(current);
  return values;
}

function parseRecord(lineNumber: number, line: string, warnings: string[]): CheckinRecord | undefined {
  const trimmed = line.trim();
  if (trimmed.length === 0) return undefined;
  const columns = splitCheckinCsv(trimmed);
  if (columns.length < 4) {
    if (trimmed.includes(',')) warnings.push('Ignored malformed checkin line ' + lineNumber + '.');
    return undefined;
  }
  const uidText = columns[1] ?? '';
  const uid = /^\d+$/.test(uidText) ? Number(uidText) : undefined;
  const type = (columns[3] ?? '').trim().toLowerCase();
  if (uid === undefined && type !== 'h') return undefined;
  return {
    lineNumber,
    ...(uid !== undefined ? { uid } : {}),
    aggregation: (columns[2] ?? '').trim().toLowerCase(),
    type,
    fields: columns.slice(4),
    raw: trimmed,
  };
}

function timers(
  records: readonly CheckinRecord[],
  types: ReadonlySet<string>,
  fallbackName: string,
  warnings: string[],
): Record<string, ResourceTimer> {
  const result: Record<string, ResourceTimer> = {};
  for (const record of records) {
    if (!types.has(record.type)) continue;
    const nameIndex = record.fields.findIndex(
      (field) => field.trim().length > 0 && !/^\d+$/.test(field) && !TIMER_MARKERS.has(field),
    );
    const name = nameIndex >= 0 && (record.fields[nameIndex] ?? '').length > 0
      ? (record.fields[nameIndex] as string)
      : fallbackName + '@' + String(record.lineNumber);
    const values = record.fields
      .slice(Math.max(nameIndex + 1, 0))
      .filter((field) => /^\d+$/.test(field))
      .map((field) => Number(field));
    if (values.length === 0) {
      warnings.push(record.type + ' record at line ' + record.lineNumber + ' did not contain timer values.');
      continue;
    }
    let durationUs = 0;
    for (let index = 0; index < values.length; index += TIMER_SECTION_WIDTH) {
      durationUs += values[index] ?? 0;
    }
    let count = 0;
    for (let index = 1; index < values.length; index += TIMER_SECTION_WIDTH) {
      count += values[index] ?? 0;
    }
    if (count === 0) count = values[1] ?? 0;
    const current: ResourceTimer = {
      name,
      durationMs: Math.floor(durationUs / MICROSECONDS_PER_MILLISECOND),
      count,
      confidence: 'EXACT',
    };
    const previous = result[name];
    result[name] =
      previous === undefined
        ? current
        : {
            ...previous,
            durationMs: previous.durationMs + current.durationMs,
            count: previous.count + current.count,
          };
  }
  return result;
}

function parseNetwork(record: CheckinRecord): NetworkUsage {
  const values = record.fields.map((field) => (/^\d+$/.test(field) ? Number(field) : 0));
  const at = (index: number): number => values[index] ?? 0;
  return {
    mobileRxBytes: at(0),
    mobileTxBytes: at(1),
    wifiRxBytes: at(2),
    wifiTxBytes: at(3),
    bluetoothRxBytes: at(4),
    bluetoothTxBytes: at(5),
    mobileRxPackets: at(6),
    mobileTxPackets: at(7),
    wifiRxPackets: at(8),
    wifiTxPackets: at(9),
    mobileRadioActiveMs: Math.floor(at(10) / MICROSECONDS_PER_MILLISECOND),
  };
}

function androidUidOf(match: RegExpExecArray): number | undefined {
  const explicit = match[4];
  if (explicit !== undefined) {
    const parsed = Number(explicit);
    if (Number.isSafeInteger(parsed)) return parsed;
  }
  const userId = match[2] === undefined ? 0 : Number(match[2]);
  const appId = match[3] === undefined ? Number.NaN : Number(match[3]);
  if (!Number.isFinite(appId)) return undefined;
  return userId * PER_USER_UID_RANGE + FIRST_APPLICATION_UID + appId;
}

function modeledEnergy(component: string, value: number): EnergyEstimate {
  return {
    component,
    energyMah: value,
    source: 'SYSTEM_MODEL',
    attributionScope: 'UID',
    confidence: 'MODELED',
  };
}

function parseEnergy(report: string, targetUid: number, warnings: string[]): Record<string, EnergyEstimate> {
  const result: Record<string, EnergyEstimate> = {};
  for (const line of report.split('\n')) {
    const match = UID_ENERGY.exec(line);
    if (match === null) continue;
    if (androidUidOf(match) !== targetUid) continue;
    const total = Number(match[5]);
    if (Number.isFinite(total)) result['total'] = modeledEnergy('total', total);
    for (const component of line.matchAll(COMPONENT_ENERGY)) {
      const value = Number(component[2]);
      if (!Number.isFinite(value)) continue;
      const name = component[1] as string;
      result[name] = modeledEnergy(name, value);
    }
  }
  if (Object.keys(result).length === 0 && /estimated power/i.test(report)) {
    warnings.push('Estimated power was present, but no unambiguous value could be attributed to UID ' + targetUid + '.');
  }
  return result;
}

export function parseBatteryDeviceState(output: string): BatteryDeviceState {
  const values: Record<string, string> = {};
  for (const line of output.split('\n')) {
    const separator = line.indexOf(':');
    if (separator <= 0) continue;
    values[line.slice(0, separator).trim().toLowerCase()] = line.slice(separator + 1).trim();
  }
  const poweredValues = ['ac powered', 'usb powered', 'wireless powered', 'dock powered']
    .map((key) => values[key])
    .filter((value): value is string => value !== undefined)
    .map((value) => value.toLowerCase() === 'true');
  const levelText = values['level'];
  const temperatureText = values['temperature'];
  const voltageText = values['voltage'];
  return {
    ...(levelText !== undefined && /^\d+$/.test(levelText) ? { levelPercent: Number(levelText) } : {}),
    ...(temperatureText !== undefined && /^-?\d+$/.test(temperatureText)
      ? { temperatureTenthsCelsius: Number(temperatureText) }
      : {}),
    ...(voltageText !== undefined && /^\d+$/.test(voltageText) ? { voltageMillivolts: Number(voltageText) } : {}),
    ...(poweredValues.length > 0 ? { powered: poweredValues.some((value) => value) } : {}),
    ...(values['status'] !== undefined ? { status: values['status'] } : {}),
    rawValues: values,
  };
}

export function parseBatteryHistoryLine(line: string): BatteryHistoryEvent | undefined {
  if (!line.includes(',h,') && !line.startsWith('h,')) return undefined;
  const elapsed = HISTORY_TIME.exec(line)?.[1];
  const marker = HISTORY_MARKER.exec(line);
  const token = (marker?.[2] ?? '').toLowerCase();
  let kind: BatteryHistoryEventKind = 'UNKNOWN';
  if (token.includes('wake')) kind = 'WAKELOCK';
  else if (token.includes('alarm')) kind = 'ALARM';
  else if (token.includes('job')) kind = 'JOB';
  else if (token.includes('sensor')) kind = 'SENSOR';
  else if (token.includes('wifi') || token.includes('network') || token.includes('mobile')) kind = 'NETWORK';
  else if (token.includes('screen')) kind = 'SCREEN';
  else if (token.includes('charge') || token.includes('plug')) kind = 'CHARGING';
  else if (token.includes('temp') || token.includes('thermal')) kind = 'THERMAL';
  else if (token.includes('top') || token.includes('proc')) kind = 'APP_STATE';
  const uid = HISTORY_UID.exec(line)?.[1];
  const confidence: EvidenceConfidence = marker === null ? 'INFERRED' : 'EXACT';
  return {
    ...(elapsed !== undefined ? { elapsedMs: Number(elapsed) } : {}),
    kind,
    ...(marker !== null ? { active: marker[1] === '+' } : {}),
    ...(marker !== null ? { name: marker[2] as string } : {}),
    ...(uid !== undefined ? { uid: Number(uid) } : {}),
    raw: line,
    confidence,
  };
}

export function parseBatteryHistory(output: string): BatteryHistoryEvent[] {
  return output
    .split('\n')
    .map((line) => parseBatteryHistoryLine(line.trim()))
    .filter((event): event is BatteryHistoryEvent => event !== undefined);
}

/**
 * Port of BatteryStatsParser. Resource ownership is never inferred from a UID
 * counter: framework-mediated names only raise an attribution warning.
 */
export function parseBatteryStats(
  checkin: string,
  report: string,
  battery: string,
  targetUid: number,
): ParsedBatteryStats {
  const warnings: string[] = [];
  if (battery.trim().length === 0) warnings.push('Battery device state output was empty.');
  const records: CheckinRecord[] = [];
  checkin.split('\n').forEach((line, index) => {
    const record = parseRecord(index + 1, line, warnings);
    if (record !== undefined) records.push(record);
  });
  const uidRecords = records.filter(
    (record) => record.uid === targetUid && (record.aggregation === 'l' || record.aggregation.length === 0),
  );
  const wakelocks = timers(uidRecords, new Set(['wl', 'kwl', 'wfl']), 'wakelock', warnings);
  const alarms = timers(uidRecords, new Set(['apk', 'wua', 'wa']), 'alarm', warnings);
  const jobs = timers(uidRecords, new Set(['jb', 'job']), 'job', warnings);
  const sensors = timers(uidRecords, new Set(['sr', 'sensor']), 'sensor', warnings);
  const networkRecord = uidRecords.find((record) => record.type === 'nt');
  const network = networkRecord === undefined ? EMPTY_NETWORK_USAGE : parseNetwork(networkRecord);
  const energy = parseEnergy(report, targetUid, warnings);
  const names = [...Object.keys(wakelocks), ...Object.keys(alarms), ...Object.keys(jobs)];
  if (names.some((name) => name.startsWith('*'))) {
    warnings.push(
      'Framework-mediated resource names were observed; UID attribution does not establish component ownership.',
    );
  }
  const periodRecord = records.find((record) => PERIOD_TYPES.has(record.type));
  const history = records
    .filter((record) => record.type === 'h')
    .map((record) => parseBatteryHistoryLine(record.raw))
    .filter((event): event is BatteryHistoryEvent => event !== undefined);
  if (uidRecords.length === 0) warnings.push('No checkin records were found for UID ' + targetUid + '.');
  return {
    ...(periodRecord !== undefined ? { statsPeriodId: periodRecord.fields.join(':') } : {}),
    uidStats: { uid: targetUid, wakelocks, alarms, jobs, sensors, network, energy },
    history,
    warnings: [...new Set(warnings)],
  };
}
