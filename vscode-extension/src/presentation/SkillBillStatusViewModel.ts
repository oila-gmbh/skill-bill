import { StatusRefreshCoordinator } from "../application/StatusRefreshCoordinator";
import { SkillBillStatusOutcome } from "../domain/SkillBillStatusOutcome";
import { StatusClock } from "../domain/StatusClock";
import { StatusUiMapper } from "./StatusUiMapper";
import { SkillBillStatusUiState } from "./SkillBillStatusUiState";

export type UiStateListener = (state: SkillBillStatusUiState) => void;

export class SkillBillStatusViewModel {
  private latestOutcome: SkillBillStatusOutcome | undefined;
  private tickTimer: ReturnType<typeof setInterval> | undefined;
  private unsubscribe: (() => void) | undefined;
  private started = false;
  private readonly listeners = new Set<UiStateListener>();

  constructor(
    private readonly coordinator: StatusRefreshCoordinator,
    private readonly clock: StatusClock,
    private readonly tickIntervalMs = 1000,
  ) {}

  subscribe(listener: UiStateListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onConsumerActivated(): void {
    if (!this.started) {
      this.started = true;
      this.unsubscribe = this.coordinator.subscribe((outcome) => {
        this.latestOutcome = outcome;
        this.publish(outcome);
      });
      this.tickTimer = setInterval(() => {
        if (this.latestOutcome) {
          this.publish(this.latestOutcome);
        }
      }, this.tickIntervalMs);
    }
    this.coordinator.addConsumer();
  }

  onConsumerDeactivated(): void {
    this.coordinator.removeConsumer();
  }

  refresh(): void {
    this.coordinator.requestRefresh();
  }

  dispose(): void {
    if (this.tickTimer) {
      clearInterval(this.tickTimer);
      this.tickTimer = undefined;
    }
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    this.started = false;
    this.listeners.clear();
  }

  private publish(outcome: SkillBillStatusOutcome): void {
    const state = StatusUiMapper.map(outcome, this.clock.now());
    for (const listener of this.listeners) {
      listener(state);
    }
  }
}
