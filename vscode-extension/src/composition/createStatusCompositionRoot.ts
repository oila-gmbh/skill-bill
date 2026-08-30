import type { ExtensionContext } from "vscode";
import { StatusRefreshCoordinator } from "../application/StatusRefreshCoordinator";
import { StatusClock } from "../domain/StatusClock";
import { CliGoalPauseRepository } from "../infrastructure/cli/CliGoalPauseRepository";
import { CliGoalStopRepository } from "../infrastructure/cli/CliGoalStopRepository";
import { CliSkillBillStatusRepository } from "../infrastructure/cli/CliSkillBillStatusRepository";
import { ProcessRunner } from "../infrastructure/cli/ProcessRunner";
import { VsCodePreferenceCache } from "../infrastructure/prefs/VsCodePreferenceCache";
import { SkillBillStatusViewModel } from "../presentation/SkillBillStatusViewModel";
import { StatusCompositionRoot } from "./StatusCompositionRoot";

export function createStatusCompositionRoot(
  context: ExtensionContext,
  workspaceRoot: string,
  clock: StatusClock = StatusClock.system(),
): StatusCompositionRoot {
  const preferences = new VsCodePreferenceCache(context, workspaceRoot);
  const processRunner = new ProcessRunner();
  const pauseProcessRunner = new ProcessRunner();
  const stopProcessRunner = new ProcessRunner();
  const statusRepository = new CliSkillBillStatusRepository(preferences, processRunner, clock);
  const coordinator = new StatusRefreshCoordinator(
    statusRepository,
    preferences,
    workspaceRoot,
    () => processRunner.cancelAll(),
  );
  const viewModel = new SkillBillStatusViewModel(coordinator, clock);
  const goalPauseRepository = new CliGoalPauseRepository(preferences, pauseProcessRunner);
  const goalStopRepository = new CliGoalStopRepository(preferences, stopProcessRunner);
  return new StatusCompositionRoot(
    preferences,
    processRunner,
    pauseProcessRunner,
    stopProcessRunner,
    statusRepository,
    coordinator,
    viewModel,
    goalPauseRepository,
    goalStopRepository,
  );
}
