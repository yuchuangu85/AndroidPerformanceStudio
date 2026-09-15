/** Kotlin-compatible parsing for `adb shell simpleperf list` event output. */
const EVENT_NAME = /^[a-z][a-z0-9_-]*(?::[uk])?$/;
const NON_EVENT_LABELS = new Set(['list', 'event', 'events', 'hardware', 'software', 'tracepoint']);

export const DEFAULT_SIMPLEPERF_EVENT_NAMES = ['cpu-clock', 'cpu-cycles', 'task-clock'] as const;

export function parseSimpleperfEventNames(output: string): string[] {
  const events: string[] = [];
  for (const line of output.split(/\r?\n/)) {
    const candidate = line.trim().split(/[\s(]/, 1)[0] ?? '';
    if (!EVENT_NAME.test(candidate) || NON_EVENT_LABELS.has(candidate) || events.includes(candidate)) continue;
    events.push(candidate);
  }
  return events;
}

/** Fallbacks first, then device names, preserving the selected saved event. */
export function simpleperfEventChoices(availableEvents: readonly string[], selectedEvent: string): string[] {
  const choices: string[] = [...DEFAULT_SIMPLEPERF_EVENT_NAMES];
  for (const event of availableEvents) if (!choices.includes(event)) choices.push(event);
  if (selectedEvent.trim().length > 0 && !choices.includes(selectedEvent)) choices.push(selectedEvent);
  return choices;
}
