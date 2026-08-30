import * as vscode from "vscode";
import { MappedPresentation } from "../presentation/SkillBillStatusBarPresentation";

export function showStatusDetails(presentation: MappedPresentation, onRefresh: () => void): void {
  const details = presentation.details;
  const lines: string[] = [
    `Lifecycle: ${details.lifecycleState}`,
  ];
  if (details.issueKey) {
    lines.push(`Issue: ${details.issueKey}`);
  }
  if (details.workflowId) {
    lines.push(`Workflow: ${details.workflowId}`);
  }
  if (details.stepLabel) {
    lines.push(`Step: ${details.stepLabel}`);
  }
  if (details.selectedSlotLabel && details.selectedSlotText) {
    lines.push(`${details.selectedSlotLabel}: ${details.selectedSlotText}`);
  }
  if (details.modelText) {
    lines.push(`Model: ${details.modelText}`);
  }
  lines.push(`Goal ${details.elapsedNoun}: ${details.goalElapsedText}`);
  lines.push(`Subtask ${details.elapsedNoun}: ${details.subtaskElapsedText}`);
  if (details.progressText) {
    lines.push(`Progress: ${details.progressText}`);
  }
  if (details.lastUpdateText) {
    lines.push(`Last update: ${details.lastUpdateText}`);
  }
  if (details.problemSummary) {
    lines.push(details.problemSummary);
  }
  if (details.staleNote) {
    lines.push(details.staleNote);
  }
  if (details.pauseReasonText) {
    lines.push(`Pause reason: ${details.pauseReasonText}`);
  }

  void vscode.window
    .showInformationMessage(lines.join("\n"), { modal: false }, "Refresh")
    .then((choice) => {
      if (choice === "Refresh") {
        onRefresh();
      }
    });
}
