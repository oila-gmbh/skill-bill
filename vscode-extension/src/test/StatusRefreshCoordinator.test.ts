import assert from "node:assert/strict";
import { describe, it } from "mocha";
import { PreferenceCachePort } from "../application/PreferenceCachePort";
import { StatusRefreshCoordinator } from "../application/StatusRefreshCoordinator";
import { StatusRepository } from "../application/StatusRepository";
import { SkillBillStatusOutcome } from "../domain/SkillBillStatusOutcome";

class FakePreferences implements PreferenceCachePort {
  constructor(public refreshIntervalSeconds = 60) {}
  getCliExecutableOverride(): string | undefined {
    return undefined;
  }
  setCliExecutableOverride(): void {}
  getRefreshIntervalSeconds(): number {
    return this.refreshIntervalSeconds;
  }
  setRefreshIntervalSeconds(): void {}
  getLastKnownDisplayCache() {
    return undefined;
  }
  setLastKnownDisplayCache(): void {}
}

class FakeStatusRepository implements StatusRepository {
  callCount = 0;
  maxInFlight = 0;
  private inFlight = 0;
  private gate: Promise<void> | undefined;
  private releaseGate: (() => void) | undefined;

  constructor(private readonly handler: () => Promise<SkillBillStatusOutcome> | SkillBillStatusOutcome) {}

  armGate(): void {
    this.gate = new Promise((resolve) => {
      this.releaseGate = resolve;
    });
  }

  release(): void {
    this.releaseGate?.();
  }

  async fetchStatus(): Promise<SkillBillStatusOutcome> {
    this.callCount += 1;
    this.inFlight += 1;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    try {
      if (this.gate) {
        await this.gate;
      }
      return await this.handler();
    } finally {
      this.inFlight -= 1;
    }
  }
}

function idle(summary = "idle"): SkillBillStatusOutcome {
  return { kind: "idle", observedAt: new Date(), summary };
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe("StatusRefreshCoordinator", () => {
  it("coalesces overlapping refresh requests", async () => {
    const repo = new FakeStatusRepository(() => idle());
    repo.armGate();
    const prefs = new FakePreferences(60);
    const coordinator = new StatusRefreshCoordinator(repo, prefs, "/tmp/a");
    coordinator.addConsumer();
    await delay(30);
    coordinator.requestRefresh();
    await delay(30);
    assert.equal(repo.maxInFlight, 1);
    repo.release();
    await delay(50);
    coordinator.dispose();
  });

  it("stops polling after dispose", async () => {
    let cancelled = false;
    const repo = new FakeStatusRepository(async () => {
      await delay(10_000);
      return idle();
    });
    const prefs = new FakePreferences(1);
    const coordinator = new StatusRefreshCoordinator(repo, prefs, "/tmp/b", () => {
      cancelled = true;
    });
    coordinator.addConsumer();
    await delay(50);
    const calls = repo.callCount;
    coordinator.dispose();
    assert.equal(cancelled, true);
    await delay(200);
    assert.equal(repo.callCount, calls);
  });
});
