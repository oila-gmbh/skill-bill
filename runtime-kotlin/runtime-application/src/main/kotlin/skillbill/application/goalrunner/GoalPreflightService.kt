package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.featuretask.model.GoalContinuationCandidate
import skillbill.application.goalrunner.model.GoalPreflightAgentAddon
import skillbill.application.goalrunner.model.GoalPreflightDependency
import skillbill.application.goalrunner.model.GoalPreflightGateBlock
import skillbill.application.goalrunner.model.GoalPreflightRehydrateTarget
import skillbill.application.goalrunner.model.GoalPreflightRequest
import skillbill.application.goalrunner.model.GoalPreflightResult
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
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.decomposition.model.SpecSource.LINEAR

@Inject
@Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount", "LongParameterList")
class GoalPreflightService(
  private val continuationLookup: FeatureTaskContinuationLookupService,
  private val manifestStore: GoalRunnerManifestStore,
  private val agentAddonSelectionPort: AgentAddonSelectionPort,
  private val externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort,
  private val manifestFileStore: DecompositionManifestFileStore,
  private val manifestValidator: DecompositionManifestValidator,
) {
  fun preflight(request: GoalPreflightRequest): GoalPreflightResult {
    if (request.invokedAgentId.isBlank()) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "preflight request",
        "invoked_agent_id is required",
      )
    }
    validateOptionalIdentity("agent_override_id", request.agentOverrideId)
    val root = runCatching {
      request.repoRoot.toAbsolutePath().normalize().toRealPath()
    }.getOrElse {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "preflight request",
        "repository root '${request.repoRoot}' cannot be resolved",
        it,
      )
    }
    val normalizedIssueKey = validateIssueKey(request.issueKey)
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
    if (manifest != null && manifest.issueKey != normalizedIssueKey) {
      throw InvalidDecompositionManifestSchemaError(
        sourceLabel = normalizedIssueKey,
        reason = "manifest issue_key '${manifest.issueKey}' does not match the requested issue key.",
        failureCode = "issue_key_mismatch",
      )
    }
    val receivingAgents = listOfNotNull(
      request.invokedAgentId,
      request.agentOverrideId,
    ).filter(String::isNotBlank).distinct()
    val selection = resolveSelection(
      request = request,
      root = root,
      receivingAgents = receivingAgents,
      parentWorkflowId = manifestState?.parentWorkflowId,
    )
    val lookup = continuationLookup.lookupIfPresent(
      issueKey = normalizedIssueKey,
      repositoryIdentity = goalRepositoryIdentity(root),
      dbOverride = request.dbPathOverride,
    )
    return when (lookup) {
      FeatureTaskContinuationLookupResult.NoMatch -> {
        val activeManifest = manifest?.takeUnless { it.status in setOf("complete", "skipped") }
        GoalPreflightResult(
          verdict = "new_work",
          issueKey = normalizedIssueKey,
          gateBlock = activeManifest?.let {
            gateBlock(request, it, selection, null)
          },
          rehydrateTargets = activeManifest?.let { rehydrateTargets(root, it) }.orEmpty(),
          manifestMissing = manifest == null,
        )
      }
      is FeatureTaskContinuationLookupResult.Resumable -> GoalPreflightResult(
        verdict = "resumable",
        issueKey = normalizedIssueKey,
        candidate = lookup.candidate,
        gateBlock = manifestState?.manifest?.let {
          gateBlock(request, it, selection, manifestState.parentWorkflowId)
        },
        rehydrateTargets = manifestState?.manifest?.let { rehydrateTargets(root, it) }.orEmpty(),
      )
      is FeatureTaskContinuationLookupResult.AlreadyRunning -> GoalPreflightResult(
        verdict = "already_running",
        issueKey = normalizedIssueKey,
        candidate = lookup.candidate,
      )
      is FeatureTaskContinuationLookupResult.Ambiguous -> GoalPreflightResult(
        verdict = "ambiguous",
        issueKey = normalizedIssueKey,
        candidates = lookup.candidates,
      )
      is FeatureTaskContinuationLookupResult.TerminalOnly -> GoalPreflightResult(
        verdict = "terminal_only",
        issueKey = normalizedIssueKey,
        candidates = lookup.candidates,
      )
      is FeatureTaskContinuationLookupResult.GoalContinuation -> goalContinuationResult(
        normalizedIssueKey,
        lookup.candidate,
        manifestState,
        request,
        selection,
        root,
      )
      is FeatureTaskContinuationLookupResult.NeedsIdentityRepair ->
        throw InvalidFeatureTaskExecutionIdentitySchemaError(lookup.workflowId, lookup.summary)
    }
  }

  private fun goalContinuationResult(
    issueKey: String,
    candidate: GoalContinuationCandidate,
    manifestState: GoalRunnerManifestState?,
    request: GoalPreflightRequest,
    selection: HydratedAgentAddonSelection,
    root: Path,
  ): GoalPreflightResult {
    val manifest = manifestState?.manifest ?: throw InvalidDecompositionManifestSchemaError(
      sourceLabel = issueKey,
      reason = "goal continuation has no readable decomposition manifest",
      failureCode = "missing_manifest",
    )
    return GoalPreflightResult(
      verdict = "goal_continuation",
      issueKey = issueKey,
      goal = candidate,
      gateBlock = gateBlock(request, manifest, selection, candidate.parentWorkflowId),
      rehydrateTargets = rehydrateTargets(root, manifest),
    )
  }

  private fun resolveSelection(
    request: GoalPreflightRequest,
    root: Path,
    receivingAgents: List<String>,
    parentWorkflowId: String?,
  ): HydratedAgentAddonSelection {
    val persisted = parentWorkflowId
      ?.takeIf(String::isNotBlank)
      ?.let { manifestStore.reviewPolicy(it, request.dbPathOverride)?.agentAddonSelection }
    if (request.requestedAgentAddonSlugs.isNotEmpty()) {
      if (persisted != null) {
        if (persisted.entries.map { it.slug } != request.requestedAgentAddonSlugs) {
          throw InvalidAgentAddonSelectionError(
            "Cannot change agent add-on selection on goal resume: " +
              "the parent workflow has a different durable selection.",
          )
        }
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

  private fun gateBlock(
    request: GoalPreflightRequest,
    manifest: DecompositionManifest,
    selection: HydratedAgentAddonSelection,
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

  private fun rehydrateTargets(root: Path, manifest: DecompositionManifest): List<GoalPreflightRehydrateTarget> {
    if (manifest.specSource != LINEAR) return emptyList()
    val targets = buildList {
      add(
        GoalPreflightRehydrateTarget(
          issueKey = manifest.issueKey,
          linearIssueId = manifest.issueKey,
          targetPath = relativePath(root, manifest.parentSpecPath),
        ).takeUnless { manifestFileStore.isRegularFileWithoutRecovery(root.resolve(it.targetPath)) },
      )
      manifest.subtasks
        .filterNot { it.status == "complete" || it.status == "skipped" }
        .forEach { subtask ->
          add(
            GoalPreflightRehydrateTarget(
              issueKey = manifest.issueKey,
              linearIssueId = subtask.linearIssueId,
              targetPath = relativePath(root, subtask.specPath),
            ).takeUnless { manifestFileStore.isRegularFileWithoutRecovery(root.resolve(it.targetPath)) },
          )
        }
    }
    return targets.filterNotNull()
  }

  private fun validateIssueKey(issueKey: String): String {
    val normalized = issueKey.trim().uppercase()
    if (!FeatureTaskExecutionIdentityPolicy.ISSUE_KEY_PATTERN.matches(normalized)) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "preflight request",
        "issue_key '$issueKey' is malformed",
      )
    }
    return normalized
  }

  private fun validateOptionalIdentity(field: String, value: String?) {
    if (value?.isBlank() == true) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "preflight request",
        "$field must be omitted when blank",
      )
    }
  }

  private fun relativePath(root: Path, rawPath: String): String {
    val path = Path.of(rawPath)
    val resolved = (if (path.isAbsolute) path else root.resolve(path)).toAbsolutePath().normalize()
    return if (resolved.startsWith(root)) {
      root.relativize(resolved).joinToString("/")
    } else {
      rawPath
    }
  }
}

private fun CodeReviewExecutionMode.displayName(omitted: Boolean): String = when {
  omitted && this == CodeReviewExecutionMode.INLINE -> "inline (default)"
  this == CodeReviewExecutionMode.DELEGATED -> "delegated (experimental)"
  else -> wireValue
}
