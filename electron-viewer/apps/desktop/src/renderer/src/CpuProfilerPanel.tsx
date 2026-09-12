import { useCallback, useEffect, useMemo, useState, type JSX } from 'react';
import type { FrameImplementation, ImplementationFilter } from '@aps/profile-analysis';
import type {
  CallGraphMode,
  CpuProfileFlameGraph,
  CpuProfileFlameNode,
  CpuProfileSessionRecord,
  CpuTransformRequest,
  EventScope,
} from '@aps/simpleperf-profiler';
import type { DeviceSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';
import type { SimpleperfSettings } from '../../shared/settings-contract';
import { FlameGraph, flamePathTo, nodesByIndex } from './FlameGraph';

export interface CpuProfilerPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
  /** Stored Simpleperf defaults; the settings page owns them. */
  readonly settings: SimpleperfSettings;
}

const CALL_GRAPHS: readonly CallGraphMode[] = ['DWARF', 'FRAME_POINTER', 'NONE'];
const SCOPES: readonly EventScope[] = ['BOTH', 'USER', 'KERNEL'];
const IMPLEMENTATIONS: readonly ImplementationFilter[] = ['ALL', 'SCRIPT', 'NATIVE'];
const EVENTS: readonly string[] = ['cpu-clock', 'cpu-cycles', 'task-clock'];

function formatWeight(value: string): string {
  try {
    return BigInt(value).toLocaleString('en-US');
  } catch {
    return value;
  }
}

function percentOf(weight: string, total: string): string {
  try {
    const totalValue = BigInt(total);
    if (totalValue === 0n) return '0.0%';
    return ((Number(BigInt(weight)) / Number(totalValue)) * 100).toFixed(1) + '%';
  } catch {
    return '-';
  }
}

interface Aggregate {
  readonly functionId: string;
  readonly symbolName: string;
  readonly resource: string;
  readonly implementation: FrameImplementation;
  value: bigint;
  samples: number;
}

function topNodes(
  nodes: readonly CpuProfileFlameNode[],
  by: 'INCLUSIVE' | 'SELF',
  limit = 15,
): readonly Aggregate[] {
  const grouped = new Map<string, Aggregate>();
  nodes.forEach((node) => {
    const weight = BigInt(by === 'SELF' ? node.selfWeight : node.inclusiveWeight);
    if (weight === 0n) return;
    const existing = grouped.get(node.functionId);
    if (existing === undefined) {
      grouped.set(node.functionId, {
        functionId: node.functionId,
        symbolName: node.symbolName,
        resource: node.resource,
        implementation: node.implementation,
        value: weight,
        samples: Number(node.sampleCount),
      });
      return;
    }
    existing.value += weight;
    existing.samples += Number(node.sampleCount);
  });
  // A function can appear at several depths, so sum per function and rank once.
  return [...grouped.values()]
    .sort((left, right) => (left.value === right.value ? 0 : left.value > right.value ? -1 : 1))
    .slice(0, limit);
}

