export type BatteryCaptureMode = 'INTERACTIVE' | 'TIMED' | 'REPEATED' | 'ONLINE';
export type BatterySessionStatus = 'RUNNING' | 'COMPLETED' | 'INTERRUPTED';
export type AttributionScope = 'PACKAGE' | 'UID' | 'SHARED_UID' | 'DEVICE';
export type EvidenceConfidence = 'EXACT' | 'MODELED' | 'INFERRED' | 'UNAVAILABLE';
export type EnergyEvidenceKind = 'HARDWARE_COUNTER' | 'SYSTEM_MODEL' | 'UID_COUNTER' | 'HISTORY_CORRELATION' | 'DEVICE_STATE' | 'UNAVAILABLE';
export type BatteryHistoryEventKind =
  | 'WAKELOCK'
  | 'ALARM'
  | 'JOB'
  | 'SENSOR'
  | 'NETWORK'
  | 'APP_STATE'
  | 'SCREEN'
  | 'CHARGING'
  | 'THERMAL'
  | 'UNKNOWN';

export interface BatteryExperimentConfig {
  readonly mode: BatteryCaptureMode;
  readonly durationSeconds: number;
  readonly pollingIntervalSeconds: number;
  readonly measuredRuns: number;
  readonly launchApp: boolean;
  readonly cooldownSeconds: number;
}

export const DEFAULT_BATTERY_EXPERIMENT: BatteryExperimentConfig = {
  mode: 'INTERACTIVE',
  durationSeconds: 60,
  pollingIntervalSeconds: 10,
  measuredRuns: 1,
  launchApp: false,
  cooldownSeconds: 30,
};

/** Returns validation errors; an empty list means the configuration is usable. */
export function validateBatteryExperimentConfig(config: BatteryExperimentConfig): string[] {
  const errors: string[] = [];
  if (config.durationSeconds < 5 || config.durationSeconds > 3600) {
    errors.push('durationSeconds must be between 5 and 3600');
  }
  if (config.pollingIntervalSeconds < 5 || config.pollingIntervalSeconds > 60) {
    errors.push('pollingIntervalSeconds must be between 5 and 60');
  }
  if (config.measuredRuns < 1 || config.measuredRuns > 50) {
    errors.push('measuredRuns must be between 1 and 50');
  }
  if (config.cooldownSeconds < 0 || config.cooldownSeconds > 300) {
    errors.push('cooldownSeconds must be between 0 and 300');
  }
  return errors;
}

export interface ResourceTimer {
  readonly name: string;
  readonly durationMs: number;
  readonly count: number;
  readonly confidence: EvidenceConfidence;
}

export interface NetworkUsage {
  readonly mobileRxBytes: number;
  readonly mobileTxBytes: number;
  readonly wifiRxBytes: number;
  readonly wifiTxBytes: number;
  readonly bluetoothRxBytes: number;
  readonly bluetoothTxBytes: number;
  readonly mobileRxPackets: number;
  readonly mobileTxPackets: number;
  readonly wifiRxPackets: number;
  readonly wifiTxPackets: number;
  readonly mobileRadioActiveMs: number;
}

export const EMPTY_NETWORK_USAGE: NetworkUsage = {
  mobileRxBytes: 0,
  mobileTxBytes: 0,
  wifiRxBytes: 0,
  wifiTxBytes: 0,
  bluetoothRxBytes: 0,
  bluetoothTxBytes: 0,
  mobileRxPackets: 0,
  mobileTxPackets: 0,
  wifiRxPackets: 0,
  wifiTxPackets: 0,
  mobileRadioActiveMs: 0,
};

export function totalNetworkBytes(usage: NetworkUsage): number {
  return (
    usage.mobileRxBytes +
    usage.mobileTxBytes +
    usage.wifiRxBytes +
    usage.wifiTxBytes +
    usage.bluetoothRxBytes +
    usage.bluetoothTxBytes
  );
}

export interface EnergyEstimate {
  readonly component: string;
  readonly energyMah?: number;
  readonly energyUws?: number;
  readonly source: EnergyEvidenceKind;
  readonly attributionScope: AttributionScope;
  readonly confidence: EvidenceConfidence;
}

export interface UidBatteryStats {
  readonly uid: number;
  readonly wakelocks: Readonly<Record<string, ResourceTimer>>;
  readonly alarms: Readonly<Record<string, ResourceTimer>>;
  readonly jobs: Readonly<Record<string, ResourceTimer>>;
  readonly sensors: Readonly<Record<string, ResourceTimer>>;
  readonly network: NetworkUsage;
  readonly energy: Readonly<Record<string, EnergyEstimate>>;
}

export interface BatteryHistoryEvent {
  readonly elapsedMs?: number;
  readonly kind: BatteryHistoryEventKind;
  readonly active?: boolean;
  readonly name?: string;
  readonly uid?: number;
  readonly raw: string;
  readonly confidence: EvidenceConfidence;
}

export interface BatteryDeviceState {
  readonly levelPercent?: number;
  readonly temperatureTenthsCelsius?: number;
  readonly voltageMillivolts?: number;
  readonly powered?: boolean;
  readonly status?: string;
  readonly rawValues: Readonly<Record<string, string>>;
}

export interface BatterySnapshot {
  readonly id: string;
  readonly sessionId: string;
  readonly sequence: number;
  readonly capturedAtEpochMillis: number;
  readonly uidStats: UidBatteryStats;
  readonly deviceState: BatteryDeviceState;
  readonly history: readonly BatteryHistoryEvent[];
  readonly warnings: readonly string[];
  readonly statsPeriodId?: string;
  readonly bootId?: string;
  readonly conditions: Readonly<Record<string, string>>;
}

export interface BatteryRun {
  readonly id: string;
  readonly sessionId: string;
  readonly iteration: number;
  readonly baseline: BatterySnapshot;
  readonly samples: readonly BatterySnapshot[];
  readonly finalSnapshot: BatterySnapshot;
}

export interface BatteryRunDelta {
  readonly runId: string;
  readonly sessionId: string;
  readonly iteration: number;
  readonly durationMs: number;
  readonly wakelocks: readonly ResourceTimer[];
  readonly alarms: readonly ResourceTimer[];
  readonly jobs: readonly ResourceTimer[];
  readonly sensors: readonly ResourceTimer[];
  readonly network: NetworkUsage;
  readonly energy: readonly EnergyEstimate[];
  readonly history: readonly BatteryHistoryEvent[];
  readonly warnings: readonly string[];
}

export interface BatteryStatistics {
  readonly count: number;
  readonly missingCount: number;
  readonly minimum?: number;
  readonly maximum?: number;
  readonly median?: number;
  readonly mean?: number;
  readonly p90?: number;
  readonly p95?: number;
  readonly standardDeviation?: number;
  readonly medianAbsoluteDeviation?: number;
}
