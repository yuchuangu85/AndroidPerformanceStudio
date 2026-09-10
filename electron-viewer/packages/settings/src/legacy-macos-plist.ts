import type { PreferenceNodes } from './legacy-linux-xml.js';
import { decodeXmlEntities, tokenizeXml } from './xml.js';

/**
 * Parses the XML plist that plutil produces from
 * ~/Library/Preferences/com.apple.java.util.prefs.plist.
 */
export function parseMacOsPreferencesPlist(xml: string): PreferenceNodes {
  const tokens = [...tokenizeXml(xml)];
  let index = 0;

  function textUntilClose(tagName: string): string {
    const start = tokens[index - 1]?.end ?? 0;
    const closing = tokens.findIndex((token, position) => position >= index && token.name === tagName && token.closing);
    const closingTag = closing === -1 ? undefined : tokens[closing];
    index = closing === -1 ? tokens.length : closing + 1;
    return decodeXmlEntities(xml.slice(start, closingTag?.index ?? xml.length));
  }

  function parseDict(): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    while (index < tokens.length) {
      const tag = tokens[index];
      if (tag === undefined || (tag.name === 'dict' && tag.closing)) {
        index += 1;
        break;
      }
      if (tag.name !== 'key') {
        index += 1;
        continue;
      }
      index += 1;
      const key = textUntilClose('key');
      result[key] = parseValue();
    }
    return result;
  }

  function parseValue(): unknown {
    const tag = tokens[index];
    if (tag === undefined) return undefined;
    index += 1;
    if (tag.selfClosing || tag.closing) {
      if (tag.name === 'true') return true;
      if (tag.name === 'false') return false;
      return undefined;
    }
    if (tag.name === 'string' || tag.name === 'date' || tag.name === 'data') return textUntilClose(tag.name);
    if (tag.name === 'integer') return Number.parseInt(textUntilClose(tag.name), 10);
    if (tag.name === 'real') return Number.parseFloat(textUntilClose(tag.name));
    if (tag.name === 'array') {
      const items: unknown[] = [];
      while (index < tokens.length && !(tokens[index]?.name === 'array' && tokens[index]?.closing)) {
        items.push(parseValue());
      }
      index += 1;
      return items;
    }
    if (tag.name === 'dict') return parseDict();
    return undefined;
  }

  while (index < tokens.length && tokens[index]?.name !== 'dict') index += 1;
  index += 1;
  const root = parseDict();
  const nodes = new Map<string, Map<string, string>>();
  for (const [path, value] of Object.entries(root)) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) continue;
    const entries = new Map<string, string>();
    for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
      if (entry === null || entry === undefined) continue;
      entries.set(key, String(entry));
    }
    nodes.set(normalizeNodePath(path), entries);
  }
  return nodes;
}

export function normalizeNodePath(path: string): string {
  const withSlashes = path.replace(/\\/g, '/');
  return withSlashes.startsWith('/') ? withSlashes : '/' + withSlashes;
}
