import * as vscode from "vscode";
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
    private readonly workspaceRootKey: string,
    priority = 100,
  ) {
    this.statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, priority);
    this.statusBarItem.command = {
      command: "skill-bill-status.showDetails",
      arguments: [this.workspaceRootKey],
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
      description: presentation.accessibleDescription,
    };
  }

  showDetails(): void {
    if (this.latestPresentation) {
      showStatusDetails(this.latestPresentation, () => this.viewModel.refresh());
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
