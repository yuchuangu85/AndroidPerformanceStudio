/**
 * Port of TraceQueryResult / TraceQueryRow / TraceColumn / TraceQuery.
 * Trace Processor prints query results as CSV, including quoted fields.
 */

import { PINNED_TRACE_PROCESSOR_VERSION } from './version.js';

export class TraceQueryRow {
  private readonly values: ReadonlyMap<string, string>;

  constructor(values: ReadonlyMap<string, string>) {
    this.values = values;
  }

  string(column: string): string | undefined {
    const value = this.values.get(column);
    return value === undefined || value === '[NULL]' ? undefined : value;
  }

  long(column: string): number | undefined {
    const value = this.string(column);
    if (value === undefined) return undefined;
    const parsed = Number(value);
    return Number.isInteger(parsed) ? parsed : undefined;
  }

  double(column: string): number | undefined {
    const value = this.string(column);
    if (value === undefined) return undefined;
    const parsed = Number(value);
    return Number.isNaN(parsed) ? undefined : parsed;
  }

  get<T>(column: TraceColumn<T>): T | undefined {
    return column.read(this);
  }
}

export class TraceColumn<T> {
  readonly name: string;
  private readonly readValue: (row: TraceQueryRow) => T | undefined;

  private constructor(name: string, readValue: (row: TraceQueryRow) => T | undefined) {
    this.name = name;
    this.readValue = readValue;
  }

  read(row: TraceQueryRow): T | undefined {
    return this.readValue(row);
  }

  static string(name: string): TraceColumn<string> {
    return new TraceColumn(name, (row) => row.string(name));
  }

  static long(name: string): TraceColumn<number> {
    return new TraceColumn(name, (row) => row.long(name));
  }

  static double(name: string): TraceColumn<number> {
    return new TraceColumn(name, (row) => row.double(name));
  }
}

export interface TraceQuerySchema {
  readonly traceProcessorVersion: string;
  readonly columns: readonly TraceColumn<unknown>[];
}

export function traceQuerySchemaV57_2(...columns: readonly TraceColumn<unknown>[]): TraceQuerySchema {
  if (columns.length === 0) throw new Error('typed trace queries need at least one column');
  const names = columns.map((column) => column.name);
  if (new Set(names).size !== names.length) {
    throw new Error('typed trace query columns must be unique');
  }
  return { traceProcessorVersion: PINNED_TRACE_PROCESSOR_VERSION, columns };
}

export class TraceQuery<T> {
  readonly sql: string;
  readonly schema: TraceQuerySchema;
  private readonly mapRow: (row: TraceQueryRow) => T;

  constructor(sql: string, schema: TraceQuerySchema, mapRow: (row: TraceQueryRow) => T) {
    if (sql.trim().length === 0) throw new Error('trace SQL must not be blank');
    this.sql = sql;
    this.schema = schema;
    this.mapRow = mapRow;
  }

  map(result: TraceQueryResult): T[] {
    const expected = this.schema.columns.map((column) => column.name);
    if (result.columns.length !== expected.length || result.columns.some((column, index) => column !== expected[index])) {
      throw new Error('trace query result does not match its pinned schema');
    }
    return result.rows.map((row) => this.mapRow(row));
  }
}

export interface TraceQueryResult {
  readonly columns: readonly string[];
  readonly rows: readonly TraceQueryRow[];
}

export function parseTraceQueryResult(csv: string): TraceQueryResult {
  const values = parseCsv(csv).filter((row, index) => index === 0 || !row.every((cell) => cell.trim().length === 0));
  const first = values[0];
  if (first === undefined) return { columns: [], rows: [] };
  if (first.some((column) => column.trim().length === 0)) {
    throw new Error('trace query returned a blank column name');
  }
  const rows: TraceQueryRow[] = [];
  for (const row of values.slice(1)) {
    if (row.length === 0) continue;
    if (row.length !== first.length) {
      throw new Error('trace query row does not match its header');
    }
    const map = new Map<string, string>();
    first.forEach((column, index) => map.set(column, row[index] ?? ''));
    rows.push(new TraceQueryRow(map));
  }
  return { columns: first, rows };
}

function parseCsv(input: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let value = '';
  let quoted = false;
  for (let index = 0; index < input.length; index += 1) {
    const character = input[index] as string;
    if (character === '"') {
      if (quoted && input[index + 1] === '"') {
        value += '"';
        index += 1;
      } else {
        quoted = !quoted;
      }
    } else if (character === ',') {
      if (quoted) value += character;
      else {
        row.push(value);
        value = '';
      }
    } else if (character === '\n') {
      if (quoted) value += character;
      else {
        row.push(value);
        rows.push(row);
        row = [];
        value = '';
      }
    } else if (character !== '\r') {
      value += character;
    }
  }
  if (quoted) throw new Error('trace query returned unterminated CSV quoting');
  if (value.length > 0 || row.length > 0) {
    row.push(value);
    rows.push(row);
  }
  return rows;
}

