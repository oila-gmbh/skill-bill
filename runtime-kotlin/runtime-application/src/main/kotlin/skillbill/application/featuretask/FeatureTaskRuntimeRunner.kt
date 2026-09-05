package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimePreparation
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

@Inject
class FeatureTaskRuntimeRunner(
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  val phaseSettlementService: FeatureTaskPhaseSettlementService,
  val diagnostics: RuntimeDiagnostics,
  val clock: Clock,
  val activityStampWriter: AgentActivityStampWriter,
) {
  fun run(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport {
    val reconciliation = crashReconciler.reconcile(request.dbPathOverride)
    return when (val preparation = prepareRun(request)) {
      is FeatureTaskRuntimePreparation.PreparationBlocked -> preparation.report
      is FeatureTaskRuntimePreparation.Prepared -> executePreparedRun(preparation.request, reconciliation)
    }
  }

  private fun prepareRun(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimePreparation =
    foreignModeWorkflowBlock(request)?.let(FeatureTaskRuntimePreparation::PreparationBlocked)
      ?: FeatureTaskRuntimeRunPreparation(
        recorder,
        goalContinuationRecorder,
        runInvariantsStore,
      ).prepare(request)

  private fun foreignModeWorkflowBlock(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport.Blocked? {
    val existingMode = recorder.existingWorkflowMode(request.workflowId, request.dbPathOverride)
    if (existingMode == null || existingMode == FeatureTaskWorkflowMode.RUNTIME) {
      return null
    }
    return FeatureTaskRuntimeRunReport.Blocked(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      lastIncompletePhase = FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultInitialStepId,
      blockedReason = "Cannot resume workflow '${request.workflowId}' in runtime mode: it was created in " +
        "'${existingMode.wireValue}' mode. A feature-task workflow is mode-scoped — prose and runtime are " +
        "not interchangeable. Finish this subtask in '${existingMode.wireValue}' mode, or reset the subtask " +
        "to start a fresh runtime attempt.",
      completedPhaseIds = emptyList(),
      resolvedBranch = null,
    )
  }
}
