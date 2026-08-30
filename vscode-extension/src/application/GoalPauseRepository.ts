export type GoalPauseOutcome = { kind: "requested" } | { kind: "failed"; summary: string };

export interface GoalPauseRepository {
  requestPause(projectRoot: string, issueKey: string): Promise<GoalPauseOutcome>;
}
