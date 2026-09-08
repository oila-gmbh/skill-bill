package skillbill.application.goalrunner.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.DecompositionManifestValidator

@Inject
data class GoalPreflightServiceDeps(
  val continuationLookup: FeatureTaskContinuationLookupService,
  val manifestStore: GoalRunnerManifestStore,
  val agentAddonSelectionPort: AgentAddonSelectionPort,
  val externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort,
  val manifestFileStore: DecompositionManifestStore,
  val manifestValidator: DecompositionManifestValidator,
  val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
)
