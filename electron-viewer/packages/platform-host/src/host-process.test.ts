import { describe, expect, it } from 'vitest';
import {
  HostProcessCancelledError,
  HostProcessTimeoutError,
  runHostProcessBinary,
  runHostProcessText,
} from './host-process.js';

const node = process.execPath;
const script = (source: string): string[] => ['-e', source];

describe('runHostProcess', () => {
  it('captures stdout, stderr, and the exit code', async () => {
    const result = await runHostProcessText({
      executable: node,
      args: script("process.stdout.write('out'); process.stderr.write('err'); process.exit(3);"),
    });
    expect(result.stdout).toBe('out');
    expect(result.stderr).toBe('err');
    expect(result.exitCode).toBe(3);
    expect(result.pid).toBeGreaterThan(0);
  });

  it('does not interpret shell metacharacters', async () => {
    const result = await runHostProcessText({
      executable: node,
      args: script('process.stdout.write(process.argv[1] ?? "none")'),
    });
    expect(result.stdout).toBe('none');
    const withArg = await runHostProcessText({
      executable: node,
      args: ['-e', 'process.stdout.write(process.argv[1] ?? "none")', 'a;b$(whoami)'],
    });
    expect(withArg.stdout).toBe('a;b$(whoami)');
  });

  it('returns raw bytes with executeBinary', async () => {
    const result = await runHostProcessBinary({
      executable: node,
      args: script('process.stdout.write(Buffer.from([0, 1, 2, 255]))'),
    });
    expect([...result.stdout]).toEqual([0, 1, 2, 255]);
  });

  it('truncates output past the per-stream limit', async () => {
    const result = await runHostProcessText({
      executable: node,
      args: script("process.stdout.write('abcdefghij')"),
      maxOutputBytesPerStream: 4,
    });
    expect(result.stdout).toBe('abcd');
    expect(result.stdoutTruncated).toBe(true);
  });

  it('times out long processes', async () => {
    await expect(
      runHostProcessText({
        executable: node,
        args: script('setTimeout(() => {}, 10_000)'),
        timeoutMs: 150,
      }),
    ).rejects.toBeInstanceOf(HostProcessTimeoutError);
  });

  it('cancels through an AbortSignal', async () => {
    const controller = new AbortController();
    setTimeout(() => controller.abort(), 100);
    await expect(
      runHostProcessText({
        executable: node,
        args: script('setTimeout(() => {}, 10_000)'),
        timeoutMs: 10_000,
        signal: controller.signal,
      }),
    ).rejects.toBeInstanceOf(HostProcessCancelledError);
  });

  it('fails to start a missing executable', async () => {
    await expect(
      runHostProcessText({ executable: '/definitely/not/a/real/binary-aps', args: [] }),
    ).rejects.toMatchObject({ name: 'HostProcessStartError' });
  });
});
