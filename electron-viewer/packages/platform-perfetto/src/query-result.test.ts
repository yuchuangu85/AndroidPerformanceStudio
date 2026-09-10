import { describe, expect, it } from 'vitest';
import {
  parseTraceQueryResult,
  traceQuerySchemaV57_2,
  TraceColumn,
  TraceQuery,
} from './query-result.js';

describe('parseTraceQueryResult', () => {
  it('parses plain and quoted CSV fields', () => {
    const csv = 'name,value\nalpha,1\n"b,eta",2\n"line\nbreak",3\nnullish,[NULL]\n';
    const result = parseTraceQueryResult(csv);
    expect(result.columns).toEqual(['name', 'value']);
    expect(result.rows).toHaveLength(4);
    expect(result.rows[0]?.string('name')).toBe('alpha');
    expect(result.rows[1]?.string('name')).toBe('b,eta');
    expect(result.rows[2]?.string('name')).toBe('line\nbreak');
    expect(result.rows[3]?.string('value')).toBeUndefined();
    expect(result.rows[3]?.string('name')).toBe('nullish');
  });

  it('unescapes doubled quotes', () => {
    const result = parseTraceQueryResult('name\n"say ""hi"""\n');
    expect(result.rows[0]?.string('name')).toBe('say "hi"');
  });

  it('returns empty for blank input and array forms', () => {
    expect(parseTraceQueryResult('')).toEqual({ columns: [], rows: [] });
  });

  it('exposes typed long and double accessors', () => {
    const result = parseTraceQueryResult('n,ratio\n42,1.5\n');
    expect(result.rows[0]?.long('n')).toBe(42);
    expect(result.rows[0]?.double('ratio')).toBe(1.5);
    expect(result.rows[0]?.long('ratio')).toBeUndefined();
  });

  it('rejects blank column names, mismatched rows, and unterminated quoting', () => {
    expect(() => parseTraceQueryResult('a,,c\n1,2,3\n')).toThrow();
    expect(() => parseTraceQueryResult('a,b\n1\n')).toThrow();
    expect(() => parseTraceQueryResult('a\n"unterminated\n')).toThrow();
  });

  it('maps a typed query and validates its schema', () => {
    const schema = traceQuerySchemaV57_2(TraceColumn.string('name'), TraceColumn.long('dur'));
    const query = new TraceQuery(schema ? 'select name, dur' : '', schema, (row) => ({
      name: row.get(TraceColumn.string('name')),
      duration: row.get(TraceColumn.long('dur')),
    }));
    const mapped = query.map(parseTraceQueryResult('name,dur\nslice,10\n'));
    expect(mapped).toEqual([{ name: 'slice', duration: 10 }]);
    expect(() => query.map(parseTraceQueryResult('other,dur\nslice,10\n'))).toThrow();
  });
});
