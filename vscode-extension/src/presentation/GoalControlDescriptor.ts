export enum GoalControlKind {
  STOP = "STOP",
  PAUSE = "PAUSE",
}

export interface GoalControlDescriptor {
  kind: GoalControlKind;
  issueKey: string;
  text: string;
  enabled: boolean;
  accessibleName: string;
}
