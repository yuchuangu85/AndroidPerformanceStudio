import { tokenizeXml } from './xml.js';

export type PreferenceNode = ReadonlyMap<string, string>;
export type PreferenceNodes = ReadonlyMap<string, PreferenceNode>;

/** Parses java.util.prefs prefs.xml files under ~/.java/.userPrefs. */
export function parseLinuxPreferencesXml(xml: string): PreferenceNodes {
  const nodes = new Map<string, Map<string, string>>();
  const stack: string[] = [];
  for (const tag of tokenizeXml(xml)) {
    if (tag.name !== 'node') continue;
    if (tag.closing) {
      stack.pop();
      continue;
    }
    stack.push(tag.attributes['name'] ?? '');
    nodes.set('/' + stack.join('/'), new Map());
  }
  const entryStack: string[] = [];
  for (const tag of tokenizeXml(xml)) {
    if (tag.name === 'node') {
      if (tag.closing) entryStack.pop();
      else entryStack.push(tag.attributes['name'] ?? '');
    } else if (tag.name === 'entry') {
      const key = tag.attributes['key'];
      const value = tag.attributes['value'];
      if (key === undefined || value === undefined) continue;
      nodes.get('/' + entryStack.join('/'))?.set(key, value);
    }
  }
  // java.util.prefs resolves a node's keys through its ancestors.
  const resolved = new Map<string, Map<string, string>>();
  for (const path of nodes.keys()) {
    const merged = new Map<string, string>();
    for (const ancestor of ancestorPaths(path)) {
      for (const [key, value] of nodes.get(ancestor) ?? []) merged.set(key, value);
    }
    for (const [key, value] of nodes.get(path) ?? []) merged.set(key, value);
    resolved.set(path, merged);
  }
  return resolved;
}

function ancestorPaths(path: string): string[] {
  const segments = path.split('/').filter((segment) => segment.length > 0);
  const ancestors: string[] = [];
  for (let index = 1; index < segments.length; index += 1) {
    ancestors.push('/' + segments.slice(0, index).join('/'));
  }
  return ancestors;
}
