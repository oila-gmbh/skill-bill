import assert from "node:assert/strict";
import { describe, it } from "mocha";
import { GoalPlanningInfo } from "../domain/SkillBillStatusOutcome";
import { CurrentPhaseExecution } from "../domain/SkillBillStatusOutcome";
import { SkillBillStatusBarPresentation } from "../presentation/SkillBillStatusBarPresentation";
import { SkillBillStatusUiState } from "../presentation/SkillBillStatusUiState";
import { StatusUiMapper } from "../presentation/StatusUiMapper";

const now = new Date("2026-08-07T12:00:00Z");
const started = new Date("2026-08-07T10:00:00Z");
const subtaskStarted = new Date("2026-08-07T11:30:00Z");

describe("SkillBillStatusBarPresentation", () => {
  it("planning wins over current phase execution in the display slot", () => {
    const planning: GoalPlanningInfo = {
      state: "partially_planned",
      sharedPreplanPrepared: true,
      plannedSubtaskCount: 2,
      totalSubtaskCount: 4,
    };
    const execution: CurrentPhaseExecution = {
      phaseId: "implement",
      kind: "attempt",
      count: 1,
    };
    const slot = SkillBillStatusBarPresentation.selectDisplaySlot(planning, execution);
    assert.equal(slot?.kind, "planning");
    const state: SkillBillStatusUiState = {
      kind: "active",
      headline: "Skill Bill: Implement",
      stepLabel: "Implement",
      planning,
      currentPhaseExecution: execution,
      goalElapsedMs: 7_200_000,
      subtaskElapsedMs: 1_800_000,
      progressCompleted: 0,
      progressTotal: 4,
      lastUpdated: now,
    };
    const mapped = SkillBillStatusBarPresentation.map(state, now);
    assert.ok(mapped.barText.includes("Planning 2/4"));
    assert.ok(mapped.tooltipText.includes("Planning:"));
    assert.ok(!mapped.barText.includes("attempt"));
  });

  it("elapsed ticks only for active and freezes for paused", () => {
    const activeState: SkillBillStatusUiState = {
      kind: "active",
      headline: "Skill Bill: Implement",
      stepLabel: "Implement",
      startedAt: started,
      subtaskStartedAt: subtaskStarted,
      goalElapsedMs: StatusUiMapper.activeElapsedMs(undefined, undefined, started, now),
      subtaskElapsedMs: StatusUiMapper.subtaskElapsedMs(undefined, undefined, subtaskStarted, undefined, undefined, started, now),
      lastUpdated: now,
    };
    const later = new Date("2026-08-07T12:00:30Z");
    const activeLater = SkillBillStatusBarPresentation.map(activeState, later);
    assert.notEqual(activeLater.details.goalElapsedText, activeState.goalElapsedMs && "0s");

    const pausedState: SkillBillStatusUiState = {
      kind: "paused",
      headline: "Skill Bill: paused",
      stepLabel: "Implement",
      startedAt: started,
      subtaskStartedAt: subtaskStarted,
      goalElapsedMs: StatusUiMapper.activeElapsedMs(undefined, undefined, started, now),
      subtaskElapsedMs: StatusUiMapper.subtaskElapsedMs(undefined, undefined, subtaskStarted, undefined, undefined, started, now),
      lastUpdated: now,
    };
    const pausedFrozen = SkillBillStatusBarPresentation.map(pausedState, later);
    assert.equal(pausedFrozen.details.goalElapsedText, SkillBillStatusBarPresentation.map(pausedState, now).details.goalElapsedText);
    assert.equal(pausedFrozen.details.elapsedNoun, "ran");
  });

  it("stale overlays prior lifecycle wording", () => {
    const stale: SkillBillStatusUiState = {
      kind: "stale",
      headline: "Skill Bill: Implement (stale)",
      stepLabel: "Implement",
      goalElapsedMs: 3_600_000,
      subtaskElapsedMs: 900_000,
      lastUpdated: now,
      problemSummary: "Status is stale",
    };
    const mapped = SkillBillStatusBarPresentation.map(stale, now);
    assert.ok(mapped.barText.includes("stale"));
    assert.ok(mapped.details.staleNote);
    assert.equal(mapped.details.elapsedNoun, "ran");
  });
});
