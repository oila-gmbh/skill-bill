package skillbill.application.featuretask

import skillbill.application.featuretask.model.RemediationReconcileSnapshot
import skillbill.application.featuretask.model.RemediationReconciliationBlocked
import skillbill.application.featuretask.model.RemediationReconciliationCoherent
import skillbill.application.featuretask.model.RemediationReconciliationDecision
import skillbill.application.featuretask.model.RemediationReconciliationHeal
import skillbill.application.featuretask.model.ResolvedReviewFixCheckpoint
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import java.nio.file.Path

internal fun latestResolvedReviewFixCheckpointCommit(
  checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): ResolvedReviewFixCheckpoint? = checkpoints
  .asReversed()
  .firstNotNullOfOrNull { identity ->
    if (identity.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
      return@firstNotNullOfOrNull null
    }
    resolveCheckpointRefCommit(gitOperations, repoRoot, identity.checkpointRef)
      ?.let { ResolvedReviewFixCheckpoint(identity, it) }
  }

fun resolvesCommit(gitOperations: WorkflowGitOperations, repoRoot: Path, sha: String): Boolean {
  val resolved = gitOperations.resolveCommit(repoRoot, sha.trim())
  if (!resolved.ok) throw remediationGitFailure("commit '$sha' could not be read (${resolved.error})")
  if (resolved.value.orEmpty().trim().isBlank()) {
    throw remediationGitFailure("commit '$sha' resolved to a blank Git object")
  }
  return true
}

fun resolveCheckpointRefCommit(gitOperations: WorkflowGitOperations, repoRoot: Path, checkpointRef: String): String? {
  val resolved = gitOperations.resolveCheckpointRef(
    repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    checkpointRef,
  )
  if (!resolved.ok) throw remediationGitFailure("checkpoint ref could not be read (${resolved.error})")
  val value = resolved.value.orEmpty().trim()
  if (value.isBlank()) throw remediationGitFailure("checkpoint ref '$checkpointRef' resolved to a blank Git object")
  return value
}

val remediationBlockedCause: (String?, Boolean, String?) -> String = { stored, storedResolves, failedRef ->
  when {
    stored != null && !storedResolves ->
      "stored remediation_base_sha '$stored' did not resolve to a commit"
    failedRef != null ->
      "checkpoint ref '$failedRef' did not resolve to a commit"
    else -> "no review_fix checkpoint ref resolved to a commit"
  }
}

val latestReviewFixCheckpointRef: (List<FeatureTaskRuntimeCheckpointIdentity>) -> String? = { checkpoints ->
  checkpoints
    .asReversed()
    .firstOrNull { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
    ?.checkpointRef
}

internal fun remediationBaseRecoveryEvidenceEntry(
  recovery: RemediationBaseRecovery,
  signal: RemediationDegradationSignal = RemediationDegradationSignal(),
): LinkedHashMap<String, Any?> {
  val failureMessage = recovery.failureMessageOverride ?: run {
    val headDetail = recovery.headSha?.takeIf(String::isNotBlank)?.let { " at HEAD '$it'" }.orEmpty()
    "Resume reconciled remediation_base_sha (${recovery.reason}) so the recorded base stays reachable " +
      "from branch '${recovery.goalBranch}'$headDetail."
  }
  return linkedMapOf<String, Any?>(
    "original_sha" to recovery.originalSha,
    "replacement_sha" to recovery.replacementSha,
    "repointed_field" to GoalReviewBaseField.REMEDIATION_BASE.wireValue,
    "failure_reason" to recovery.reason,
    "failure_message" to failureMessage,
    "goal_branch" to recovery.goalBranch,
  ).also { entry ->
    signal.seam?.let { entry["seam"] = it }
    signal.valueUsed?.let { entry["value_used"] = it }
    signal.valueExpected?.let { entry["value_expected"] = it }
    signal.cause?.let { entry["cause"] = it }
  }
}

internal fun decideRemediationReconciliation(
  snapshot: RemediationReconcileSnapshot,
  latestRemediationResolved: ResolvedReviewFixCheckpoint?,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): RemediationReconciliationDecision {
  val stored = snapshot.state.remediationBaseSha
  val storedResolves = stored?.let { resolvesCommit(gitOperations, repoRoot, it) } == true
  return when {
    latestRemediationResolved != null &&
      (stored == null || isStrictAncestor(gitOperations, repoRoot, stored, latestRemediationResolved.sha)) ->
      RemediationReconciliationHeal(latestRemediationResolved.sha)
    stored != null && storedResolves ->
      storedBranchReconciliation(gitOperations, repoRoot, stored, latestRemediationResolved)
    latestRemediationResolved != null -> RemediationReconciliationHeal(latestRemediationResolved.sha)
    stored != null && !storedResolves -> RemediationReconciliationBlocked
    else -> RemediationReconciliationBlocked
  }
}

private fun storedBranchReconciliation(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  stored: String,
  latestRemediationResolved: ResolvedReviewFixCheckpoint?,
): RemediationReconciliationDecision {
  val head = gitOperations.headCommitSha(repoRoot)
  if (!head.ok || head.value.isBlank()) throw remediationGitFailure("HEAD could not be read (${head.error})")
  val headSha = head.value.trim()
  val onBranch = gitOperations.isCommitAncestor(repoRoot, stored, headSha)
  return when {
    !onBranch.ok -> throw remediationGitFailure("ancestry could not be read (${onBranch.error})")
    onBranch.value == "true" -> RemediationReconciliationCoherent
    latestRemediationResolved != null -> RemediationReconciliationHeal(latestRemediationResolved.sha)
    else -> RemediationReconciliationBlocked
  }
}

private fun isStrictAncestor(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  ancestor: String,
  descendant: String,
): Boolean {
  if (ancestor == descendant) return false
  val ancestry = gitOperations.isCommitAncestor(repoRoot, ancestor, descendant)
  if (!ancestry.ok) throw remediationGitFailure("ancestry could not be read (${ancestry.error})")
  return ancestry.value == "true"
}

internal fun remediationGitFailure(reason: String) = FeatureTaskRuntimeSubtaskCommitReconciliationError(
  workflowId = "unknown",
  issueKey = "unknown",
  subtaskId = "unknown",
  reason = "$reason; operator decision: repair Git access before resuming",
)
