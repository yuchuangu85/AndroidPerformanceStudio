import { describe, expect, it } from 'vitest';
import type { HostProcessBinaryResult, HostProcessRequest } from '@aps/platform-host';
import { AdbClient, type AdbBinaryExecutor } from './adb-client.js';
import { AdbCommandFailedError, AdbInputError } from './adb-errors.js';

const DEVICES_OUTPUT = [
  'List of devices attached',
  'emulator-5554 device product:sdk model:Pixel device:emu transport_id:7',
].join('\n');

function fakeExecutor(stdout: string) {
  const calls: HostProcessRequest[] = [];
  const execute: AdbBinaryExecutor = async (request) => {
    calls.push(request);
    const result: HostProcessBinaryResult = {
      pid: 42,
      exitCode: 0,
      stdout: Buffer.from(stdout, 'utf8'),
      stderr: Buffer.alloc(0),
      durationMs: 1,
      stdoutTruncated: false,
      stderrTruncated: false,
    };
    return result;
  };
  return { calls, execute };
}

describe('AdbClient', () => {
  it('lists and parses devices', async () => {
    const fake = fakeExecutor(DEVICES_OUTPUT);
    const client = new AdbClient({ executable: '/sdk/adb', execute: fake.execute });
    const devices = await client.listDevices();
    expect(devices[0]).toMatchObject({ serial: 'emulator-5554', state: 'ONLINE', transportId: 7 });
    expect(fake.calls[0]?.args).toEqual(['devices', '-l']);
  });

  it('quotes remote shell arguments but not exec-out arguments', async () => {
    const shellFake = fakeExecutor('ok');
    const shellClient = new AdbClient({ executable: '/sdk/adb', execute: shellFake.execute });
    await shellClient.shell('SER', ['pm', 'path', "com.x'y"]);
    expect(shellFake.calls[0]?.args).toEqual([
      '-s',
      'SER',
      'shell',
      "'pm'",
      "'path'",
      "'com.x'\"'\"'y'",
    ]);

    const execFake = fakeExecutor('raw');
    const execClient = new AdbClient({ executable: '/sdk/adb', execute: execFake.execute });
    await execClient.execOut('SER', ['screencap', '-p']);
    expect(execFake.calls[0]?.args).toEqual(['-s', 'SER', 'exec-out', 'screencap', '-p']);
  });

  it('builds push, pull, forward, remove-forward, and bugreport argument vectors', async () => {
    const fake = fakeExecutor('ok');
    const client = new AdbClient({
      executable: '/sdk/adb',
      execute: fake.execute,
      isRegularFile: (path) => path === '/local/file',
    });
    await client.push('SER', '/local/file', '/data/local/tmp/f');
    await client.pull('SER', '/data/local/tmp/f', '/local/out');
    await client.forward('SER', 'tcp:0', 'localabstract:agent');
    await client.removeForward('SER', 'tcp:1234');
    await client.bugreport('SER', '/local/bugreport.zip');
    expect(fake.calls.map((call) => call.args)).toEqual([
      ['-s', 'SER', 'push', '/local/file', '/data/local/tmp/f'],
      ['-s', 'SER', 'pull', '/data/local/tmp/f', '/local/out'],
      ['-s', 'SER', 'forward', 'tcp:0', 'localabstract:agent'],
      ['-s', 'SER', 'forward', '--remove', 'tcp:1234'],
      ['-s', 'SER', 'bugreport', '/local/bugreport.zip'],
    ]);
  });

  it('rejects a push whose local source is not a regular file', async () => {
    const fake = fakeExecutor('ok');
    const client = new AdbClient({ executable: '/sdk/adb', execute: fake.execute, isRegularFile: () => false });
    await expect(client.push('SER', '/missing', '/remote')).rejects.toBeInstanceOf(AdbInputError);
  });

  it('fails on a non-zero exit code', async () => {
    const execute: AdbBinaryExecutor = async () => ({
      pid: 1,
      exitCode: 1,
      stdout: Buffer.alloc(0),
      stderr: Buffer.from('device offline', 'utf8'),
      durationMs: 1,
      stdoutTruncated: false,
      stderrTruncated: false,
    });
    const client = new AdbClient({ executable: '/sdk/adb', execute });
    await expect(client.listDevices()).rejects.toBeInstanceOf(AdbCommandFailedError);
  });
});
