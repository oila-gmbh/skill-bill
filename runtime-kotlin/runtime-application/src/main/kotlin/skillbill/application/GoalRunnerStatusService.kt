package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.install.model.InstallAgent
import skillbill.ports.goalrunner.GoalRunnerManifestStore

@Inject
class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
) {
  fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    val effectiveAgent = request.configuredAgentOverrideId
      ?.let { InstallAgent.fromNormalizedId(it, label = "configuredAgentOverrideId") }
      ?: InstallAgent.fromNormalizedId(request.invokedAgentId, label = "invokedAgentId")
    return manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride)
      ?.manifest
      ?.let { manifest -> GoalRunnerStatusProjector.project(manifest, activeAgent = effectiveAgent.id) }
  }
}
