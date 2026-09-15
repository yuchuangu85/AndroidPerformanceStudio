import type { MethodSessionRecord } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

/** Gives imported sessions a truthful filename label and keeps old captures readable. */
export function methodSessionLabel(record: MethodSessionRecord, language: UiLanguage): string {
  if (record.origin === 'IMPORTED') {
    return translate('method.importedTrace', language) + ': ' + (record.sourceFileName ?? record.id);
  }
  return record.packageName ?? record.id;
}

/** Device API is evidence from a live capture and must not be shown for imports. */
export function methodSessionMetadata(record: MethodSessionRecord, language: UiLanguage): string {
  const entries = [translate('method.traceVersion', language) + ': ' + String(record.traceVersion)];
  if (record.deviceSdkApiLevel !== undefined) {
    entries.push(translate('method.api', language) + ': ' + String(record.deviceSdkApiLevel));
  }
  entries.push(translate('method.methods', language) + ': ' + String(record.methodCount));
  entries.push(translate('method.threads', language) + ': ' + String(record.threadCount));
  return entries.join(' · ');
}
