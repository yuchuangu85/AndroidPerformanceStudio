import { describe, expect, it } from 'vitest';
import { MethodRecordingLifecycle } from './method-recording-lifecycle.js';

describe('MethodRecordingLifecycle', () => {
  it('owns one capture, makes stop cooperative, and releases it after finalization', () => {
    const lifecycle = new MethodRecordingLifecycle();
    const first = lifecycle.begin();

    expect(first).toBeDefined();
    expect(lifecycle.begin()).toBeUndefined();
    expect(lifecycle.requestStop()).toBe(true);
    expect(first?.shouldStop()).toBe(true);

    lifecycle.finish(first!);
    expect(lifecycle.requestStop()).toBe(false);
  });

  it('does not let an older completion release a newer recording', () => {
    const lifecycle = new MethodRecordingLifecycle();
    const first = lifecycle.begin();
    lifecycle.finish(first!);
    const second = lifecycle.begin();

    lifecycle.finish(first!);

    expect(second).toBeDefined();
    expect(lifecycle.begin()).toBeUndefined();
    expect(lifecycle.requestStop()).toBe(true);
    expect(second?.shouldStop()).toBe(true);
  });
});
