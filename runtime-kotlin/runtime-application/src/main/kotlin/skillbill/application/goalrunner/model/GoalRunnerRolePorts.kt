package skillbill.application.goalrunner.model

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

interface GoalRunnerRunBoundariesPort {
  val manifestStore: GoalRunnerManifestStore
  val outcomeStore: GoalRunnerWorkflowOutcomeStore
  val goalPlanningSweep: GoalPlanningSweep
  val telemetry: GoalLifecycleTelemetryEmitter
  val clock: Clock
  val diagnostics: RuntimeDiagnostics
  val executionCoordinator: GoalRunnerExecutionCoordinator
  val phaseRecorder: FeatureTaskRuntimePhaseRecorder?
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?
}

interface GoalRunnerSubtaskLaunchBoundariesPort {
  val manifestStore: GoalRunnerManifestStore
  val outcomeStore: GoalRunnerWorkflowOutcomeStore
  val subtaskLauncher: GoalRunnerSubtaskLauncher
  val gitOperations: WorkflowGitOperations
}

interface GoalRunnerFinalizationBoundariesPort {
  val manifestStore: GoalRunnerManifestStore
  val outcomeStore: GoalRunnerWorkflowOutcomeStore
  val pullRequestPort: GoalPullRequestPort
  val specScratchStore: SpecScratchStore
  val gitOperations: WorkflowGitOperations
  val diagnostics: RuntimeDiagnostics
  val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?
}
