const NAMED_ENTITIES: Readonly<Record<string, string>> = {
  lt: '<',
  gt: '>',
  amp: '&',
  quot: '"',
  apos: "'",
};

/** Minimal XML entity decoder for java.util.prefs files. */
export function decodeXmlEntities(value: string): string {
  return value.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (match, entity: string) => {
    if (entity.startsWith('#x') || entity.startsWith('#X')) {
      return String.fromCodePoint(Number.parseInt(entity.slice(2), 16));
    }
    if (entity.startsWith('#')) {
      return String.fromCodePoint(Number.parseInt(entity.slice(1), 10));
    }
    return NAMED_ENTITIES[entity.toLowerCase()] ?? match;
  });
}

export interface XmlTag {
  readonly name: string;
  readonly attributes: Readonly<Record<string, string>>;
  readonly closing: boolean;
  readonly selfClosing: boolean;
  readonly index: number;
  readonly end: number;
}

const TAG = /<\/?([a-zA-Z][a-zA-Z0-9:._-]*)((?:\s+[a-zA-Z_:][a-zA-Z0-9:._-]*\s*=\s*"[^"]*")*)\s*(\/?)>/g;
const ATTRIBUTE = /([a-zA-Z_:][a-zA-Z0-9:._-]*)\s*=\s*"([^"]*)"/g;

export function* tokenizeXml(xml: string): Generator<XmlTag> {
  // Do not strip the XML declaration: offsets must stay aligned with the input so
  // callers can slice tag text by index.
  for (const match of xml.matchAll(TAG)) {
    const raw = match[0];
    const attributes: Record<string, string> = {};
    for (const attribute of match[2]?.matchAll(ATTRIBUTE) ?? []) {
      attributes[attribute[1] as string] = decodeXmlEntities(attribute[2] as string);
    }
    yield {
      name: match[1] as string,
      attributes,
      closing: raw.startsWith('</'),
      selfClosing: match[3] === '/',
      index: match.index ?? 0,
      end: (match.index ?? 0) + raw.length,
    };
  }
}
