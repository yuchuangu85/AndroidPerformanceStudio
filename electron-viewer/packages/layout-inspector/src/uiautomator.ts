import { AdbOutputParseError } from './errors.js';
import { parseUiNode, type Bounds, type UiNode } from './model.js';

export interface UiAutomatorHierarchy {
  readonly rotation: number;
  readonly root: UiNode;
}

const TAG = /<(\/?)(node|hierarchy)((?:\s+[a-zA-Z_:][a-zA-Z0-9:._-]*\s*=\s*"[^"]*")*)\s*(\/?)>/g;
const ATTRIBUTE = /([a-zA-Z_:][a-zA-Z0-9:._-]*)\s*=\s*"([^"]*)"/g;
const BOUNDS = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/;

export function parseBoundsAttribute(value: string): Bounds | undefined {
  const match = BOUNDS.exec(value.trim());
  if (match === null) return undefined;
  return {
    left: Number(match[1]),
    top: Number(match[2]),
    right: Number(match[3]),
    bottom: Number(match[4]),
  };
}

function decodeEntities(value: string): string {
  return value
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_match, code: string) => String.fromCodePoint(Number(code)))
    .replace(/&amp;/g, '&');
}

function attributesOf(raw: string): Record<string, string> {
  const attributes: Record<string, string> = {};
  for (const match of raw.matchAll(ATTRIBUTE)) {
    attributes[match[1] as string] = decodeEntities(match[2] as string);
  }
  return attributes;
}

function booleanAttribute(attributes: Record<string, string>, name: string): boolean | undefined {
  const value = attributes[name];
  if (value === undefined) return undefined;
  return value.toLowerCase() === 'true';
}

interface RawNode {
  readonly id: string;
  readonly attributes: Record<string, string>;
  readonly children: unknown[];
}

/**
 * Parses uiautomator dump output into a ViewNode tree. Node ids are structural
 * paths (0, 0.1, ...) so the same hierarchy yields the same ids.
 */
export function parseUiAutomatorHierarchy(xml: string): UiAutomatorHierarchy {
  let rotation = 0;
  const stack: RawNode[] = [];
  const roots: RawNode[] = [];

  for (const match of xml.matchAll(TAG)) {
    const closing = match[1] === '/';
    const name = match[2];
    const selfClosing = match[4] === '/';
    if (name === 'hierarchy') {
      if (!closing) {
        const attributes = attributesOf(match[3] ?? '');
        const value = Number(attributes['rotation'] ?? '0');
        rotation = Number.isFinite(value) ? value : 0;
      }
      continue;
    }
    if (closing) {
      const completed = stack.pop();
      if (completed === undefined) {
        throw new AdbOutputParseError('uiautomator hierarchy closed more nodes than it opened');
      }
      const parent = stack.at(-1);
      if (parent === undefined) roots.push(completed);
      else parent.children.push(completed);
      continue;
    }
    const attributes = attributesOf(match[3] ?? '');
    const parent = stack.at(-1);
    const index = parent === undefined ? String(roots.length) : String(parent.children.length);
    // The reference names paths root, root/0, root/0/1 — the same ids the
    // Visible Window Views reader produces, so both paths show one id scheme.
    const id = parent === undefined ? 'root' : parent.id + '/' + index;
    const entry: RawNode = { id, attributes, children: [] };
    if (selfClosing) {
      if (parent === undefined) roots.push(entry);
      else parent.children.push(entry);
    } else {
      stack.push(entry);
    }
  }
  if (stack.length > 0) {
    throw new AdbOutputParseError('uiautomator hierarchy has unclosed nodes');
  }
  const root = roots[0];
  if (root === undefined) {
    throw new AdbOutputParseError('uiautomator hierarchy has no nodes');
  }
  return { rotation, root: toUiNode(root) };
}

function optionalBoolean(key: string, value: boolean | undefined): Record<string, boolean> {
  return value === undefined ? {} : { [key]: value };
}

function toUiNode(raw: RawNode): UiNode {
  const attributes = raw.attributes;
  const bounds = parseBoundsAttribute(attributes['bounds'] ?? '');
  if (bounds === undefined) {
    throw new AdbOutputParseError('uiautomator node is missing a bounds attribute: ' + raw.id);
  }
  const className = attributes['class'] ?? '';
  if (className.length === 0) {
    throw new AdbOutputParseError('uiautomator node is missing a class attribute: ' + raw.id);
  }
  const resourceName = attributes['resource-id'];
  const text = attributes['text'];
  const contentDescription = attributes['content-desc'];
  return parseUiNode({
    type: 'view',
    id: raw.id,
    className,
    bounds,
    visible: true,
    alpha: 1,
    children: raw.children.map((child) => toUiNode(child as RawNode)),
    ...(resourceName !== undefined && resourceName.length > 0 ? { resourceName } : {}),
    ...(text !== undefined && text.length > 0 ? { text } : {}),
    attributes: {
      ...(contentDescription !== undefined && contentDescription.length > 0 ? { contentDescription } : {}),
      ...optionalBoolean('enabled', booleanAttribute(attributes, 'enabled')),
      ...optionalBoolean('clickable', booleanAttribute(attributes, 'clickable')),
      ...optionalBoolean('longClickable', booleanAttribute(attributes, 'long-clickable')),
      ...optionalBoolean('focusable', booleanAttribute(attributes, 'focusable')),
      ...optionalBoolean('focused', booleanAttribute(attributes, 'focused')),
      ...optionalBoolean('selected', booleanAttribute(attributes, 'selected')),
      rawProperties: attributes,
    },
  });
}