export function CpuProfilerPanel({ language, devices, settings }: CpuProfilerPanelProps): JSX.Element {
  const defaults = settings.captureDefaults;
  const [sessions, setSessions] = useState<readonly CpuProfileSessionRecord[]>([]);
  const [serial, setSerial] = useState('');
  const [packageName, setPackageName] = useState('');
  const [target, setTarget] = useState<'APP' | 'SYSTEM_WIDE'>(defaults.target);
  const [event, setEvent] = useState(defaults.event);
  const [frequencyHertz, setFrequencyHertz] = useState(defaults.frequencyHertz);
  const [durationSeconds, setDurationSeconds] = useState(defaults.durationSeconds);
  const [callGraph, setCallGraph] = useState<CallGraphMode>(defaults.callGraph);
  const [scope, setScope] = useState<EventScope>(defaults.scope);
  const [selectedId, setSelectedId] = useState('');
  const [threadKey, setThreadKey] = useState('');
  const [searchText, setSearchText] = useState('');
  const [implementation, setImplementation] = useState<ImplementationFilter>('ALL');
  const [direction, setDirection] = useState<'FORWARD' | 'INVERTED'>('FORWARD');
  const [transforms, setTransforms] = useState<readonly CpuTransformRequest[]>([]);
  const [rankBy, setRankBy] = useState<'INCLUSIVE' | 'SELF'>('INCLUSIVE');
  const [graph, setGraph] = useState<CpuProfileFlameGraph | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.listCpuProfiles().then((records) => {
      setSessions(records);
      setSelectedId((current) => (current.length > 0 ? current : records[0]?.id ?? ''));
    });
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (serial.length === 0 && devices.length > 0) setSerial(devices[0]?.serial ?? '');
  }, [devices, serial]);

  // A template or a capture default saved in Settings fills this form. The
  // effect watches the values, not the object, so an unrelated settings write
  // never overwrites a capture the user is configuring here.
  useEffect(() => {
    setTarget(defaults.target);
    setEvent(defaults.event);
    setFrequencyHertz(defaults.frequencyHertz);
    setDurationSeconds(defaults.durationSeconds);
    setCallGraph(defaults.callGraph);
    setScope(defaults.scope);
  }, [
    defaults.callGraph,
    defaults.durationSeconds,
    defaults.event,
    defaults.frequencyHertz,
    defaults.scope,
    defaults.target,
  ]);

  useEffect(() => {
    if (selectedId.length === 0) {
      setGraph(null);
      return;
    }
    window.aps
      .cpuSnapshot({
        id: selectedId,
        ...(threadKey.length > 0 ? { threadKey } : {}),
        searchText,
        implementation,
        direction,
        transforms,
      })
      .then((outcome) => {
        if (!outcome.ok) {
          setMessage(outcome.error ?? 'snapshot failed');
          setGraph(null);
          return;
        }
        setGraph(outcome.graph ?? null);
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [direction, implementation, searchText, selectedId, threadKey, transforms]);

  const capture = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .captureCpuProfile({
        serial,
        ...(packageName.length > 0 ? { packageName } : {}),
        target,
        event,
        frequencyHertz,
        durationSeconds,
        callGraph,
        scope,
      })
      .then((outcome) => {
        setMessage(
          outcome.ok
            ? translate('cpu.complete', language)
            : translate('cpu.failed', language) + ': ' + String(outcome.error ?? ''),
        );
        if (outcome.ok && outcome.id !== undefined) {
          setSelectedId(outcome.id);
          setThreadKey('');
          setTransforms([]);
        }
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [
    callGraph,
    durationSeconds,
    event,
    frequencyHertz,
    language,
    packageName,
    refresh,
    scope,
    serial,
    target,
  ]);

  const importProfile = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .importCpuProfile()
      .then((result) => {
        setMessage(
          result.ok
            ? translate('cpu.imported', language)
            : translate('cpu.importFailed', language) + ': ' + String(result.error ?? ''),
        );
        if (result.ok && result.id !== undefined) {
          setSelectedId(result.id);
          setThreadKey('');
          setTransforms([]);
        }
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [language, refresh]);

  const session = useMemo(
    () => sessions.find((record) => record.id === selectedId),
    [selectedId, sessions],
  );
  const nodeByIndex = useMemo(() => (graph === null ? new Map() : nodesByIndex(graph)), [graph]);
  const ranked = useMemo(() => (graph === null ? [] : topNodes(graph.nodes, rankBy)), [graph, rankBy]);
  const focus = useCallback(
    (node: CpuProfileFlameNode) => {
      setTransforms((current) => [
        ...current,
        { kind: 'FOCUS_CALL_NODE', path: flamePathTo(node, nodeByIndex) },
      ]);
    },
    [nodeByIndex],
  );

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('cpu.capture', language)}</h3>
        <div className="form">
          <label className="field">
            <span>{translate('trace.device', language)}</span>
            <select value={serial} onChange={(input) => setSerial(input.target.value)}>
              {devices.length === 0 ? <option value="">{translate('trace.noDevice', language)}</option> : null}
              {devices.map((device) => (
                <option key={device.serial} value={device.serial}>
                  {device.serial}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('cpu.target', language)}</span>
            <select
              value={target}
              onChange={(input) => setTarget(input.target.value === 'SYSTEM_WIDE' ? 'SYSTEM_WIDE' : 'APP')}
            >
              <option value="APP">{translate('cpu.target.app', language)}</option>
              <option value="SYSTEM_WIDE">{translate('cpu.target.system', language)}</option>
            </select>
          </label>
          <label className="field">
            <span>{translate('frame.package', language)}</span>
            <input
              type="text"
              value={packageName}
              placeholder="com.example.app"
              disabled={target === 'SYSTEM_WIDE'}
              onChange={(input) => setPackageName(input.target.value)}
            />
          </label>
          <label className="field">
            <span>{translate('cpu.event', language)}</span>
            <select value={event} onChange={(input) => setEvent(input.target.value)}>
              {EVENTS.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('cpu.frequency', language)}</span>
            <input
              type="number"
              min={1}
              max={100000}
              value={frequencyHertz}
              onChange={(input) => setFrequencyHertz(Number(input.target.value))}
            />
          </label>
          <label className="field">
            <span>{translate('cpu.duration', language)}</span>
            <input
              type="number"
              min={1}
              max={600}
              value={durationSeconds}
              onChange={(input) => setDurationSeconds(Number(input.target.value))}
            />
          </label>
          <label className="field">
            <span>{translate('cpu.callGraph', language)}</span>
            <select value={callGraph} onChange={(input) => setCallGraph(input.target.value as CallGraphMode)}>
              {CALL_GRAPHS.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('cpu.scope', language)}</span>
            <select value={scope} onChange={(input) => setScope(input.target.value as EventScope)}>
              {SCOPES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="button"
            disabled={busy || serial.length === 0 || (target === 'APP' && packageName.trim().length === 0)}
            onClick={capture}
          >
            {busy ? translate('cpu.capturing', language) : translate('cpu.captureAction', language)}
          </button>
          <button type="button" className="button" disabled={busy} onClick={importProfile}>
            {translate('cpu.importAction', language)}
          </button>
        </div>
        {message !== null ? <p className="card__muted">{message}</p> : null}
        <p className="card__muted">{translate('cpu.importNote', language)}</p>
        <p className="card__muted">{translate('cpu.disclaimer', language)}</p>
      </section>

      <section className="card">
        <h3 className="card__title">{translate('cpu.sessions', language)}</h3>
        {sessions.length === 0 ? (
          <p className="card__muted">{translate('cpu.none', language)}</p>
        ) : (
          <div className="form">
            <label className="field">
              <span>{translate('cpu.session', language)}</span>
              <select
                value={selectedId}
                onChange={(input) => {
                  setSelectedId(input.target.value);
                  setThreadKey('');
                  setTransforms([]);
                }}
              >
                {sessions.map((record) => (
                  <option key={record.id} value={record.id}>
                    {new Date(record.capturedAtEpochMillis).toLocaleString()} · {record.parameters.target} ·{' '}
                    {String(record.sampleCount)} samples
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>{translate('cpu.thread', language)}</span>
              <select value={threadKey} onChange={(input) => setThreadKey(input.target.value)}>
                <option value="">{translate('cpu.thread.all', language)}</option>
                {(session?.threadKeys ?? []).map((key) => (
                  <option key={key} value={key}>
                    {key}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>{translate('cpu.search', language)}</span>
              <input
                type="text"
                value={searchText}
                placeholder={translate('cpu.search.placeholder', language)}
                onChange={(input) => setSearchText(input.target.value)}
              />
            </label>
            <label className="field">
              <span>{translate('cpu.implementation', language)}</span>
              <select
                value={implementation}
                onChange={(input) => setImplementation(input.target.value as ImplementationFilter)}
              >
                {IMPLEMENTATIONS.map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>{translate('cpu.direction', language)}</span>
              <select
                value={direction}
                onChange={(input) => setDirection(input.target.value === 'INVERTED' ? 'INVERTED' : 'FORWARD')}
              >
                <option value="FORWARD">FORWARD</option>
                <option value="INVERTED">INVERTED</option>
              </select>
            </label>
            <button
              type="button"
              className="button"
              disabled={transforms.length === 0}
              onClick={() => setTransforms([])}
            >
              {translate('cpu.reset', language)}
            </button>
          </div>
        )}
        {transforms.length > 0 ? (
          <p className="card__muted">
            {translate('cpu.focused', language)}: {transforms.length}
          </p>
        ) : null}
        {graph !== null && graph.invalidTransforms.length > 0 ? (
          <p className="card__error">
            {translate('cpu.invalidTransforms', language)}: {graph.invalidTransforms.join(', ')}
          </p>
        ) : null}
      </section>

      {graph === null ? (
        <section className="card">
          <p className="card__muted">{translate('cpu.noneSelected', language)}</p>
        </section>
      ) : (
        <>
          <section className="card">
            <h3 className="card__title">{translate('cpu.flameGraph', language)}</h3>
            <p className="card__muted">
              {translate('cpu.totalWeight', language)}: {formatWeight(graph.totalWeight)} ·{' '}
              {translate('cpu.stacks', language)}: {String(graph.sourceStackCount)} ·{' '}
              {translate('cpu.nodes', language)}: {String(graph.nodeCount)}
            </p>
            {graph.nodes.length === 0 ? (
              <p className="card__muted">
                {translate('cpu.empty', language)}: {graph.emptyReason ?? 'UNKNOWN'}
              </p>
            ) : (
              <FlameGraph
                graph={graph}
                language={language}
                tooltipMode={settings.flameTooltipMode}
                onFocus={focus}
              />
            )}
          </section>

          <section className="card">
            <h3 className="card__title">{translate('cpu.topFunctions', language)}</h3>
            <div className="form">
              <label className="field">
                <span>{translate('cpu.rankBy', language)}</span>
                <select
                  value={rankBy}
                  onChange={(input) => setRankBy(input.target.value === 'SELF' ? 'SELF' : 'INCLUSIVE')}
                >
                  <option value="INCLUSIVE">{translate('cpu.inclusive', language)}</option>
                  <option value="SELF">{translate('cpu.self', language)}</option>
                </select>
              </label>
            </div>
            <table className="runs">
              <thead>
                <tr>
                  <th>{translate('memory.class', language)}</th>
                  <th>{translate('cpu.resource', language)}</th>
                  <th>{translate('cpu.weight', language)}</th>
                  <th>{translate('cpu.share', language)}</th>
                </tr>
              </thead>
              <tbody>
                {ranked.map((entry) => (
                  <tr key={entry.functionId}>
                    <td>
                      <code>{entry.symbolName}</code>
                    </td>
                    <td>
                      <code>{entry.resource}</code>
                    </td>
                    <td>{formatWeight(entry.value.toString())}</td>
                    <td>{percentOf(entry.value.toString(), graph.totalWeight)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          {graph.nodes.length > 0 ? (
            <section className="card">
              <h3 className="card__title">{translate('cpu.legend', language)}</h3>
              <p className="card__muted">
                {(['NATIVE', 'MANAGED', 'KERNEL', 'UNKNOWN'] as readonly FrameImplementation[])
                  .map((value) => value)
                  .join(' · ')}
              </p>
              <p className="card__muted">{translate('cpu.clickHint', language)}</p>
            </section>
          ) : null}
        </>
      )}
    </>
  );
}