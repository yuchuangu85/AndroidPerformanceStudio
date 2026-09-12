import type { UiNode, ViewAttributes, ViewNode } from '@aps/layout-inspector';
import type { UiLanguage } from '../../../shared/i18n';
import { layoutText } from './labels';

/**
 * Port of NodeDetailsPresenter.present: the PROPERTIES pane is five sections of
 * derived facts plus every raw property the device reported, and a missing field
 * is a dash rather than a dropped row. Showing only the handful of fields that
 * happen to be present is what made the pane look incomplete.
 */
export type DetailTone = 'normal' | 'info' | 'warning';

export interface DetailRow {
  readonly label: string;
  readonly value: string;
  readonly tone: DetailTone;
}

export interface DetailSection {
  readonly title: string;
  readonly rows: readonly DetailRow[];
  /** The first section is the render-risk summary, which the pane emphasises. */
  readonly highlightsRenderingRisk?: boolean;
}

const SIGNIFICANT_OVERLAP_RATIO = 0.8;
const COMPLEXITY_DEPTH_WARNING = 10;
const DESCENDANT_WARNING = 50;

interface Complexity {
  readonly descendants: number;
  readonly depth: number;
  readonly hidden: number;
}

interface OverlapStats {
  readonly pairs: number;
  readonly maxRatio: number;
}

/** Kotlin's Float.toString keeps a decimal point; String(1) would lose it. */
function formatFloat(value: number): string {
  return Number.isInteger(value) ? value.toFixed(1) : String(value);
}

function formatBounds(bounds: { left: number; top: number; right: number; bottom: number }): string {
  return bounds.left + ', ' + bounds.top + ', ' + bounds.right + ', ' + bounds.bottom;
}

function formatDimension(value: number | undefined): string | undefined {
  if (value === undefined) return undefined;
  if (value === -1) return 'MATCH_PARENT (-1)';
  if (value === -2) return 'WRAP_CONTENT (-2)';
  return String(value);
}

function complexityOf(node: UiNode): Complexity {
  if (node.children.length === 0) return { descendants: 0, depth: 1, hidden: 0 };
  const childComplexities = node.children.map(complexityOf);
  return {
    descendants:
      node.children.length + childComplexities.reduce((total, child) => total + child.descendants, 0),
    depth: 1 + Math.max(...childComplexities.map((child) => child.depth)),
    hidden:
      node.children.filter((child) => !child.visible || child.alpha <= 0).length +
      childComplexities.reduce((total, child) => total + child.hidden, 0),
  };
}

function overlapRatio(first: UiNode['bounds'], second: UiNode['bounds']): number {
  const width = Math.max(0, Math.min(first.right, second.right) - Math.max(first.left, second.left));
  const height = Math.max(0, Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top));
  const smallerArea = Math.min(
    (first.right - first.left) * (first.bottom - first.top),
    (second.right - second.left) * (second.bottom - second.top),
  );
  if (smallerArea === 0) return 0;
  return (width * height) / smallerArea;
}

function overlapStatsOf(node: UiNode): OverlapStats {
  let pairs = 0;
  let maxRatio = 0;
  for (let first = 0; first < node.children.length; first += 1) {
    for (let second = first + 1; second < node.children.length; second += 1) {
      const firstChild = node.children[first];
      const secondChild = node.children[second];
      if (firstChild === undefined || secondChild === undefined) continue;
      const ratio = overlapRatio(firstChild.bounds, secondChild.bounds);
      if (ratio >= SIGNIFICANT_OVERLAP_RATIO) {
        pairs += 1;
        maxRatio = Math.max(maxRatio, ratio);
      }
    }
  }
  return { pairs, maxRatio };
}

function attributesOf(node: UiNode): ViewAttributes | undefined {
  return node.type === 'view' ? (node as ViewNode).attributes : undefined;
}

