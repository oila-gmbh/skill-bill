package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity

internal fun resolveCheckpointRefCommit(
  gitOperations: WorkflowGitOperations,
  repoRoot: java.nio.file.Path,
  checkpointRef: String,
): String? {
  val resolved = gitOperations.resolveCheckpointRef(
    repoRoot,
    FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
    checkpointRef,
  )
  if (!resolved.ok) return null
  return resolved.value.orEmpty().trim().takeIf(String::isNotBlank)
}

internal val remediationBlockedCause: (String?, Boolean, String?) -> String = { stored, storedResolves, failedRef ->
  when {
    stored != null && !storedResolves ->
      "stored remediation_base_sha '$stored' did not resolve to a commit"
    failedRef != null ->
      "checkpoint ref '$failedRef' did not resolve to a commit"
    else -> "no review_fix checkpoint ref resolved to a commit"
  }
}

internal val latestReviewFixCheckpointRef: (List<FeatureTaskRuntimeCheckpointIdentity>) -> String? = { checkpoints ->
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
