import assert from "node:assert/strict";
import { describe, it } from "mocha";
import { StatusRepository } from "../application/StatusRepository";
import { StatusCompositionRoot } from "../composition/StatusCompositionRoot";
import { SkillBillStatusOutcome } from "../domain/SkillBillStatusOutcome";
import { ProcessSpec } from "../infrastructure/cli/ProcessRunner";
import { FakePreferenceCache } from "./fakes/FakePreferenceCache";

class StubStatusRepository implements StatusRepository {
  async fetchStatus(): Promise<SkillBillStatusOutcome> {
    return { kind: "idle", observedAt: new Date(), summary: "idle" };
  }
}

function createRoot(): StatusCompositionRoot {
  return StatusCompositionRoot.createForTest({
    preferences: new FakePreferenceCache(),
    statusRepository: new StubStatusRepository(),
    projectRoot: "/tmp/skill-bill-test",
  });
}

describe("ProcessRunnerIsolation", () => {
  it("StatusCompositionRoot wires distinct status pause and stop runners", () => {
    const root = createRoot();
    try {
      assert.notEqual(root.processRunner, root.pauseProcessRunner);
      assert.notEqual(root.processRunner, root.stopProcessRunner);
      assert.notEqual(root.pauseProcessRunner, root.stopProcessRunner);
      assert.equal(
        new Set([root.processRunner, root.pauseProcessRunner, root.stopProcessRunner]).size,
        3,
      );
    } finally {
      root.dispose();
    }
  });

  it("cancelAll on each runner is independent", async () => {
    const root = createRoot();
    try {
      const spec: ProcessSpec = {
        command: ["skill-bill"],
        timeoutMs: 500,
        stdoutLimitBytes: 16,
        stderrLimitBytes: 16,
      };
      root.processRunner.cancelAll();
      const statusResult = await root.processRunner.runCoalesced(spec);
      assert.equal(statusResult.cancelled, true);
      root.pauseProcessRunner.cancelAll();
      const pauseResult = await root.pauseProcessRunner.runCoalesced(spec);
      assert.equal(pauseResult.cancelled, true);
      root.stopProcessRunner.cancelAll();
      const stopResult = await root.stopProcessRunner.runCoalesced(spec);
      assert.equal(stopResult.cancelled, true);
    } finally {
      root.dispose();
    }
  });
});
