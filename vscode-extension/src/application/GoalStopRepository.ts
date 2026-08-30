export type GoalStopOutcome = { kind: "requested" } | { kind: "failed"; summary: string };

export interface GoalStopRepository {
  requestStop(projectRoot: string, issueKey: string): Promise<GoalStopOutcome>;
}
