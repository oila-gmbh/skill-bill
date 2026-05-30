package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.install.model.InstallAgent
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore

@Inject
class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) {
  fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    val effectiveAgent = request.configuredAgentOverrideId
      ?.let { InstallAgent.fromNormalizedId(it, label = "configuredAgentOverrideId") }
      ?: InstallAgent.fromNormalizedId(request.invokedAgentId, label = "invokedAgentId")
    return manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?.manifest
      ?.let { manifest ->
        val currentSubtask = manifest.subtasks.firstOrNull { subtask ->
          subtask.id == manifest.currentSubtaskIntent.subtaskId
        }
        val currentStep = currentSubtask
          ?.workflowId
          ?.takeIf(String::isNotBlank)
          ?.let { workflowId -> outcomeStore.progress(workflowId, request.dbPathOverride)?.currentStepId }
        GoalRunnerStatusProjector.project(
          manifest = manifest,
          activeAgent = effectiveAgent.id,
          currentStepOverride = currentStep,
        )
      }
  }
}
