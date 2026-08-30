import * as vscode from "vscode";
import { GoalPauseRepository } from "../application/GoalPauseRepository";
import { GoalStopRepository } from "../application/GoalStopRepository";
import { MappedPresentation, SkillBillStatusBarPresentation } from "../presentation/SkillBillStatusBarPresentation";
import { SkillBillStatusUiState } from "../presentation/SkillBillStatusUiState";
import { SkillBillStatusViewModel } from "../presentation/SkillBillStatusViewModel";
import { showStatusDetails } from "./StatusDetailsView";

export class StatusBarController implements vscode.Disposable {
  private readonly statusBarItem: vscode.StatusBarItem;
  private unsubscribe: (() => void) | undefined;
  private latestPresentation: MappedPresentation | undefined;
  private disposed = false;

  constructor(
    private readonly viewModel: SkillBillStatusViewModel,
    private readonly workspaceRoot: string,
    private readonly goalStopRepository: GoalStopRepository,
    private readonly goalPauseRepository: GoalPauseRepository,
    priority = 100,
  ) {
    this.statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, priority);
    this.statusBarItem.command = {
      command: "skill-bill-status.showDetails",
      title: "Show Skill Bill status details",
      arguments: [this.workspaceRoot],
    };
    this.statusBarItem.tooltip = "Skill Bill status";
    this.statusBarItem.show();
    this.unsubscribe = this.viewModel.subscribe((state) => this.render(state));
    this.viewModel.onConsumerActivated();
  }

  render(state: SkillBillStatusUiState): void {
    const presentation = SkillBillStatusBarPresentation.map(state);
    this.latestPresentation = presentation;
    this.statusBarItem.text = presentation.showActivityAnimation
      ? `$(sync~spin) ${presentation.barText}`
      : presentation.barText;
    this.statusBarItem.tooltip = presentation.tooltipText;
    this.statusBarItem.accessibilityInformation = {
      label: presentation.accessibleName,
      role: "status",
    };
  }

  showDetails(): void {
    if (this.latestPresentation) {
      showStatusDetails(this.latestPresentation, {
        onRefresh: () => this.viewModel.refresh(),
        onStop: async (issueKey) => {
          const outcome = await this.goalStopRepository.requestStop(this.workspaceRoot, issueKey);
          return outcome.kind === "failed" ? outcome.summary : undefined;
        },
        onPause: async (issueKey) => {
          const outcome = await this.goalPauseRepository.requestPause(this.workspaceRoot, issueKey);
          return outcome.kind === "failed" ? outcome.summary : undefined;
        },
      });
    } else {
      void vscode.window.showInformationMessage("Skill Bill status is not available yet.");
      this.viewModel.refresh();
    }
  }

  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    this.viewModel.onConsumerDeactivated();
    this.statusBarItem.dispose();
  }
}
