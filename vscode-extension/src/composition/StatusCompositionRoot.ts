import * as vscode from "vscode";
import { PreferenceCachePort } from "../application/PreferenceCachePort";
import { StatusRefreshCoordinator } from "../application/StatusRefreshCoordinator";
import { StatusRepository } from "../application/StatusRepository";
import { StatusClock } from "../domain/StatusClock";
import { CliSkillBillStatusRepository } from "../infrastructure/cli/CliSkillBillStatusRepository";
import { ProcessRunner } from "../infrastructure/cli/ProcessRunner";
import { VsCodePreferenceCache } from "../infrastructure/prefs/VsCodePreferenceCache";
import { SkillBillStatusViewModel } from "../presentation/SkillBillStatusViewModel";

export class StatusCompositionRoot {
  constructor(
    readonly preferences: PreferenceCachePort,
    readonly processRunner: ProcessRunner,
    readonly statusRepository: StatusRepository,
    readonly coordinator: StatusRefreshCoordinator,
    readonly viewModel: SkillBillStatusViewModel,
  ) {}

  dispose(): void {
    this.viewModel.dispose();
    this.coordinator.dispose();
    this.processRunner.cancelAll();
  }

  static create(
    context: vscode.ExtensionContext,
    workspaceRoot: string,
    clock: StatusClock = StatusClock.system(),
  ): StatusCompositionRoot {
    const preferences = new VsCodePreferenceCache(context, workspaceRoot);
    const processRunner = new ProcessRunner();
    const statusRepository = new CliSkillBillStatusRepository(preferences, processRunner, clock);
    const coordinator = new StatusRefreshCoordinator(
      statusRepository,
      preferences,
      workspaceRoot,
      () => processRunner.cancelAll(),
    );
    const viewModel = new SkillBillStatusViewModel(coordinator, clock);
    return new StatusCompositionRoot(preferences, processRunner, statusRepository, coordinator, viewModel);
  }

  static createForTest(options: {
    preferences: PreferenceCachePort;
    statusRepository: StatusRepository;
    projectRoot: string;
    clock?: StatusClock;
    onCancelProcesses?: () => void;
  }): StatusCompositionRoot {
    const clock = options.clock ?? StatusClock.system();
    const processRunner = new ProcessRunner();
    const coordinator = new StatusRefreshCoordinator(
      options.statusRepository,
      options.preferences,
      options.projectRoot,
      options.onCancelProcesses ?? (() => processRunner.cancelAll()),
    );
    const viewModel = new SkillBillStatusViewModel(coordinator, clock);
    return new StatusCompositionRoot(options.preferences, processRunner, options.statusRepository, coordinator, viewModel);
  }
}
