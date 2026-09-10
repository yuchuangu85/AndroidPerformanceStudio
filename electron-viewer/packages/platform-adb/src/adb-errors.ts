export class AdbError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = 'AdbError';
  }
}

export class AdbNotFoundError extends AdbError {
  constructor(message = 'ADB was not found in the configured Android SDK, environment, or PATH') {
    super(message);
    this.name = 'AdbNotFoundError';
  }
}

export class AdbNotExecutableError extends AdbError {
  readonly path: string;
  constructor(path: string) {
    super('ADB executable is not usable: ' + path);
    this.name = 'AdbNotExecutableError';
    this.path = path;
  }
}

export class AdbProcessStartError extends AdbError {
  readonly command: readonly string[];
  constructor(command: readonly string[], cause: unknown) {
    super('Failed to start ADB command: ' + command.join(' '), { cause });
    this.name = 'AdbProcessStartError';
    this.command = command;
  }
}

export class AdbCommandTimeoutError extends AdbError {
  readonly command: readonly string[];
  readonly timeoutMs: number;
  readonly pid: number;
  constructor(command: readonly string[], timeoutMs: number, pid: number) {
    super('ADB command timed out after ' + timeoutMs + 'ms: ' + command.join(' '));
    this.name = 'AdbCommandTimeoutError';
    this.command = command;
    this.timeoutMs = timeoutMs;
    this.pid = pid;
  }
}

export class AdbCommandCancelledError extends AdbError {
  readonly command: readonly string[];
  readonly pid: number;
  constructor(command: readonly string[], pid: number) {
    super('ADB command was cancelled: ' + command.join(' '));
    this.name = 'AdbCommandCancelledError';
    this.command = command;
    this.pid = pid;
  }
}

export class AdbCommandFailedError extends AdbError {
  readonly command: readonly string[];
  readonly exitCode: number;
  readonly stderr: string;
  constructor(command: readonly string[], exitCode: number, stderr: string) {
    const detail = stderr.trim().length > 0 ? stderr.trim() : command.join(' ');
    super('ADB command failed with exit code ' + exitCode + ': ' + detail);
    this.name = 'AdbCommandFailedError';
    this.command = command;
    this.exitCode = exitCode;
    this.stderr = stderr;
  }
}

export class AdbOutputParseError extends AdbError {
  constructor(message: string) {
    super(message);
    this.name = 'AdbOutputParseError';
  }
}

export class AdbInputError extends AdbError {
  constructor(message: string) {
    super(message);
    this.name = 'AdbInputError';
  }
}
