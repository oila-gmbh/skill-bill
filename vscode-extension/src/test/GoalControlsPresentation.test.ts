import assert from "node:assert/strict";
import { describe, it } from "mocha";
import { FEATURE_GOAL_WORKFLOW_FAMILY } from "../domain/Constants";
import { GoalControlKind } from "../presentation/GoalControlDescriptor";
import { GoalControlsPresentation } from "../presentation/GoalControlsPresentation";
import { SkillBillStatusUiState } from "../presentation/SkillBillStatusUiState";

function activeUiState(
  issueKey: string | undefined,
  workflowFamily: string | undefined,
  pauseRequested?: boolean,
): SkillBillStatusUiState {
  return {
    kind: "active",
    headline: "Skill Bill: goal",
    stepLabel: "Implement",
    issueKey,
    workflowId: "w1",
    workflowFamily,
    pauseRequested,
    progressCompleted: 1,
    progressTotal: 3,
    lastUpdated: new Date("2026-08-06T12:00:00Z"),
  };
}

describe("GoalControlsPresentation", () => {
  const families = [FEATURE_GOAL_WORKFLOW_FAMILY, "feature-task-runtime", undefined];
  const issueKeys = ["SKILL-168", undefined, "   "];

  it("controls appear only for active feature-goal with a non-blank issue key", () => {
    let eligibleCount = 0;
    for (const family of families) {
      for (const issueKey of issueKeys) {
        for (const state of allStates(family, issueKey)) {
          const controls = GoalControlsPresentation.controlsFor(state);
          const shouldBeEligible =
            state.kind === "active" &&
            family === FEATURE_GOAL_WORKFLOW_FAMILY &&
            issueKey === "SKILL-168";
          if (shouldBeEligible) {
            eligibleCount += 1;
            assert.deepEqual(
              controls.map((control) => control.kind),
              [GoalControlKind.STOP, GoalControlKind.PAUSE],
            );
            assert.ok(controls.every((control) => control.issueKey === "SKILL-168"));
            assert.ok(controls.every((control) => control.accessibleName.length > 0));
          } else {
            assert.equal(controls.length, 0, `unexpected controls for family=${family} key=${issueKey}`);
          }
        }
      }
    }
    assert.equal(eligibleCount, 1);
  });

  it("pause is disabled with Pause requested text when pauseRequested is true", () => {
    const controls = GoalControlsPresentation.controlsFor(
      activeUiState("SKILL-168", FEATURE_GOAL_WORKFLOW_FAMILY, true),
    );
    const pause = controls.find((control) => control.kind === GoalControlKind.PAUSE);
    assert.ok(pause);
    assert.equal(pause.enabled, false);
    assert.match(pause.text, /requested/i);
  });

  it("absent or false pauseRequested leaves pause enabled", () => {
    for (const pauseRequested of [undefined, false] as const) {
      const controls = GoalControlsPresentation.controlsFor(
        activeUiState("SKILL-168", FEATURE_GOAL_WORKFLOW_FAMILY, pauseRequested),
      );
      const pause = controls.find((control) => control.kind === GoalControlKind.PAUSE);
      assert.ok(pause);
      assert.equal(pause.enabled, true);
      assert.match(pause.text, /subtask/i);
    }
  });

  it("never emits Resume", () => {
    const controls = GoalControlsPresentation.controlsFor(activeUiState("SKILL-168", FEATURE_GOAL_WORKFLOW_FAMILY));
    assert.ok(!controls.some((control) => String(control.kind).includes("RESUME")));
    assert.equal(Object.values(GoalControlKind).filter((kind) => String(kind).includes("RESUME")).length, 0);
  });
});

function allStates(family: string | undefined, issueKey: string | undefined): SkillBillStatusUiState[] {
  return [
    activeUiState(issueKey, family),
    { kind: "idle", headline: "idle" },
    {
      kind: "done",
      headline: "done",
      issueKey,
    },
    {
      kind: "paused",
      headline: "paused",
      stepLabel: "Implement",
      issueKey,
      workflowId: "w1",
      workflowFamily: family,
    },
    {
      kind: "stale",
      headline: "stale",
      issueKey,
    },
    {
      kind: "blocked",
      headline: "blocked",
      issueKey,
    },
    {
      kind: "failed",
      headline: "failed",
      issueKey,
    },
    {
      kind: "unavailable",
      headline: "unavailable",
      reasonCode: "TIMEOUT",
    },
    {
      kind: "incompatible",
      headline: "incompatible",
      foundContractVersion: "0.9",
    },
  ];
}
