/** Small helpers for the JSON that arrives from the API. */
export function asRecord(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(label + ' was not a JSON object');
  }
  return value as Record<string, unknown>;
}

export function parseJsonRecord(text: string, label: string): Record<string, unknown> {
  return asRecord(JSON.parse(text), label);
}
