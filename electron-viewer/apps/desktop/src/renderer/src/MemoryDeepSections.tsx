/**
 * The memory sections that need the parsed heap or a second capture: deep
 * reports, instance browsing, bitmap dumps, and heapprofd traces.
 *
 * Instance browsing reads the heap retained for this run, because the raw dump
 * is deleted after capture. The section says so instead of showing an empty list
 * that looks like "no instances".
 */
import { useCallback, useEffect, useState, type JSX } from 'react';
import type { MemorySession } from '@aps/memory-profiler';
import type {
  BitmapDumpSession,
  BitmapSessionSummary,
  DeviceSummary,
  InstanceQueryDetail,
  InstanceQueryRow,
  NativeHeapCaptureRecord,
  NativeHeapSessionSummary,
} from '../../shared/ipc';
import type { UiLanguage } from '../../shared/i18n';

const STRINGS = {
  deep: { en: 'Deep analysis', zh: '深度分析' },
  deepSuspects: { en: 'Retention signals', zh: '持有信号' },
  activityLeaks: { en: 'Activity leaks', zh: 'Activity 泄漏' },
  bitmaps: { en: 'Bitmaps', zh: 'Bitmap' },
  instances: { en: 'Instances', zh: '实例' },
  noDeep: { en: 'No deep reports in this session', zh: '该会话没有深度分析结果' },
  reason: { en: 'Reason', zh: '原因' },
  className: { en: 'Class', zh: '类' },
  retained: { en: 'Retained', zh: '保留' },
  chain: { en: 'Chain', zh: '引用链' },
  live: { en: 'Live', zh: '存活' },
  destroyed: { en: 'Destroyed', zh: '已销毁' },
  size: { en: 'Size', zh: '尺寸' },
  pixels: { en: 'Pixels', zh: '像素' },
  field: { en: 'Field', zh: '字段' },
  value: { en: 'Value', zh: '值' },
  query: { en: 'Find instances', zh: '查询实例' },
  instanceHint: {
    en: 'Instance browsing works for the session captured in this run; the raw dump is not kept.',
    zh: '实例浏览只对本次运行中抓取的会话有效，原始 dump 不会保留。',
  },
  bitmapTitle: { en: 'Bitmap dump', zh: 'Bitmap 抓取' },
  bitmapHint: {
    en: 'Android 15 (API 35) writes every Bitmap payload as a PNG; the gallery is that payload.',
    zh: 'Android 15（API 35）会把每个 Bitmap 载荷写成 PNG，图库就是这些载荷。',
  },
  bitmapCapture: { en: 'Capture bitmaps', zh: '抓取 Bitmap' },
  noBitmaps: { en: 'No bitmap dump yet', zh: '还没有 Bitmap 抓取' },
  unique: { en: 'unique', zh: '去重后' },
  nativeTitle: { en: 'Native heap (heapprofd)', zh: 'Native 堆（heapprofd）' },
  nativeHint: {
    en: 'Requires Android 10+ and a debuggable build. The raw trace stays authoritative.',
    zh: '需要 Android 10+ 与 debuggable 构建。原始 trace 仍是权威产物。',
  },
  nativeCapture: { en: 'Capture native heap', zh: '抓取 Native 堆' },
  noNative: { en: 'No native heap trace yet', zh: '还没有 Native 堆 trace' },
  function: { en: 'Function', zh: '函数' },
  allocated: { en: 'Allocated', zh: '分配' },
  freed: { en: 'Freed', zh: '释放' },
  samples: { en: 'samples', zh: '样本' },
  remove: { en: 'Remove', zh: '移除' },
} as const;

type StringKey = keyof typeof STRINGS;

function t(key: StringKey, language: UiLanguage): string {
  return STRINGS[key][language];
}

function formatBytes(value: number): string {
  if (value < 1024) return String(value) + ' B';
  if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KB';
  return (value / (1024 * 1024)).toFixed(2) + ' MB';
}

function chainText(chain: readonly { readonly fieldName: string; readonly className: string }[]): string {
  return chain.map((step) => step.fieldName + ' -> ' + step.className).join(' / ');
}

export interface MemoryDeepPanelProps {
  readonly language: UiLanguage;
  readonly sessionId: string;
  readonly session: MemorySession | null;
}

