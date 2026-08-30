import { PreferenceCachePort } from "./PreferenceCachePort";
import { StatusRepository } from "./StatusRepository";
import { toCacheSnapshotOrNull, toStaleOutcome } from "../domain/LastKnownDisplayCache";
import { UNCORROBORATED_IDLE_TOLERANCE } from "../domain/Constants";
import {
  isLiveOutcome,
  isPollTransportFailure,
  isUncorroboratedIdle,
  SkillBillStatusOutcome,
  UnavailableReason,
  withPollFailure,
} from "../domain/SkillBillStatusOutcome";

export type OutcomeListener = (outcome: SkillBillStatusOutcome) => void;

export class StatusRefreshCoordinator {
  private activeConsumers = 0;
  private disposed = false;
  private refreshChain: Promise<void> = Promise.resolve();
  private unconfirmedIdleSamples = 0;
  private currentOutcome: SkillBillStatusOutcome | undefined;
  private pollTimer: ReturnType<typeof setInterval> | undefined;
  private readonly listeners = new Set<OutcomeListener>();

  constructor(
    private readonly statusRepository: StatusRepository,
    private readonly preferences: PreferenceCachePort,
    private readonly projectRoot: string,
    private readonly onCancelProcesses: () => void = () => undefined,
  ) {}

  subscribe(listener: OutcomeListener): () => void {
    this.listeners.add(listener);
    if (this.currentOutcome) {
      listener(this.currentOutcome);
    }
    return () => this.listeners.delete(listener);
  }

  addConsumer(): void {
    if (this.disposed) {
      return;
    }
    this.activeConsumers += 1;
    if (this.activeConsumers === 1) {
      this.startPolling();
    }
  }

  removeConsumer(): void {
    this.activeConsumers = Math.max(0, this.activeConsumers - 1);
    if (this.activeConsumers === 0) {
      this.stopPolling();
    }
  }

  requestRefresh(): void {
    if (this.disposed) {
      return;
    }
    void this.refreshOnce();
  }

  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.activeConsumers = 0;
    this.stopPolling();
    this.onCancelProcesses();
    this.listeners.clear();
  }

  private startPolling(): void {
    if (this.pollTimer) {
      return;
    }
    void this.refreshOnce();
    this.scheduleNextPoll();
  }

  private scheduleNextPoll(): void {
    const intervalMs = Math.max(1, this.preferences.getRefreshIntervalSeconds()) * 1000;
    this.pollTimer = setInterval(() => {
      if (this.disposed || this.activeConsumers <= 0) {
        this.stopPolling();
        return;
      }
      void this.refreshOnce();
    }, intervalMs);
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = undefined;
    }
  }

  private refreshOnce(): Promise<void> {
    this.refreshChain = this.refreshChain.then(() => this.runRefresh());
    return this.refreshChain;
  }

  private async runRefresh(): Promise<void> {
    if (this.disposed) {
      return;
    }
    let outcome: SkillBillStatusOutcome;
    try {
      outcome = await this.statusRepository.fetchStatus(this.projectRoot);
    } catch {
      const fallback = this.transportFailureFallback(UnavailableReason.PROCESS_FAILURE);
      if (fallback) {
        this.emit(fallback);
      }
      return;
    }

    if (isUncorroboratedIdle(outcome)) {
      const held = this.currentOutcome;
      if (held && isLiveOutcome(held)) {
        this.unconfirmedIdleSamples += 1;
        if (this.unconfirmedIdleSamples <= UNCORROBORATED_IDLE_TOLERANCE) {
          this.emit(held);
          return;
        }
      }
    } else {
      this.unconfirmedIdleSamples = 0;
    }

    let toEmit: SkillBillStatusOutcome;
    if (outcome.kind === "unavailable" && isPollTransportFailure(outcome.reasonCode)) {
      toEmit = this.transportFailureFallback(outcome.reasonCode) ?? outcome;
    } else if (outcome.kind === "unavailable" || outcome.kind === "incompatible") {
      const cache = this.preferences.getLastKnownDisplayCache();
      toEmit = cache ? toStaleOutcome(cache) : outcome;
    } else {
      const snapshot = toCacheSnapshotOrNull(outcome);
      if (snapshot) {
        this.preferences.setLastKnownDisplayCache(snapshot);
      }
      toEmit = outcome;
    }
    this.emit(toEmit);
  }

  private transportFailureFallback(reason: UnavailableReason): SkillBillStatusOutcome | undefined {
    const held = this.currentOutcome;
    if (held && isLiveOutcome(held)) {
      return withPollFailure(held, reason);
    }
    const cache = this.preferences.getLastKnownDisplayCache();
    return cache ? toStaleOutcome(cache) : undefined;
  }

  private emit(outcome: SkillBillStatusOutcome): void {
    this.currentOutcome = outcome;
    for (const listener of this.listeners) {
      listener(outcome);
    }
  }
}