export function nodeDetailSections(node: UiNode, treeDepth: number, language: UiLanguage): DetailSection[] {
  const viewNode = node.type === 'view' ? (node as ViewNode) : undefined;
  const attributes = attributesOf(node) ?? ({} as ViewAttributes);
  const complexity = complexityOf(node);
  const overlap = overlapStatsOf(node);
  const translate = (key: Parameters<typeof layoutText>[0], ...args: ReadonlyArray<string | number>): string =>
    layoutText(key, language, ...args);
  const value = (input: string | number | boolean | undefined): string =>
    input === undefined ? translate('value.dash') : typeof input === 'boolean' ? (input ? 'true' : 'false') : String(input);
  const fact = (key: Parameters<typeof layoutText>[0], input: string | number | boolean | undefined, tone: DetailTone = 'normal'): DetailRow => ({
    label: translate(key),
    value: value(input),
    tone,
  });

  const complexityRisk = complexity.depth > COMPLEXITY_DEPTH_WARNING || complexity.descendants > DESCENDANT_WARNING;
  const alignment = (count: number, ratio: number): string =>
    count === 1 ? translate('value.overlapPairSingle', count, ratio) : translate('value.overlapPairs', count, ratio);

  const risks: DetailRow[] = [
    fact(
      'label.overdrawEstimate',
      overlap.pairs === 0 ? translate('value.noOverlapPairs') : alignment(overlap.pairs, Math.round(overlap.maxRatio * 100)),
      overlap.pairs > 0 ? 'warning' : 'normal',
    ),
    fact(
      'label.subtreeComplexity',
      translate('value.subtreeComplexity', complexity.descendants, complexity.depth),
      complexityRisk ? 'warning' : 'normal',
    ),
    fact('label.hiddenDescendants', complexity.hidden, complexity.hidden > 0 ? 'info' : 'normal'),
    fact(
      'label.blending',
      node.alpha < 1 ? translate('value.blendingAlpha', formatFloat(node.alpha)) : translate('value.alphaOne'),
      node.alpha < 1 ? 'warning' : 'normal',
    ),
    fact(
      'label.layerCost',
      attributes.layerType ?? translate('value.unavailable'),
      attributes.layerType === 'SOFTWARE' ? 'warning' : 'normal',
    ),
  ];

  const identity: DetailRow[] = [
    fact('label.class', node.className),
    fact('label.id', node.id),
    fact('label.resource', viewNode?.resourceName),
    fact(
      'label.text',
      viewNode?.text ??
        (node.type === 'compose' ? node.text : undefined) ??
        attributes.rawProperties['text:mText'] ??
        attributes.rawProperties['text:text'],
    ),
    fact('label.contentDescription', attributes.contentDescription),
    fact('label.semanticsRole', node.type === 'compose' ? node.semanticsRole : undefined),
  ];

  const layout: DetailRow[] = [
    fact('label.bounds', formatBounds(node.bounds)),
    fact('label.size', node.bounds.right - node.bounds.left + ' × ' + (node.bounds.bottom - node.bounds.top)),
    fact('label.localLayoutBounds', attributes.layoutBounds === undefined ? undefined : formatBounds(attributes.layoutBounds)),
    fact(
      'label.localLayoutSize',
      attributes.layoutBounds === undefined
        ? undefined
        : attributes.layoutBounds.right -
            attributes.layoutBounds.left +
            ' × ' +
            (attributes.layoutBounds.bottom - attributes.layoutBounds.top),
    ),
    fact('label.visibility', attributes.visibility ?? String(node.visible)),
    fact('label.treeDepth', treeDepth),
    fact('label.directChildren', node.children.length),
    fact('label.descendants', complexity.descendants),
    fact('label.subtreeDepth', complexity.depth),
    fact('label.layoutWidth', formatDimension(attributes.layoutWidth)),
    fact('label.layoutHeight', formatDimension(attributes.layoutHeight)),
    fact('label.layoutParamsClass', attributes.layoutParamsClass),
    fact(
      'label.measuredSize',
      attributes.measuredWidth === undefined || attributes.measuredHeight === undefined
        ? undefined
        : attributes.measuredWidth + ' × ' + attributes.measuredHeight,
    ),
    fact(
      'label.minimumSize',
      attributes.minWidth === undefined || attributes.minHeight === undefined
        ? undefined
        : attributes.minWidth + ' × ' + attributes.minHeight,
    ),
    fact('label.padding', attributes.padding === undefined ? undefined : formatBounds(attributes.padding)),
    fact('label.margin', attributes.margin === undefined ? undefined : formatBounds(attributes.margin)),
    fact(
      'label.scroll',
      attributes.scrollX === undefined || attributes.scrollY === undefined
        ? undefined
        : attributes.scrollX + ', ' + attributes.scrollY,
    ),
    fact('label.layoutRequested', attributes.layoutRequested),
  ];

  const drawing: DetailRow[] = [
    fact('label.alpha', formatFloat(node.alpha)),
    fact('label.z', attributes.z === undefined ? undefined : formatFloat(attributes.z)),
    fact('label.elevation', attributes.elevation === undefined ? undefined : formatFloat(attributes.elevation)),
    fact(
      'label.translation',
      attributes.translationX === undefined
        ? undefined
        : [attributes.translationX, attributes.translationY ?? 0, attributes.translationZ ?? 0].map(formatFloat).join(', '),
    ),
    fact(
      'label.rotation',
      attributes.rotation === undefined
        ? undefined
        : [attributes.rotationX ?? 0, attributes.rotationY ?? 0, attributes.rotation as number]
            .map(formatFloat)
            .join(', '),
    ),
    fact(
      'label.scale',
      attributes.scaleX === undefined ? undefined : [attributes.scaleX, attributes.scaleY ?? 1].map(formatFloat).join(', '),
    ),
    fact(
      'label.pivot',
      attributes.pivotX === undefined ? undefined : [attributes.pivotX, attributes.pivotY ?? 0].map(formatFloat).join(', '),
    ),
    fact('label.background', attributes.background),
    fact('label.backgroundColor', attributes.backgroundColor),
    fact('label.foreground', attributes.foreground),
    fact('label.clipBounds', attributes.clipBounds === undefined ? undefined : formatBounds(attributes.clipBounds)),
    fact('label.clipChildren', attributes.clipChildren),
    fact('label.clipToPadding', attributes.clipToPadding),
    fact('label.opaque', attributes.opaque),
    fact('label.willNotDraw', attributes.willNotDraw),
    fact('label.hardwareAccelerated', attributes.hardwareAccelerated),
    fact('label.layerType', attributes.layerType),
  ];

  const interaction: DetailRow[] = [
    fact('label.enabled', attributes.enabled),
    fact('label.clickable', attributes.clickable),
    fact('label.longClickable', attributes.longClickable),
    fact('label.focusable', attributes.focusable),
    fact('label.focused', attributes.focused),
    fact('label.selected', attributes.selected),
  ];

  const sections: DetailSection[] = [
    { title: translate('section.renderRisks'), rows: risks, highlightsRenderingRisk: true },
    { title: translate('section.identity'), rows: identity },
    { title: translate('section.layout'), rows: layout },
    { title: translate('section.drawing'), rows: drawing },
    { title: translate('section.interaction'), rows: interaction },
  ];

  const rawProperties: Record<string, string> = {
    ...attributes.rawProperties,
    ...(node.type === 'compose' ? node.semanticProperties : {}),
  };
  const rawKeys = Object.keys(rawProperties).sort();
  if (rawKeys.length > 0) {
    sections.push({
      title: translate('section.rawProperties'),
      rows: rawKeys.map((key) => ({ label: key, value: rawProperties[key] as string, tone: 'normal' as DetailTone })),
    });
  }
  return sections;
}
