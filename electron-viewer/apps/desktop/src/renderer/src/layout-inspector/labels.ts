import type { UiLanguage } from '../../../shared/i18n';

/**
 * Layout Inspector strings, ported key by key from the Compose resources
 * (values/strings.xml and values-zh/strings.xml) so the panes read exactly the
 * way they read before the rewrite, in both languages.
 */
export const LAYOUT_STRINGS = {
  'pane.hierarchy': { en: 'HIERARCHY', zh: '层级结构' },
  'pane.canvas': { en: 'CANVAS', zh: '画布' },
  'pane.properties': { en: 'PROPERTIES', zh: '属性' },
  'canvas.appOnlyOn': { en: 'APP ONLY ON', zh: '仅应用 开' },
  'canvas.appOnlyOff': { en: 'APP ONLY OFF', zh: '仅应用 关' },
  'canvas.smallHits': { en: 'SMALL HITS', zh: '小面积优先' },
  'canvas.zOrderHits': { en: 'Z-ORDER HITS', zh: 'Z 序优先' },
  'canvas.noLiveFrame': { en: 'No live frame', zh: '无实时画面' },
  'canvas.zoomIn': { en: 'Zoom in preview', zh: '放大预览' },
  'canvas.zoomOut': { en: 'Zoom out preview', zh: '缩小预览' },
  'window.title': { en: 'Window', zh: '窗口' },
  'window.select': { en: 'Select window', zh: '选择窗口' },
  'view.showIds': { en: 'Show layout IDs', zh: '显示布局 ID' },
  'view.hideIndices': { en: 'Hide hierarchy indices', zh: '隐藏层级索引' },
  'view.hideInvisible': { en: 'Hide invisible views in hierarchy', zh: '隐藏层级结构中的不可见视图' },
  'hidden.summary': { en: 'Hidden {0} · Clear', zh: '已隐藏 {0} · 清除' },
  'metrics.summary': { en: '{0} nodes · depth {1} · width {2}', zh: '{0} 个节点 · 深度 {1} · 最大宽度 {2}' },

  'section.renderRisks': { en: 'RENDER RISKS', zh: '渲染风险' },
  'section.identity': { en: 'IDENTITY', zh: '标识' },
  'section.layout': { en: 'LAYOUT', zh: '布局' },
  'section.drawing': { en: 'DRAWING', zh: '绘制' },
  'section.interaction': { en: 'INTERACTION', zh: '交互' },
  'section.rawProperties': { en: 'RAW PROPERTIES', zh: '原始属性' },

  'label.overdrawEstimate': { en: 'Overdraw estimate', zh: '过度绘制估算' },
  'label.subtreeComplexity': { en: 'Subtree complexity', zh: '子树复杂度' },
  'label.hiddenDescendants': { en: 'Hidden descendants', zh: '隐藏后代' },
  'label.blending': { en: 'Blending', zh: '混合' },
  'label.layerCost': { en: 'Layer cost', zh: '图层成本' },
  'label.class': { en: 'Class', zh: '类' },
  'label.id': { en: 'ID', zh: 'ID' },
  'label.resource': { en: 'Resource', zh: '资源' },
  'label.text': { en: 'Text', zh: '文本' },
  'label.contentDescription': { en: 'Content description', zh: '内容描述' },
  'label.semanticsRole': { en: 'Semantics role', zh: '语义角色' },
  'label.bounds': { en: 'Bounds', zh: '边界' },
  'label.size': { en: 'Size', zh: '尺寸' },
  'label.localLayoutBounds': { en: 'Local layout bounds', zh: '本地布局边界' },
  'label.localLayoutSize': { en: 'Local layout size', zh: '本地布局尺寸' },
  'label.visibility': { en: 'Visibility', zh: '可见性' },
  'label.treeDepth': { en: 'Tree depth', zh: '树深度' },
  'label.directChildren': { en: 'Direct children', zh: '直接子节点' },
  'label.descendants': { en: 'Descendants', zh: '后代节点' },
  'label.subtreeDepth': { en: 'Subtree depth', zh: '子树深度' },
  'label.layoutWidth': { en: 'Layout width', zh: '布局宽度' },
  'label.layoutHeight': { en: 'Layout height', zh: '布局高度' },
  'label.layoutParamsClass': { en: 'Layout params class', zh: '布局参数类' },
  'label.measuredSize': { en: 'Measured size', zh: '测量尺寸' },
  'label.minimumSize': { en: 'Minimum size', zh: '最小尺寸' },
  'label.padding': { en: 'Padding', zh: '内边距' },
  'label.margin': { en: 'Margin', zh: '外边距' },
  'label.scroll': { en: 'Scroll', zh: '滚动偏移' },
  'label.layoutRequested': { en: 'Layout requested', zh: '请求布局' },
  'label.alpha': { en: 'Alpha', zh: '透明度' },
  'label.z': { en: 'Z', zh: 'Z' },
  'label.elevation': { en: 'Elevation', zh: '高度' },
  'label.translation': { en: 'Translation', zh: '位移' },
  'label.rotation': { en: 'Rotation', zh: '旋转' },
  'label.scale': { en: 'Scale', zh: '缩放' },
  'label.pivot': { en: 'Pivot', zh: '轴心' },
  'label.background': { en: 'Background', zh: '背景' },
  'label.backgroundColor': { en: 'Background color', zh: '背景色' },
  'label.foreground': { en: 'Foreground', zh: '前景' },
  'label.clipBounds': { en: 'Clip bounds', zh: '裁剪边界' },
  'label.clipChildren': { en: 'Clip children', zh: '裁剪子节点' },
  'label.clipToPadding': { en: 'Clip to padding', zh: '裁剪到内边距' },
  'label.opaque': { en: 'Opaque', zh: '不透明' },
  'label.willNotDraw': { en: 'Will not draw', zh: '不执行绘制' },
  'label.hardwareAccelerated': { en: 'Hardware accelerated', zh: '硬件加速' },
  'label.layerType': { en: 'Layer type', zh: '图层类型' },
  'label.enabled': { en: 'Enabled', zh: '启用' },
  'label.clickable': { en: 'Clickable', zh: '可点击' },
  'label.longClickable': { en: 'Long clickable', zh: '可长按' },
  'label.focusable': { en: 'Focusable', zh: '可聚焦' },
  'label.focused': { en: 'Focused', zh: '已聚焦' },
  'label.selected': { en: 'Selected', zh: '已选中' },

  'value.noOverlapPairs': { en: 'No high-overlap child pairs · structural', zh: '无高重叠子节点对 · 结构估算' },
  'value.overlapPairs': { en: '{0} pairs · max {1}% · structural', zh: '{0} 对 · 最大 {1}% · 结构估算' },
  'value.overlapPairSingle': { en: '{0} pair · max {1}% · structural', zh: '{0} 对 · 最大 {1}% · 结构估算' },
  'value.subtreeComplexity': { en: '{0} descendants · depth {1}', zh: '{0} 个后代 · 深度 {1}' },
  'value.blendingAlpha': { en: 'Alpha {0} requires blending', zh: 'Alpha {0} 需要混合' },
  'value.alphaOne': { en: 'Alpha 1.0', zh: 'Alpha 1.0' },
  'value.unavailable': { en: 'Unavailable', zh: '不可用' },
  'value.dash': { en: '—', zh: '—' },
} as const;

export type LayoutTextKey = keyof typeof LAYOUT_STRINGS;

/** Fills {0}, {1} … the way the Compose resources fill %1$s, %2$s … */
export function layoutText(key: LayoutTextKey, language: UiLanguage, ...args: ReadonlyArray<string | number>): string {
  const entry = LAYOUT_STRINGS[key] as Record<UiLanguage, string> | undefined;
  if (entry === undefined) return key;
  const template = entry[language] ?? entry.en;
  return template.replace(/\{(\d+)\}/g, (match, index: string) => {
    const value = args[Number(index)];
    return value === undefined ? match : String(value);
  });
}

/** A missing protocol field renders as a dash, never as an empty row. */
export function layoutValue(value: string | number | boolean | undefined, language: UiLanguage): string {
  if (value === undefined) return layoutText('value.dash', language);
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  return String(value);
}
