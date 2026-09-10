export class LayoutInspectorError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'LayoutInspectorError';
  }
}

export class AdbOutputParseError extends LayoutInspectorError {
  constructor(message: string) {
    super(message);
    this.name = 'AdbOutputParseError';
  }
}
