import { describe, expect, it } from 'vitest';
import { composePerfettoConfig } from './perfetto-config.js';

describe('composePerfettoConfig', () => {
  it('serializes only the requested data sources', () => {
    const config = composePerfettoConfig({
      durationMillis: 5000,
      bufferSizeKb: 2048,
      flushPeriodMillis: 1000,
      dataSources: [
        { name: 'linux.ftrace', config: 'ftrace_config {\n  ftrace_events: "sched/sched_switch"\n}' },
      ],
    });
    expect(config).toBe(
      [
        'buffers: {',
        '  size_kb: 2048',
        '  fill_policy: RING_BUFFER',
        '}',
        'duration_ms: 5000',
        'flush_period_ms: 1000',
        'data_sources: {',
        '  config {',
        '    name: "linux.ftrace"',
        '    ftrace_config {',
        '      ftrace_events: "sched/sched_switch"',
        '    }',
        '  }',
        '}',
        '',
      ].join('\n'),
    );
  });

  it('rejects invalid documents', () => {
    expect(() => composePerfettoConfig({ durationMillis: 0, bufferSizeKb: 2048, dataSources: [{ name: 'a' }] })).toThrow();
    expect(() => composePerfettoConfig({ durationMillis: 1, bufferSizeKb: 1, dataSources: [{ name: 'a' }] })).toThrow();
    expect(() => composePerfettoConfig({ durationMillis: 1, bufferSizeKb: 2048, dataSources: [] })).toThrow();
    expect(() =>
      composePerfettoConfig({ durationMillis: 1, bufferSizeKb: 2048, dataSources: [{ name: 'bad name' }] }),
    ).toThrow();
  });
});
