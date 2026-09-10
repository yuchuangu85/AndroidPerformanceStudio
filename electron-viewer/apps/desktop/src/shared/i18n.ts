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