export function MemoryDeepPanel({ language, sessionId, session }: MemoryDeepPanelProps): JSX.Element {
  const [className, setClassName] = useState('');
  const [rows, setRows] = useState<readonly InstanceQueryRow[]>([]);
  const [detail, setDetail] = useState<InstanceQueryDetail | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const query = useCallback(() => {
    if (sessionId.length === 0 || className.trim().length === 0) {
      setRows([]);
      return;
    }
    window.aps
      .queryMemoryInstances({ sessionId, className: className.trim(), limit: 200 })
      .then(setRows)
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [className, sessionId]);

  if (session === null) return <></>;
  const deep = session.deep;

  return (
    <>
      <section className="card">
        <h3 className="card__title">{t('deep', language)}</h3>
        {deep === undefined ? (
          <p className="card__muted">{t('noDeep', language)}</p>
        ) : (
          <>
            <h4 className="card__title">{t('deepSuspects', language)}</h4>
            {deep.suspects.length === 0 ? (
              <p className="card__muted">{t('deep', language)}</p>
            ) : (
              <table className="runs">
                <thead>
                  <tr>
                    <th>{t('className', language)}</th>
                    <th>{t('reason', language)}</th>
                    <th>{t('retained', language)}</th>
                    <th>{t('chain', language)}</th>
                  </tr>
                </thead>
                <tbody>
                  {deep.suspects.map((suspect) => (
                    <tr key={suspect.className + suspect.reason}>
                      <td>{suspect.className}</td>
                      <td>{suspect.reason}</td>
                      <td>{formatBytes(suspect.retainedBytes)}</td>
                      <td>{chainText(suspect.referenceChain)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <h4 className="card__title">{t('activityLeaks', language)}</h4>
            {deep.activityLeaks.length === 0 ? (
              <p className="card__muted">{t('noDeep', language)}</p>
            ) : (
              <table className="runs">
                <thead>
                  <tr>
                    <th>{t('className', language)}</th>
                    <th>{t('live', language)}</th>
                    <th>{t('destroyed', language)}</th>
                    <th>{t('retained', language)}</th>
                  </tr>
                </thead>
                <tbody>
                  {deep.activityLeaks.map((entry) => (
                    <tr key={entry.className}>
                      <td>{entry.className}</td>
                      <td>{String(entry.liveInstanceCount)}</td>
                      <td>{String(entry.destroyedInstanceCount)}</td>
                      <td>{formatBytes(entry.retainedBytes)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <h4 className="card__title">{t('bitmaps', language)}</h4>
            {deep.bitmaps.length === 0 ? (
              <p className="card__muted">{t('noDeep', language)}</p>
            ) : (
              <table className="runs">
                <thead>
                  <tr>
                    <th>{t('size', language)}</th>
                    <th>{t('retained', language)}</th>
                    <th>{t('pixels', language)}</th>
                  </tr>
                </thead>
                <tbody>
                  {deep.bitmaps.map((bitmap) => (
                    <tr key={bitmap.objectId}>
                      <td>{String(bitmap.width ?? 0) + 'x' + String(bitmap.height ?? 0)}</td>
                      <td>{formatBytes(bitmap.retainedBytes)}</td>
                      <td>{bitmap.estimatedPixelBytes === undefined ? '-' : formatBytes(bitmap.estimatedPixelBytes)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </section>

      <section className="card">
        <h3 className="card__title">{t('instances', language)}</h3>
        <p className="card__muted">{t('instanceHint', language)}</p>
        <div className="form">
          <label className="field">
            <span>{t('className', language)}</span>
            <input value={className} onChange={(event) => setClassName(event.target.value)} />
          </label>
          <button type="button" className="button" onClick={query}>
            {t('query', language)}
          </button>
        </div>
        {rows.length === 0 ? null : (
          <table className="runs">
            <thead>
              <tr>
                <th>id</th>
                <th>heap</th>
                <th>{t('size', language)}</th>
                <th>{t('retained', language)}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.objectId}>
                  <td>
                    <button
                      type="button"
                      className="button"
                      onClick={() => {
                        void window.aps
                          .memoryInstanceDetail({ sessionId, objectId: row.objectId })
                          .then((loaded) => setDetail(loaded ?? null));
                      }}
                    >
                      {row.objectId}
                    </button>
                  </td>
                  <td>{row.heap}</td>
                  <td>{formatBytes(row.shallowBytes)}</td>
                  <td>{row.retainedBytes === undefined ? '-' : formatBytes(row.retainedBytes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {detail === null ? null : (
          <table className="runs">
            <thead>
              <tr>
                <th>{t('field', language)}</th>
                <th>{t('value', language)}</th>
              </tr>
            </thead>
            <tbody>
              {detail.fields.map((entry) => (
                <tr key={entry.name}>
                  <td>{entry.name}</td>
                  <td>{entry.displayValue}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {message !== null ? <p className="card__error">{message}</p> : null}
      </section>
    </>
  );
}

export interface MemoryArtifactSectionsProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

/** Bitmap dumps and native heap traces share the capture/list/load shape. */
export function MemoryArtifactSections({ language, devices }: MemoryArtifactSectionsProps): JSX.Element {
  const [serial, setSerial] = useState('');
  const [packageName, setPackageName] = useState('');
  const [bitmaps, setBitmaps] = useState<readonly BitmapSessionSummary[]>([]);
  const [bitmapId, setBitmapId] = useState('');
  const [bitmap, setBitmap] = useState<BitmapDumpSession | null>(null);
  const [native, setNative] = useState<readonly NativeHeapSessionSummary[]>([]);
  const [nativeId, setNativeId] = useState('');
  const [nativeRecord, setNativeRecord] = useState<NativeHeapCaptureRecord | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    void window.aps.listBitmapSessions().then(setBitmaps);
    void window.aps.listNativeHeapSessions().then(setNative);
  }, []);

  useEffect(refresh, [refresh]);
  useEffect(() => {
    if (serial.length === 0 && devices.length > 0) setSerial(devices[0]?.serial ?? '');
  }, [devices, serial]);
  useEffect(() => {
    if (bitmapId.length === 0) {
      setBitmap(null);
      return;
    }
    void window.aps.loadBitmapSession(bitmapId).then((loaded) => setBitmap(loaded ?? null));
  }, [bitmapId]);
  useEffect(() => {
    if (nativeId.length === 0) {
      setNativeRecord(null);
      return;
    }
    void window.aps.loadNativeHeapSession(nativeId).then((loaded) => setNativeRecord(loaded ?? null));
  }, [nativeId]);

  const run = (body: () => Promise<void>): void => {
    setBusy(true);
    setMessage(null);
    body()
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  };

  return (
    <>
      <section className="card">
        <h3 className="card__title">{t('bitmapTitle', language)}</h3>
        <p className="card__muted">{t('bitmapHint', language)}</p>
        <div className="form">
          <label className="field">
            <span>device</span>
            <select value={serial} onChange={(event) => setSerial(event.target.value)}>
              {devices.map((device) => (
                <option key={device.serial} value={device.serial}>
                  {device.serial}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>package</span>
            <input value={packageName} onChange={(event) => setPackageName(event.target.value)} />
          </label>
          <button
            type="button"
            className="button"
            disabled={busy || serial.length === 0 || packageName.length === 0}
            onClick={() =>
              run(async () => {
                const result = await window.aps.captureBitmapDump({ serial, packageName });
                if (!result.ok) setMessage(result.error ?? 'capture failed');
                refresh();
              })
            }
          >
            {t('bitmapCapture', language)}
          </button>
          <button
            type="button"
            className="button"
            disabled={busy || serial.length === 0 || packageName.length === 0}
            onClick={() =>
              run(async () => {
                const result = await window.aps.captureNativeHeap({ serial, packageName });
                if (!result.ok) setMessage(result.error ?? 'capture failed');
                refresh();
              })
            }
          >
            {t('nativeCapture', language)}
          </button>
        </div>
        <label className="field">
          <span>{t('bitmapTitle', language)}</span>
          <select value={bitmapId} onChange={(event) => setBitmapId(event.target.value)}>
            <option value="">-</option>
            {bitmaps.map((summary) => (
              <option key={summary.id} value={summary.id}>
                {summary.id + ' · ' + String(summary.exportedImageCount) + ' ' + t('bitmaps', language)}
              </option>
            ))}
          </select>
        </label>
        {bitmaps.length === 0 ? <p className="card__muted">{t('noBitmaps', language)}</p> : null}
        {bitmap !== null ? (
          <p className="card__muted">
            {String(bitmap.summary.exportedImageCount) + ' · ' + String(bitmap.summary.uniqueImageCount) + ' ' + t('unique', language) + ' · ' + formatBytes(bitmap.summary.estimatedBitmapBytes)}
          </p>
        ) : null}
        {message !== null ? <p className="card__error">{message}</p> : null}
      </section>

      <section className="card">
        <h3 className="card__title">{t('nativeTitle', language)}</h3>
        <p className="card__muted">{t('nativeHint', language)}</p>
        <label className="field">
          <span>{t('nativeTitle', language)}</span>
          <select value={nativeId} onChange={(event) => setNativeId(event.target.value)}>
            <option value="">-</option>
            {native.map((summary) => (
              <option key={summary.id} value={summary.id}>
                {summary.id + ' · ' + String(summary.sampleCount) + ' ' + t('samples', language)}
              </option>
            ))}
          </select>
        </label>
        {native.length === 0 ? <p className="card__muted">{t('noNative', language)}</p> : null}
        {nativeRecord !== null ? (
          <table className="runs">
            <thead>
              <tr>
                <th>{t('function', language)}</th>
                <th>{t('allocated', language)}</th>
                <th>{t('freed', language)}</th>
              </tr>
            </thead>
            <tbody>
              {nativeRecord.analysis.topAllocations.slice(0, 30).map((sample) => (
                <tr key={sample.functionName}>
                  <td>{sample.functionName}</td>
                  <td>{formatBytes(sample.allocatedBytes)}</td>
                  <td>{formatBytes(sample.freedBytes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>
    </>
  );
}
