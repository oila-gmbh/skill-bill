package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.decomposition.model.DecompositionSubtask

internal class GoalRunnerStatusProjectionAssembler(deps: GoalRunnerStatusProjectionAssemblerDeps) {
  internal val manifestStore = deps.manifestStore
  internal val outcomeStore = deps.outcomeStore
  internal val phaseRecorder = deps.phaseRecorder
  internal val gitOperations = deps.gitOperations
  internal val attemptLedgerStore = deps.attemptLedgerStore
  internal val clock = deps.clock
  internal val workerSupervisor = deps.workerSupervisor
  internal val planningStatusReasonCoherence = deps.planningStatusReasonCoherence
  internal val diagnostics = deps.diagnostics
  internal val runtimeStatusService = deps.runtimeStatusService
  fun project(loadedState: GoalRunnerManifestState, request: GoalRunnerStatusRequest): GoalRunnerStatusProjection {
    val acceptances = manifestStore.outOfBandAcceptances(loadedState.parentWorkflowId, request.dbPathOverride)
    val manifest = reconcileStatusManifest(loadedState, request, acceptances)
    val currentSubtask = manifest.subtasks.firstOrNull { subtask ->
      subtask.id == manifest.currentSubtaskIntent.subtaskId
    }
    return GoalRunnerStatusProjector.project(
      manifest = manifest,
      activeAgent = resolveActiveAgent(currentSubtask, request.dbPathOverride),
      extras = statusProjectionExtras(
        loadedState = loadedState,
        request = request,
        manifest = manifest,
        currentSubtask = currentSubtask,
        acceptances = acceptances,
      ),
    )
  }

  fun resolveExecutionLiveness(
    parentWorkflowId: String,
    currentSubtask: DecompositionSubtask?,
    dbPathOverride: String?,
  ): ExecutionLiveness {
    val workflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank)
      ?: return resolveParentExecutionLiveness(parentWorkflowId, dbPathOverride)
    val childLiveness = resolveChildExecutionLiveness(workflowId, dbPathOverride)
    if (childLiveness == ExecutionLiveness.LIVE || childLiveness == ExecutionLiveness.UNKNOWN) {
      return childLiveness
    }
    return resolveParentExecutionLiveness(parentWorkflowId, dbPathOverride)
  }
}
