package skillbill.application.goalrunner

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.goalrunner.model.GoalPreflightAgentAddon
import skillbill.application.goalrunner.model.GoalPreflightDependency
import skillbill.application.goalrunner.model.GoalPreflightGateBlock
import skillbill.application.goalrunner.model.GoalPreflightRehydrateTarget
import skillbill.application.goalrunner.model.GoalPreflightRequest
import skillbill.application.goalrunner.model.GoalPreflightSubtask
import skillbill.error.InvalidAgentAddonSelectionError
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource.LINEAR
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import java.nio.file.Path

class GoalPreflightGateBlockBuilder(
  private val manifestStore: GoalRunnerManifestStore,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
  private val externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort,
  private val manifestFileStore: DecompositionManifestFileStore,
) {
  fun resolveSelection(
    request: GoalPreflightRequest,
    root: Path,
    receivingAgents: List<String>,
    parentWorkflowId: String?,
  ): HydratedAgentAddonSelection {
    val persisted = parentWorkflowId
      ?.takeIf(String::isNotBlank)
      ?.let { manifestStore.reviewPolicy(it, request.dbPathOverride)?.agentAddonSelection }
    if (request.requestedAgentAddonSlugs.isNotEmpty()) {
      if (persisted != null && persisted.entries.map { it.slug } != request.requestedAgentAddonSlugs) {
        throw InvalidAgentAddonSelectionError(
          "Cannot change agent add-on selection on goal resume: " +
            "the parent workflow has a different durable selection.",
        )
      }
      return agentAddonSelectionPort.resolveInitial(
        repoRoot = root,
        requestedSlugs = request.requestedAgentAddonSlugs,
        consumer = AgentAddonConsumer.BILL_FEATURE,
        receivingAgentIds = receivingAgents,
        externalSourceRoots = externalAgentAddonSourceConfigPort.readExternalAgentAddonSources(
          ExternalAgentAddonSourceConfigRequest(request.userHome, request.environment),
        ).sources.map { it.path },
      )
    }
    return if (persisted == null || persisted.entries.isEmpty()) {
      HydratedAgentAddonSelection()
    } else {
      agentAddonSelectionPort.verifyPersisted(
        persisted,
        AgentAddonConsumer.BILL_FEATURE,
        receivingAgents,
      )
    }
  }

  fun build(
    request: GoalPreflightRequest,
    manifest: DecompositionManifest,
    root: Path,
    parentWorkflowId: String?,
  ): GoalPreflightGateBlock {
    val durablePolicy = parentWorkflowId
      ?.takeIf(String::isNotBlank)
      ?.let { manifestStore.reviewPolicy(it, request.dbPathOverride) }
    val mismatch = durablePolicy?.let {
      goalRunnerReviewPolicyMismatch(
        parentWorkflowId = parentWorkflowId.orEmpty(),
        requestedReviewMode = request.requestedReviewMode,
        persisted = it,
      )
    }
    if (mismatch != null) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError("goal preflight", mismatch)
    }
    val effectiveReviewPolicy = effectiveGoalRunnerReviewPolicy(
      request.requestedReviewMode,
      durablePolicy,
    )
    val selectionResult = GoalRunnerPlanner.selectNext(manifest)
    val firstRunnable = when (selectionResult) {
      is GoalRunnerSelection.Run -> selectionResult.decision.subtask.id
      is GoalRunnerSelection.Blocked -> null
      GoalRunnerSelection.Done -> null
    }
    val selection = resolveSelection(
      request = request,
      root = root,
      receivingAgents = listOfNotNull(
        request.invokedAgentId,
        request.agentOverrideId,
      ).filter(String::isNotBlank).distinct(),
      parentWorkflowId = parentWorkflowId,
    )
    return GoalPreflightGateBlock(
      issueKey = manifest.issueKey,
      featureName = manifest.featureName,
      subtasks = manifest.subtasks.map(::subtaskBlock),
      expectedFirstRunnableSubtask = firstRunnable,
      childAgent = request.agentOverrideId?.takeIf(String::isNotBlank) ?: request.invokedAgentId,
      childAgentOverride = request.agentOverrideId?.takeIf(String::isNotBlank),
      reviewMode = effectiveReviewPolicy.codeReviewMode.displayName(request.requestedReviewMode == null),
      agentAddons = selection.entries.map { entry ->
        GoalPreflightAgentAddon(
          slug = entry.persisted.slug,
          description = entry.description,
        )
      },
    )
  }

  fun rehydrateTargets(root: Path, manifest: DecompositionManifest): List<GoalPreflightRehydrateTarget> {
    if (manifest.specSource != LINEAR) return emptyList()
    return missingGovernedSpecPaths(root, manifest, manifestFileStore).map { targetPath ->
      GoalPreflightRehydrateTarget(
        issueKey = manifest.issueKey,
        linearIssueId = manifest.issueKey,
        targetPath = targetPath,
      )
    }
  }

  fun governedSpecPreflightViolation(
    manifest: DecompositionManifest,
    root: Path,
  ): InvalidDecompositionManifestSchemaError? = governedSpecPreflightViolation(manifest, root, manifestFileStore)

  private fun subtaskBlock(subtask: DecompositionSubtask): GoalPreflightSubtask = GoalPreflightSubtask(
    id = subtask.id,
    name = subtask.name,
    status = subtask.status,
    dependencies = subtask.dependencies.map { dependency ->
      GoalPreflightDependency(
        subtaskId = dependency.subtaskId,
        optional = dependency.optional,
        skipped = dependency.skipped,
        note = if (dependency.optional) {
          "optional dependency on subtask ${dependency.subtaskId}" +
            if (dependency.skipped) " is skipped" else ""
        } else {
          "requires subtask ${dependency.subtaskId}"
        },
      )
    },
  )
}

private fun CodeReviewExecutionMode.displayName(omitted: Boolean): String = when {
  omitted && this == CodeReviewExecutionMode.INLINE -> "inline (default)"
  this == CodeReviewExecutionMode.DELEGATED -> "delegated (experimental)"
  else -> wireValue
}
