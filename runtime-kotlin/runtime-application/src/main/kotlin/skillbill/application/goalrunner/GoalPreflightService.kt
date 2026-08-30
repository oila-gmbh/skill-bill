package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.goalrunner.model.GoalPreflightLookupInput
import skillbill.application.goalrunner.model.GoalPreflightRequest
import skillbill.application.goalrunner.model.GoalPreflightResult
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.workflow.decomposition.DecompositionManifestValidator

@Inject
class GoalPreflightService(
  private val continuationLookup: FeatureTaskContinuationLookupService,
  private val manifestStore: GoalRunnerManifestStore,
  agentAddonSelectionPort: AgentAddonSelectionPort,
  externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort,
  private val manifestFileStore: DecompositionManifestFileStore,
  private val manifestValidator: DecompositionManifestValidator,
) {
  private val gateBlockBuilder = GoalPreflightGateBlockBuilder(
    manifestStore,
    agentAddonSelectionPort,
    externalAgentAddonSourceConfigPort,
    manifestFileStore,
  )
  private val lookupResolver = GoalPreflightLookupResolver(gateBlockBuilder)

  fun preflight(request: GoalPreflightRequest): GoalPreflightResult {
    GoalPreflightInputValidation.requireInvokedAgentId(request.invokedAgentId)
    GoalPreflightInputValidation.requireOptionalIdentity("agent_override_id", request.agentOverrideId)
    val root = GoalPreflightInputValidation.resolveRepositoryRoot(request.repoRoot)
    val normalizedIssueKey = GoalPreflightInputValidation.normalizeIssueKey(request.issueKey)
    val projectedManifest = resolveDecompositionManifest(
      repoRoot = root,
      issueKey = normalizedIssueKey,
      fileStore = manifestFileStore,
      validator = manifestValidator,
      recoverPending = false,
    )
    val manifestState = manifestStore.readByIssueKeyIfPresent(
      normalizedIssueKey,
      request.dbPathOverride,
      root,
    )
    val manifest = manifestState?.manifest ?: projectedManifest
    if (manifest != null) {
      GoalPreflightInputValidation.requireManifestIssueKey(manifest.issueKey, normalizedIssueKey)
    }
    val lookup = continuationLookup.lookupIfPresent(
      issueKey = normalizedIssueKey,
      repositoryIdentity = goalRepositoryIdentity(root),
      dbOverride = request.dbPathOverride,
    )
    return lookupResolver.resolve(
      GoalPreflightLookupInput(
        lookup = lookup,
        normalizedIssueKey = normalizedIssueKey,
        manifest = manifest,
        manifestState = manifestState,
        request = request,
        root = root,
      ),
    )
  }
}
