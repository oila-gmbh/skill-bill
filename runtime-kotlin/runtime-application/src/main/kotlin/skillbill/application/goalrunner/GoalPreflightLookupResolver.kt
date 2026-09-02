package skillbill.application.goalrunner

import skillbill.application.continuation.model.GoalContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.goalrunner.model.GoalPreflightLookupInput
import skillbill.application.goalrunner.model.GoalPreflightRequest
import skillbill.application.goalrunner.model.GoalPreflightResult
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

class GoalPreflightLookupResolver(
  private val gateBlockBuilder: GoalPreflightGateBlockBuilder,
) {
  fun resolve(input: GoalPreflightLookupInput): GoalPreflightResult = when (input.lookup) {
    FeatureTaskContinuationLookupResult.NoMatch ->
      noMatchResult(input.normalizedIssueKey, input.manifest, input.request, input.root, input.manifestState)
    is FeatureTaskContinuationLookupResult.Resumable ->
      resumableResult(input.lookup, input.normalizedIssueKey, input.manifestState, input.request, input.root)
    is FeatureTaskContinuationLookupResult.AlreadyRunning ->
      alreadyRunningResult(input.lookup, input.normalizedIssueKey)
    is FeatureTaskContinuationLookupResult.Ambiguous -> ambiguousResult(input.lookup, input.normalizedIssueKey)
    is FeatureTaskContinuationLookupResult.TerminalOnly -> terminalOnlyResult(input.lookup, input.normalizedIssueKey)
    is FeatureTaskContinuationLookupResult.GoalContinuation ->
      goalContinuationResult(
        input.normalizedIssueKey,
        input.lookup.candidate,
        input.manifestState,
        input.request,
        input.root,
      )
    is FeatureTaskContinuationLookupResult.NeedsIdentityRepair ->
      throw InvalidFeatureTaskExecutionIdentitySchemaError(input.lookup.workflowId, input.lookup.summary)
  }

  private fun noMatchResult(
    issueKey: String,
    manifest: DecompositionManifest?,
    request: GoalPreflightRequest,
    root: Path,
    manifestState: GoalRunnerManifestState?,
  ): GoalPreflightResult {
    val activeManifest = manifest?.takeUnless { it.status in setOf("complete", "skipped") }
    activeManifest?.let { gateBlockBuilder.governedSpecPreflightViolation(it, root)?.let { throw it } }
    return GoalPreflightResult(
      verdict = "new_work",
      issueKey = issueKey,
      gateBlock = activeManifest?.let {
        gateBlockBuilder.build(request, it, root, manifestState?.parentWorkflowId)
      },
      rehydrateTargets = activeManifest?.let { gateBlockBuilder.rehydrateTargets(root, it) }.orEmpty(),
      manifestMissing = manifest == null,
    )
  }

  private fun resumableResult(
    lookup: FeatureTaskContinuationLookupResult.Resumable,
    issueKey: String,
    manifestState: GoalRunnerManifestState?,
    request: GoalPreflightRequest,
    root: Path,
  ): GoalPreflightResult = GoalPreflightResult(
    verdict = "resumable",
    issueKey = issueKey,
    candidate = lookup.candidate,
    gateBlock = manifestState?.manifest?.let {
      gateBlockBuilder.governedSpecPreflightViolation(it, root)?.let { violation -> throw violation }
      gateBlockBuilder.build(request, it, root, manifestState.parentWorkflowId)
    },
    rehydrateTargets = manifestState?.manifest?.let { gateBlockBuilder.rehydrateTargets(root, it) }.orEmpty(),
  )

  private fun alreadyRunningResult(
    lookup: FeatureTaskContinuationLookupResult.AlreadyRunning,
    issueKey: String,
  ): GoalPreflightResult = GoalPreflightResult(
    verdict = "already_running",
    issueKey = issueKey,
    candidate = lookup.candidate,
  )

  private fun ambiguousResult(
    lookup: FeatureTaskContinuationLookupResult.Ambiguous,
    issueKey: String,
  ): GoalPreflightResult = GoalPreflightResult(
    verdict = "ambiguous",
    issueKey = issueKey,
    candidates = lookup.candidates,
  )

  private fun terminalOnlyResult(
    lookup: FeatureTaskContinuationLookupResult.TerminalOnly,
    issueKey: String,
  ): GoalPreflightResult = GoalPreflightResult(
    verdict = "terminal_only",
    issueKey = issueKey,
    candidates = lookup.candidates,
  )

  private fun goalContinuationResult(
    issueKey: String,
    candidate: GoalContinuationCandidate,
    manifestState: GoalRunnerManifestState?,
    request: GoalPreflightRequest,
    root: Path,
  ): GoalPreflightResult {
    val manifest = manifestState?.manifest ?: throw InvalidDecompositionManifestSchemaError(
      sourceLabel = issueKey,
      reason = "goal continuation has no readable decomposition manifest",
      failureCode = "missing_manifest",
    )
    gateBlockBuilder.governedSpecPreflightViolation(manifest, root)?.let { throw it }
    return GoalPreflightResult(
      verdict = "goal_continuation",
      issueKey = issueKey,
      goal = candidate,
      gateBlock = gateBlockBuilder.build(request, manifest, root, candidate.parentWorkflowId),
      rehydrateTargets = gateBlockBuilder.rehydrateTargets(root, manifest),
    )
  }
}
