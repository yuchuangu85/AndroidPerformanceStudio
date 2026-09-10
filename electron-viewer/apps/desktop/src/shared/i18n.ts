import type { ApplicationLanguagePreference } from './settings-contract.js';

export type UiLanguage = 'en' | 'zh';

export const UI_LANGUAGES: readonly UiLanguage[] = ['en', 'zh'];

/** Shell strings; feature pages keep their own catalogs. */
export const SHELL_STRINGS = {
  'app.name': { en: 'Android Performance Studio', zh: 'Android Performance Studio' },
  'shell.home': { en: 'Home', zh: '首页' },
  'shell.settings': { en: 'Settings', zh: '设置' },
  'shell.language': { en: 'Language', zh: '语言' },
  'shell.theme': { en: 'Theme', zh: '主题' },
  'shell.theme.system': { en: 'System', zh: '跟随系统' },
  'shell.theme.light': { en: 'Light', zh: '浅色' },
  'shell.theme.dark': { en: 'Dark', zh: '深色' },
  'shell.language.system': { en: 'System', zh: '跟随系统' },
  'shell.language.english': { en: 'English', zh: 'English' },
  'shell.language.simplifiedChinese': { en: '简体中文', zh: '简体中文' },
  'shell.devices': { en: 'Devices', zh: '设备' },
  'shell.devices.refresh': { en: 'Refresh', zh: '刷新' },
  'shell.devices.none': { en: 'No authorized device found', zh: '未发现已授权设备' },
  'shell.traceProcessor': { en: 'Trace Processor', zh: 'Trace Processor' },
  'shell.migration.migrated': {
    en: 'Imported settings from the previous desktop app',
    zh: '已从旧桌面应用导入设置',
  },
  'shell.migration.fresh': { en: 'Fresh settings', zh: '全新设置' },
  'shell.phase': { en: 'Electron rewrite in progress', zh: 'Electron 重写进行中' },
  'destination.sourceWorkspaces': { en: 'Source Workspaces', zh: '源码工作区' },
  'destination.layoutInspector': { en: 'Layout Inspector', zh: '布局检查器' },
  'destination.cpuProfiler': { en: 'CPU Profiler', zh: 'CPU 分析器' },
  'destination.traceAnalyzer': { en: 'Trace Analyzer', zh: 'Trace 分析器' },
  'destination.memoryProfiler': { en: 'Memory Profiler', zh: '内存分析器' },
  'destination.frameProfiler': { en: 'Frame Profiler', zh: '帧分析器' },
  'destination.startupProfiler': { en: 'Startup Profiler', zh: '启动分析器' },
  'destination.batteryProfiler': { en: 'Battery Profiler', zh: '电量分析器' },
  'destination.networkProfiler': { en: 'Network Profiler', zh: '网络分析器' },
  'destination.gpuInspector': { en: 'GPU Inspector', zh: 'GPU 检查器' },
  'destination.benchmarkRegression': { en: 'Benchmark Regression', zh: '基准回归' },
  'destination.methodRecording': { en: 'Method Recording', zh: '方法录制' },
  'status.available': { en: 'Available', zh: '可用' },
  'status.unavailable': { en: 'Unavailable', zh: '不可用' },
  'trace.capture': { en: 'Capture system trace', zh: '采集系统 Trace' },
  'trace.device': { en: 'Device', zh: '设备' },
  'trace.noDevice': { en: 'No device', zh: '无设备' },
  'trace.duration': { en: 'Duration (ms)', zh: '时长（毫秒）' },
  'trace.buffer': { en: 'Buffer (KB)', zh: '缓冲区（KB）' },
  'trace.captureAction': { en: 'Capture', zh: '开始采集' },
  'trace.capturing': { en: 'Capturing…', zh: '采集中…' },
  'trace.captured': { en: 'Captured traces', zh: '已采集的 Trace' },
  'trace.none': { en: 'No traces captured yet.', zh: '还没有采集结果。' },
  'trace.open': { en: 'Open', zh: '打开' },
  'trace.reveal': { en: 'Show file', zh: '定位文件' },
  'trace.uiBundled': { en: 'Bundled Perfetto UI', zh: '内置 Perfetto UI' },
  'trace.uiMissing': {
    en: 'Perfetto UI assets are not bundled. Capture still works; use the public UI or show the file.',
    zh: '未内置 Perfetto UI 资源。采集仍可用，可打开公共 UI 或定位文件。',
  },
  'trace.openPublicUi': { en: 'Open ui.perfetto.dev', zh: '打开 ui.perfetto.dev' },
  'trace.complete': { en: 'Capture complete', zh: '采集完成' },
  'trace.failed': { en: 'Capture failed', zh: '采集失败' },
  'trace.loading': { en: 'Loading trace analyzer…', zh: '正在加载 Trace 分析器…' },
  'layout.capture': { en: 'Capture layout', zh: '采集布局' },
  'layout.capturing': { en: 'Capturing…', zh: '采集中…' },
  'layout.captureAction': { en: 'Capture', zh: '开始采集' },
  'layout.captures': { en: 'Layout captures', zh: '布局采集结果' },
  'layout.none': { en: 'No layout captures yet.', zh: '还没有布局采集结果。' },
  'layout.hierarchy': { en: 'Hierarchy', zh: '层级结构' },
  'layout.properties': { en: 'Properties', zh: '属性' },
  'layout.preview': { en: 'Preview', zh: '预览' },
  'layout.loading': { en: 'Loading layout captures…', zh: '正在加载布局采集…' },
  'layout.complete': { en: 'Capture complete', zh: '采集完成' },
  'layout.failed': { en: 'Capture failed', zh: '采集失败' },
  'layout.noSelection': { en: 'Select a node in the hierarchy.', zh: '请在层级结构中选择一个节点。' },
  'layout.hide': { en: 'Hide', zh: '隐藏' },
  'layout.show': { en: 'Show', zh: '显示' },
  'layout.hidden': { en: 'Hidden', zh: '已隐藏' },
  'layout.clearHidden': { en: 'Clear', zh: '清除' },
  'layout.hitOrder': { en: 'Hit order', zh: '命中排序' },
  'layout.orderZ': { en: 'Z-order', zh: 'Z 序' },
  'layout.orderSmallest': { en: 'Smallest area', zh: '小面积优先' },
  'frame.capture': { en: 'Capture frames', zh: '采集帧数据' },
  'frame.package': { en: 'Package', zh: '包名' },
  'frame.captureAction': { en: 'Capture', zh: '开始采集' },
  'frame.capturing': { en: 'Capturing…', zh: '采集中…' },
  'frame.sessions': { en: 'Frame sessions', zh: '帧会话' },
  'frame.none': { en: 'No frame sessions yet.', zh: '还没有帧会话。' },
  'frame.frames': { en: 'Frames', zh: '帧数' },
  'frame.deadlineMiss': { en: 'Deadline misses', zh: '截止时间未命中' },
  'frame.platformJank': { en: 'Platform jank', zh: '平台 Jank' },
  'frame.p50': { en: 'p50', zh: 'p50' },
  'frame.p95': { en: 'p95', zh: 'p95' },
  'frame.p99': { en: 'p99', zh: 'p99' },
  'frame.timeline': { en: 'Frame timeline', zh: '帧时间线' },
  'frame.clusters': { en: 'Deadline-miss clusters', zh: '未命中聚类' },
  'frame.noClusters': { en: 'No deadline misses.', zh: '没有未命中。' },
  'frame.complete': { en: 'Capture complete', zh: '采集完成' },
  'frame.failed': { en: 'Capture failed', zh: '采集失败' },
  'frame.loading': { en: 'Loading frame sessions…', zh: '正在加载帧会话…' },
} as const;

export type ShellStringKey = keyof typeof SHELL_STRINGS;

/** Mirrors UiLanguage.fromLocale: any zh locale resolves to Chinese. */
export function resolveLanguage(
  preference: ApplicationLanguagePreference,
  systemLocale: string,
): UiLanguage {
  if (preference === 'simplified_chinese') return 'zh';
  if (preference === 'english') return 'en';
  return systemLocale.toLowerCase().startsWith('zh') ? 'zh' : 'en';
}

export function translate(key: ShellStringKey, language: UiLanguage): string {
  return SHELL_STRINGS[key][language];
}
