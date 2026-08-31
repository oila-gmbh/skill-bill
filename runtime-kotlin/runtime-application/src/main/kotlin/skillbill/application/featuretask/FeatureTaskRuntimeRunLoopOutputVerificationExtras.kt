package skillbill.application.featuretask

import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy

internal fun FeatureTaskRuntimeRunLoop.buildRepositoryCheckpoint(
  run: PhaseRun,
): FeatureTaskRuntimeRepositoryCheckpoint? {
  val resolvedBranch = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
  val goalReviewState = goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
  val revisions = resolveCheckpointRevisions(
    run = run,
    headRevision = resolvedBranch?.branch?.takeIf(String::isNotBlank) ?: "HEAD",
    baseRevision = goalReviewState?.reviewBaseSha ?: resolvedBranch?.reviewBaseSha,
  ) ?: return null
  val ownedPaths = resolveCheckpointOwnedPaths(
    run = run,
    persistedOwnedPaths = resolvedBranch?.workflowOwnedPaths,
    baselineOwnedPaths = resolvedBranch?.baselineOwnedPaths
      ?: goalReviewState?.baselineUntrackedPaths
      ?: resolvedBranch?.baselineUntrackedPaths.orEmpty(),
    revisions = revisions,
  ) ?: return null
  val fingerprint = gitOperations.repositoryCheckpointFingerprint(
    run.request.repoRoot,
    revisions.base,
    revisions.head,
    ownedPaths,
  ).takeIf { it.ok }?.value?.takeIf(String::isNotBlank) ?: return null
  return FeatureTaskRuntimeRepositoryCheckpoint(
    fingerprint = fingerprint,
    baseRef = revisions.base,
    headRef = revisions.head,
    workingTreeOwnedPaths = ownedPaths,
  )
}

internal fun FeatureTaskRuntimeRunLoop.resolveCheckpointOwnedPaths(
  run: PhaseRun,
  persistedOwnedPaths: List<String>?,
  baselineOwnedPaths: List<String>,
  revisions: CheckpointRevisions,
): List<String>? {
  val workingTreePaths = checkpointOwnedPaths(run, baselineOwnedPaths) ?: return null
  val committedPaths = revisions.base?.let { base ->
    gitOperations.runtimePhaseChangedPathsBetweenCommits(run.request.repoRoot, base, revisions.head)
      .takeIf { it.ok }
      ?.value
      ?.let(FeatureTaskRuntimePhaseSafetyPolicy::lineSeparatedPaths)
      ?: return null
  }.orEmpty()
  // Before a checkpoint has decided ownership the working tree is the only listing there is, so it
  // bootstraps the scope. Once a checkpoint has decided, that decision bounds the scope — it already
  // absorbed what the writing phases wrote, so nothing of this run's work is dropped, and ambient
  // dirt can no longer shift the digest a consumer compares against.
  val durableInventory = persistedOwnedPaths.orEmpty().filter(String::isNotBlank)
  val discovered = if (checkpointOwnershipDecided && durableInventory.isNotEmpty()) {
    durableInventory
  } else {
    (durableInventory + workingTreePaths).distinct()
  }
  val inventory = reconcileCheckpointPathInventory(
    repoRoot = run.request.repoRoot,
    issueKey = run.request.issueKey,
    specReference = run.request.runInvariants.specReference,
    paths = (discovered + committedPaths).distinct(),
  ).sorted()
  return inventory.takeIf {
    recorder.recordWorkflowOwnedPaths(
      run.request.workflowId,
      inventory,
      run.request.dbPathOverride,
    )
  }
}

internal fun FeatureTaskRuntimeRunLoop.resolveCheckpointRevisions(
  run: PhaseRun,
  headRevision: String,
  baseRevision: String?,
): CheckpointRevisions? {
  val immutableHead = gitOperations.resolveCommit(run.request.repoRoot, headRevision)
    .takeIf { it.ok }?.value?.takeIf(String::isNotBlank)
    ?: gitOperations.headCommitSha(run.request.repoRoot).takeIf { it.ok }?.value?.takeIf(String::isNotBlank)
    ?: return null
  val immutableBase = baseRevision?.let { revision ->
    gitOperations.resolveCommit(run.request.repoRoot, revision)
      .takeIf { it.ok }?.value?.takeIf(String::isNotBlank)
      ?: revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) }
  }
  if (baseRevision != null && immutableBase == null) return null
  return CheckpointRevisions(base = immutableBase, head = immutableHead)
}

internal fun FeatureTaskRuntimeRunLoop.checkpointOwnedPaths(
  run: PhaseRun,
  baselineOwnedPaths: List<String>,
): List<String>? {
  val owned = gitOperations.repositoryOwnedPaths(run.request.repoRoot)
  if (!owned.ok) return null
  val baseline = baselineOwnedPaths.toSet()
  val paths = owned.value.orEmpty()
    .split(OWNED_PATH_DELIMITER)
    .map(String::trim)
    .filter(String::isNotBlank)
    .filterNot { it in baseline }
    .filterNot { path -> isFeatureSpecPathForIssue(path, run.request.issueKey) }
    .distinct()
    .sorted()
  if (paths.size > MAX_CHECKPOINT_OWNED_PATHS) {
    val declaration = run.declaration.projectionDeclarations.first { projection ->
      projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
    }
    throw InvalidFeatureTaskRuntimeHandoffProjectionError(
      context = InvalidFeatureTaskRuntimeHandoffProjectionContext(
        workflowId = run.request.workflowId,
        consumerPhaseId = run.phaseId,
        projectionName = declaration.projectionName,
        projectionContractId = declaration.projectionContractId,
        projectionContractVersion = declaration.projectionContractVersion,
        failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW,
        reason = "the scoped owned-path inventory holds ${paths.size} entries, over the " +
          "$MAX_CHECKPOINT_OWNED_PATHS-entry checkpoint limit; narrow the run scope or commit " +
          "unrelated working-tree changes before relaunching",
      ),
    )
  }
  return paths
}
