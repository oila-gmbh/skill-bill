import { GoalPauseRepository } from "../application/GoalPauseRepository";
import { GoalStopRepository } from "../application/GoalStopRepository";
import { PreferenceCachePort } from "../application/PreferenceCachePort";
import { StatusRefreshCoordinator } from "../application/StatusRefreshCoordinator";
import { StatusRepository } from "../application/StatusRepository";
import { StatusClock } from "../domain/StatusClock";
import { CliGoalPauseRepository } from "../infrastructure/cli/CliGoalPauseRepository";
import { CliGoalStopRepository } from "../infrastructure/cli/CliGoalStopRepository";
import { ProcessRunner } from "../infrastructure/cli/ProcessRunner";
import { SkillBillStatusViewModel } from "../presentation/SkillBillStatusViewModel";

export class StatusCompositionRoot {
  constructor(
    readonly preferences: PreferenceCachePort,
    readonly processRunner: ProcessRunner,
    readonly pauseProcessRunner: ProcessRunner,
    readonly stopProcessRunner: ProcessRunner,
    readonly statusRepository: StatusRepository,
    readonly coordinator: StatusRefreshCoordinator,
    readonly viewModel: SkillBillStatusViewModel,
    readonly goalPauseRepository: GoalPauseRepository,
    readonly goalStopRepository: GoalStopRepository,
  ) {}

  dispose(): void {
    this.viewModel.dispose();
    this.coordinator.dispose();
    this.processRunner.cancelAll();
    this.pauseProcessRunner.cancelAll();
    this.stopProcessRunner.cancelAll();
  }

  static createForTest(options: {
    preferences: PreferenceCachePort;
    statusRepository: StatusRepository;
    projectRoot: string;
    clock?: StatusClock;
    onCancelProcesses?: () => void;
    processRunner?: ProcessRunner;
    pauseProcessRunner?: ProcessRunner;
    stopProcessRunner?: ProcessRunner;
    goalPauseRepository?: GoalPauseRepository;
    goalStopRepository?: GoalStopRepository;
  }): StatusCompositionRoot {
    const clock = options.clock ?? StatusClock.system();
    const processRunner = options.processRunner ?? new ProcessRunner();
    const pauseProcessRunner = options.pauseProcessRunner ?? new ProcessRunner();
    const stopProcessRunner = options.stopProcessRunner ?? new ProcessRunner();
    const coordinator = new StatusRefreshCoordinator(
      options.statusRepository,
      options.preferences,
      options.projectRoot,
      options.onCancelProcesses ?? (() => processRunner.cancelAll()),
    );
    const viewModel = new SkillBillStatusViewModel(coordinator, clock);
    const goalPauseRepository =
      options.goalPauseRepository ??
      new CliGoalPauseRepository(options.preferences, pauseProcessRunner);
    const goalStopRepository =
      options.goalStopRepository ??
      new CliGoalStopRepository(options.preferences, stopProcessRunner);
    return new StatusCompositionRoot(
      options.preferences,
      processRunner,
      pauseProcessRunner,
      stopProcessRunner,
      options.statusRepository,
      coordinator,
      viewModel,
      goalPauseRepository,
      goalStopRepository,
    );
  }
}
