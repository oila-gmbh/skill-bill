package skillbill.application.goalrunner.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.GoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.planning.GoalPlanningSweep
import skillbill.application.telemetry.GoalLifecycleTelemetryEmitter
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.specscratch.SpecScratchStore
import java.time.Clock

@Inject
data class DefaultGoalRunnerRunBoundariesPort(
  override val manifestStore: GoalRunnerManifestStore,
  override val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  override val goalPlanningSweep: GoalPlanningSweep,
  override val telemetry: GoalLifecycleTelemetryEmitter,
  override val clock: Clock,
  override val diagnostics: RuntimeDiagnostics,
  override val executionCoordinator: GoalRunnerExecutionCoordinator,
  override val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  override val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
) : GoalRunnerRunBoundariesPort

@Inject
data class DefaultGoalRunnerSubtaskLaunchBoundariesPort(
  override val manifestStore: GoalRunnerManifestStore,
  override val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  override val subtaskLauncher: GoalRunnerSubtaskLauncher,
  override val gitOperations: WorkflowGitOperations,
) : GoalRunnerSubtaskLaunchBoundariesPort

@Inject
data class DefaultGoalRunnerFinalizationBoundariesPort(
  override val manifestStore: GoalRunnerManifestStore,
  override val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  override val pullRequestPort: GoalPullRequestPort,
  override val specScratchStore: SpecScratchStore,
  override val gitOperations: WorkflowGitOperations,
  override val diagnostics: RuntimeDiagnostics,
  override val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
) : GoalRunnerFinalizationBoundariesPort
