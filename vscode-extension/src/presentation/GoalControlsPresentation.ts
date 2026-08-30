import { FEATURE_GOAL_WORKFLOW_FAMILY } from "../domain/Constants";
import { GoalControlDescriptor, GoalControlKind } from "./GoalControlDescriptor";
import { SkillBillStatusUiState } from "./SkillBillStatusUiState";

export const GoalControlsPresentation = {
  controlsFor(state: SkillBillStatusUiState): GoalControlDescriptor[] {
    if (state.kind !== "active") {
      return [];
    }
    if (state.workflowFamily !== FEATURE_GOAL_WORKFLOW_FAMILY) {
      return [];
    }
    const issueKey = state.issueKey?.trim();
    if (!issueKey) {
      return [];
    }

    const pauseAlreadyRequested = state.pauseRequested === true;
    return [
      {
        kind: GoalControlKind.STOP,
        issueKey,
        text: "Stop goal",
        enabled: true,
        accessibleName: `Stop Skill Bill goal ${issueKey} now`,
      },
      {
        kind: GoalControlKind.PAUSE,
        issueKey,
        text: pauseAlreadyRequested ? "Pause requested" : "Pause after current subtask",
        enabled: !pauseAlreadyRequested,
        accessibleName: pauseAlreadyRequested
          ? `Pause already requested for Skill Bill goal ${issueKey}; it takes effect after the current subtask`
          : `Pause Skill Bill goal ${issueKey} after the current subtask`,
      },
    ];
  },
};
