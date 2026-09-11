package skillbill.engine.featuretask

import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError

internal data class SubtaskMigrationRefusalRequest(
  val precedingPhaseId: String,
  val branch: String,
  val blockedReason: (String, String) -> String,
  val reason: String,
  val cause: Throwable? = null,
)

internal fun refuseSubtaskMigration(
  runLoop: FeatureTaskRuntimeRunLoop,
  request: SubtaskMigrationRefusalRequest,
): Boolean {
  val error = FeatureTaskRuntimeSubtaskCommitReconciliationError(
    workflowId = runLoop.request.workflowId,
    issueKey = runLoop.request.issueKey,
    subtaskId = runLoop.request.goalContinuation?.subtaskId?.toString() ?: "unknown",
    reason = request.reason,
    cause = request.cause,
  )
  runCatching {
    runLoop.diagnostics.warning(
      "record_kind=${if (request.reason.contains("normaliz")) "migration" else "refusal"} " +
        "seam=FeatureTaskRuntimeSubtaskCommitMigrationNormalizer value_used='${request.branch}' " +
        "value_expected=provable active subtask commit span cause=${request.reason}",
      error,
    )
  }
  return runLoop.collaborators.checkpointContinued6.blockCheckpoint(
    runLoop,
    request.precedingPhaseId,
    request.branch,
    error.message.orEmpty(),
    request.blockedReason,
  )
}

internal fun refusalRequest(
  precedingPhaseId: String,
  branch: String,
  blockedReason: (String, String) -> String,
  reason: String,
  cause: Throwable? = null,
) = SubtaskMigrationRefusalRequest(precedingPhaseId, branch, blockedReason, reason, cause)
