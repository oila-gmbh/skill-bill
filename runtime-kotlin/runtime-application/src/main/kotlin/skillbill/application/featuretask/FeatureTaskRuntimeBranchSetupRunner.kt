package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.workflow.repoRoot
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.captureGoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

/**
 * Owns the distinct pre-`implement` run-setup step that guarantees a non-default feature branch is
 * checked out before any file-mutating phase runs. Git side effects go only through the injected
 * [WorkflowGitOperations] port (no filesystem/process IO here); the resolved branch is persisted
 * through the [FeatureTaskRuntimePhaseRecorder] so resume re-attaches to the same branch.
 *
 * Idempotency: a persisted branch is re-attached by reconciling the real git HEAD with it — a
 * no-op when HEAD already sits on it, a single checkout otherwise (never creating a second/divergent
 * branch); a fresh resolution is persisted exactly once. Loud-fail: a failed/non-landing checkout,
 * an unreadable current branch, a resolution that lands on a protected branch, or a resolved branch
 * that could not be durably recorded returns [FeatureTaskRuntimeBranchSetupOutcome.Blocked] rather
 * than letting any file-mutating phase proceed on the default branch.
 */
@Inject
class FeatureTaskRuntimeBranchSetupRunner(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val gitOperations: WorkflowGitOperations,
) {
  internal fun ensureFeatureBranch(
    request: FeatureTaskRuntimeRunRequest,
    observability: FeatureTaskRuntimeRunObservability,
  ): FeatureTaskRuntimeBranchSetupOutcome {
    val current = gitOperations.currentBranch(request.repoRoot)
    if (!current.ok) {
      return FeatureTaskRuntimeBranchSetupOutcome.blocked(branchSetupBlockedReason(current.error))
    }
    val persisted = recorder.loadResolvedBranch(request.workflowId, request.dbPathOverride)
    return when {
      persisted != null -> reattachPersisted(request, observability, persisted.branch, current.value)
      request.goalContinuation != null -> reattachGoalContinuationBranch(request, observability, current.value)
      else -> resolveAndEstablish(request, observability, current.value)
    }
  }

  private fun reattachGoalContinuationBranch(
    request: FeatureTaskRuntimeRunRequest,
    observability: FeatureTaskRuntimeRunObservability,
    currentBranch: String,
  ): FeatureTaskRuntimeBranchSetupOutcome {
    val decision = FeatureTaskRuntimeBranchSetup.goalContinuationDecision(
      requireNotNull(request.goalContinuation).goalBranch,
    )
    return when (decision) {
      is FeatureTaskRuntimeBranchDecisionInvalid ->
        FeatureTaskRuntimeBranchSetupOutcome.blocked(branchSetupDeriveBlockedReason(decision.reason))
      is FeatureTaskRuntimeBranchDecisionResolved -> {
        val blockedReason = reattachBlockedReason(request, decision.branch, currentBranch)
        blockedReason?.let(FeatureTaskRuntimeBranchSetupOutcome::blocked)
          ?: establishBranch(request, observability, decision.branch, baseBranch = null, created = false)
      }
    }
  }

  // Resume re-attach: reconcile the real git HEAD with the persisted branch. When HEAD already sits
  // on the persisted branch this is a no-op; otherwise it checks out the persisted branch and only
  // returns established once HEAD is actually on it, blocking loudly if the checkout fails or HEAD
  // does not land on the persisted branch. Never returns established while HEAD is on a different
  // (possibly default/protected) branch.
  private fun reattachPersisted(
    request: FeatureTaskRuntimeRunRequest,
    observability: FeatureTaskRuntimeRunObservability,
    persistedBranch: String,
    currentBranch: String,
  ): FeatureTaskRuntimeBranchSetupOutcome {
    val blockedReason = reattachBlockedReason(request, persistedBranch, currentBranch)
    return blockedReason?.let(FeatureTaskRuntimeBranchSetupOutcome::blocked) ?: run {
      observability.branchResolved(SETUP_GUARD_PHASE, persistedBranch, created = false, reused = true)
      FeatureTaskRuntimeBranchSetupOutcome.established(persistedBranch)
    }
  }

  // Reconciles the real HEAD with the persisted branch, returning a block reason when reconciliation
  // fails. A no-op when HEAD already sits on the persisted branch; otherwise it first proves the
  // persisted branch still exists (refusing to let checkout create a second/divergent branch off the
  // current HEAD) and only then checks it out, requiring HEAD to actually land on it.
  private fun reattachBlockedReason(
    request: FeatureTaskRuntimeRunRequest,
    persistedBranch: String,
    currentBranch: String,
  ): String? {
    val normalizedPersisted = persistedBranch.trim()
    return if (currentBranch.trim() == normalizedPersisted) {
      FeatureTaskRuntimeBranchSetup.protectedBranchName(normalizedPersisted)
        ?.let(::branchSetupReattachProtectedReason)
    } else {
      persistedBranchUnusableReason(request, persistedBranch, currentBranch)
        ?: checkoutAndConfirmReason(request, persistedBranch, currentBranch)
    }
  }

  // Proves the persisted branch still exists before any checkout, refusing to let checkout create a
  // second/divergent branch off the current HEAD; returns a block reason when existence is
  // unreadable or the branch is gone, else null.
  private fun persistedBranchUnusableReason(
    request: FeatureTaskRuntimeRunRequest,
    persistedBranch: String,
    currentBranch: String,
  ): String? {
    val exists = gitOperations.branchExists(request.repoRoot, persistedBranch)
    return when {
      !exists.ok -> branchSetupReattachExistenceUnreadableReason(persistedBranch, currentBranch, exists.error)
      exists.value.trim() != "true" -> branchSetupReattachMissingReason(persistedBranch, currentBranch)
      else -> null
    }
  }

  // Checks out the (already-proven-existing) persisted branch and requires HEAD to land on it;
  // returns a block reason when the checkout fails or HEAD does not land on it, else null.
  private fun checkoutAndConfirmReason(
    request: FeatureTaskRuntimeRunRequest,
    persistedBranch: String,
    currentBranch: String,
  ): String? {
    val checkout = gitOperations.checkoutBranch(request.repoRoot, persistedBranch, baseBranch = null)
    return if (!checkout.ok) {
      branchSetupReattachBlockedReason(persistedBranch, currentBranch, checkout.error)
    } else {
      landedBranchBlockedReason(request, persistedBranch)
    }
  }

  private fun resolveAndEstablish(
    request: FeatureTaskRuntimeRunRequest,
    observability: FeatureTaskRuntimeRunObservability,
    currentBranch: String,
  ): FeatureTaskRuntimeBranchSetupOutcome {
    val decision = FeatureTaskRuntimeBranchSetup.decide(
      issueKey = request.issueKey,
      specReference = request.runInvariants.specReference,
      currentBranch = currentBranch,
    )
    return when (decision) {
      is FeatureTaskRuntimeBranchDecisionInvalid ->
        FeatureTaskRuntimeBranchSetupOutcome.blocked(branchSetupDeriveBlockedReason(decision.reason))
      is FeatureTaskRuntimeBranchDecisionResolved ->
        if (decision.create) {
          createAndSwitch(request, observability, decision.branch, requireNotNull(decision.baseBranch))
        } else {
          establishBranch(request, observability, decision.branch, baseBranch = null, created = false)
        }
    }
  }

  private fun createAndSwitch(
    request: FeatureTaskRuntimeRunRequest,
    observability: FeatureTaskRuntimeRunObservability,
    branch: String,
    baseBranch: String,
  ): FeatureTaskRuntimeBranchSetupOutcome {
    val checkout = gitOperations.checkoutBranch(request.repoRoot, branch, baseBranch)
    if (!checkout.ok) {
      return FeatureTaskRuntimeBranchSetupOutcome.blocked(
        branchSetupCreateBlockedReason(branch, baseBranch, checkout.error),
      )
    }
    return landedBranchBlockedReason(request, branch)?.let(FeatureTaskRuntimeBranchSetupOutcome::blocked)
      ?: establishBranch(request, observability, branch, baseBranch, created = true)
  }

  // Re-confirms the actual working-tree branch after a checkout reported ok: re-reads currentBranch
  // and blocks loudly when HEAD did not land on [expectedBranch] or landed on a protected branch.
  private fun landedBranchBlockedReason(request: FeatureTaskRuntimeRunRequest, expectedBranch: String): String? {
    val landed = gitOperations.currentBranch(request.repoRoot)
    if (!landed.ok) {
      return branchSetupBlockedReason(landed.error)
    }
    val landedBranch = landed.value.trim()
    return when {
      landedBranch != expectedBranch.trim() ->
        "Feature-task-runtime checkout reported success but HEAD is on '$landedBranch', not the " +
          "expected feature branch '$expectedBranch'; refusing to run file-mutating phases."
      FeatureTaskRuntimeBranchSetup.protectedBranchName(landedBranch) != null ->
        "Feature-task-runtime landed on a protected branch '$landedBranch' for the feature branch; " +
          "refusing to run file-mutating phases on a protected branch."
      else -> null
    }
  }

  private fun establishBranch(
    request: FeatureTaskRuntimeRunRequest,
    observability: FeatureTaskRuntimeRunObservability,
    branch: String,
    baseBranch: String?,
    created: Boolean,
  ): FeatureTaskRuntimeBranchSetupOutcome {
    val baseline = gitOperations.captureGoalSubtaskReviewBaseline(request.repoRoot, branch)
    if (!baseline.ok) {
      return FeatureTaskRuntimeBranchSetupOutcome.blocked(
        "Feature-task-runtime could not capture its immutable review base before implementation: ${baseline.error}",
      )
    }
    val immutableBase = requireNotNull(baseline.baseline)
    val recorded = recorder.recordResolvedBranch(
      request.workflowId,
      FeatureTaskRuntimeResolvedBranch(
        branch = branch,
        baseBranch = baseBranch,
        created = created,
        reviewBaseSha = immutableBase.reviewBaseSha,
        baselineUntrackedPaths = immutableBase.baselineUntrackedPaths,
      ),
      request.dbPathOverride,
    )
    if (!recorded) {
      return FeatureTaskRuntimeBranchSetupOutcome.blocked(branchSetupNotPersistedBlockedReason(branch))
    }
    observability.branchResolved(SETUP_GUARD_PHASE, branch, created = created, reused = !created)
    return FeatureTaskRuntimeBranchSetupOutcome.established(branch)
  }

  private companion object {
    // The first file-mutating phase; branch setup is attributed to it as a distinct pre-launch step.
    val SETUP_GUARD_PHASE: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT

    fun branchSetupBlockedReason(error: String): String =
      "Feature-task-runtime could not establish a feature branch: reading the current branch failed" +
        error.detailSuffix() + " Refusing to run file-mutating phases on the default branch."

    fun branchSetupCreateBlockedReason(branch: String, baseBranch: String, error: String): String =
      "Feature-task-runtime could not create/switch to feature branch '$branch' from '$baseBranch'" +
        error.detailSuffix() + " Refusing to run file-mutating phases on the default branch."

    fun branchSetupReattachBlockedReason(branch: String, currentBranch: String, error: String): String =
      "Feature-task-runtime could not re-attach to the persisted feature branch '$branch' from " +
        "'$currentBranch'" + error.detailSuffix() +
        " Refusing to run file-mutating phases on the default branch."

    fun branchSetupReattachMissingReason(branch: String, currentBranch: String): String =
      "Feature-task-runtime could not re-attach to the persisted feature branch '$branch': it no " +
        "longer exists in the repository (HEAD is on '$currentBranch'). Refusing to create a new, " +
        "divergent branch in its place or run file-mutating phases on the default branch; restore " +
        "or recreate '$branch' before resuming."

    fun branchSetupReattachExistenceUnreadableReason(branch: String, currentBranch: String, error: String): String =
      "Feature-task-runtime could not verify whether the persisted feature branch '$branch' still " +
        "exists (HEAD is on '$currentBranch')" + error.detailSuffix() +
        " Refusing to re-attach or run file-mutating phases until existence can be confirmed."

    fun branchSetupReattachProtectedReason(branch: String): String =
      "Feature-task-runtime resolved persisted branch '$branch' is a protected branch. Refusing to " +
        "run file-mutating phases on a protected branch."

    fun branchSetupNotPersistedBlockedReason(branch: String): String =
      "Feature-task-runtime could not durably record the resolved feature branch '$branch' " +
        "(the workflow row is absent), so a resume could not re-attach to it. Refusing to run " +
        "file-mutating phases on the default branch."

    fun branchSetupDeriveBlockedReason(reason: String): String =
      "Feature-task-runtime could not establish a feature branch: $reason Refusing to run " +
        "file-mutating phases on the default branch."

    fun String.detailSuffix(): String = if (isBlank()) "." else " ($this)."
  }
}

/** Outcome of the branch-setup step: an established branch name, or a loud block with a reason. */
internal sealed interface FeatureTaskRuntimeBranchSetupOutcome {
  val establishedBranch: String?
  val blockedReason: String?

  companion object {
    fun established(branch: String): FeatureTaskRuntimeBranchSetupOutcome = Established(branch)

    fun blocked(reason: String): FeatureTaskRuntimeBranchSetupOutcome = Blocked(reason)
  }
}

private data class Established(val branch: String) : FeatureTaskRuntimeBranchSetupOutcome {
  override val establishedBranch: String get() = branch
  override val blockedReason: String? get() = null
}

private data class Blocked(val reason: String) : FeatureTaskRuntimeBranchSetupOutcome {
  override val establishedBranch: String? get() = null
  override val blockedReason: String get() = reason
}
