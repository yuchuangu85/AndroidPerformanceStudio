import { describe, expect, it } from 'vitest';
import { fail, isFailure, isOk, mapResult, ok, unwrap } from './studio-result';

describe('StudioResult', () => {
  it('represents success and failure as a discriminated union', () => {
    const success = ok(42);
    const failure = fail('IO', 'READ_FAILED', 'could not read artifact');

    expect(isOk(success)).toBe(true);
    expect(isFailure(success)).toBe(false);
    expect(isFailure(failure)).toBe(true);
    expect(failure.error).toEqual({ category: 'IO', code: 'READ_FAILED', message: 'could not read artifact' });
  });

  it('includes cause only when provided', () => {
    expect('cause' in fail('IO', 'X', 'y').error).toBe(false);
    expect(fail('IO', 'X', 'y', 'root').error.cause).toBe('root');
  });

  it('unwraps success and throws on failure', () => {
    expect(unwrap(ok('value'))).toBe('value');
    expect(() => unwrap(fail('UNKNOWN', 'BOOM', 'nope'))).toThrow('UNKNOWN:BOOM: nope');
  });

  it('maps the success value only', () => {
    expect(mapResult(ok(2), (value) => value * 2)).toEqual({ ok: true, value: 4 });
    const failure = fail('UNKNOWN', 'BOOM', 'nope');
    expect(mapResult(failure, (value: number) => value)).toBe(failure);
  });
});
