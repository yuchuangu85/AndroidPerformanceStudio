/**
 * Main-process ownership for the single live Method Recording operation.
 *
 * Stopping is deliberately cooperative: the capture owner observes the signal,
 * issues `am profile stop`, and retains ownership of trace flush/pull/cleanup.
 */
export interface MethodRecordingStopSignal {
  shouldStop(): boolean;
}

interface ActiveMethodRecording extends MethodRecordingStopSignal {
  stopRequested: boolean;
}

export class MethodRecordingLifecycle {
  private active: ActiveMethodRecording | undefined;

  /** Starts the one recording this desktop process may own, or rejects overlap. */
  begin(): MethodRecordingStopSignal | undefined {
    if (this.active !== undefined) return undefined;
    const active: ActiveMethodRecording = {
      stopRequested: false,
      shouldStop: () => active.stopRequested,
    };
    this.active = active;
    return active;
  }

  /** Requests an early, normal finalization of the active recording. */
  requestStop(): boolean {
    if (this.active === undefined) return false;
    this.active.stopRequested = true;
    return true;
  }

  /** Releases only the operation that acquired this particular signal. */
  finish(signal: MethodRecordingStopSignal): void {
    if (this.active === signal) this.active = undefined;
  }
}
