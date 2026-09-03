package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.goalrunner.model.GoalPreflightLookupInput
import skillbill.application.goalrunner.model.GoalPreflightRequest
import skillbill.application.goalrunner.model.GoalPreflightResult
import skillbill.application.goalrunner.model.GoalPreflightServiceDeps

@Inject
class GoalPreflightService(deps: GoalPreflightServiceDeps) {
  private val continuationLookup = deps.continuationLookup
  private val manifestStore = deps.manifestStore
  private val manifestFileStore = deps.manifestFileStore
  private val manifestValidator = deps.manifestValidator
  private val repositoryEnclosingRootPort = deps.repositoryEnclosingRootPort
  private val gateBlockBuilder = GoalPreflightGateBlockBuilder(
    manifestStore,
    deps.agentAddonSelectionPort,
    deps.externalAgentAddonSourceConfigPort,
    manifestFileStore,
  )
  private val lookupResolver = GoalPreflightLookupResolver(gateBlockBuilder)

  fun preflight(request: GoalPreflightRequest): GoalPreflightResult {
    GoalPreflightInputValidation.requireInvokedAgentId(request.invokedAgentId)
    GoalPreflightInputValidation.requireOptionalIdentity("agent_override_id", request.agentOverrideId)
    val root = GoalPreflightInputValidation.resolveRepositoryRoot(request.repoRoot, repositoryEnclosingRootPort)
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
      repositoryIdentity = goalRepositoryIdentity(root, repositoryEnclosingRootPort),
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
