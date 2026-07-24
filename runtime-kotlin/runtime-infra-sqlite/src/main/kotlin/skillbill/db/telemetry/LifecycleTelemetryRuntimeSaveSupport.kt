package skillbill.db.telemetry

import skillbill.contracts.JsonSupport
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import java.sql.Connection

fun saveFeatureTaskRuntimeStarted(connection: Connection, record: FeatureTaskRuntimeStartedRecord) {
  if (rowExists(connection, "feature_task_runtime_sessions", record.sessionId)) {
    updateFeatureTaskRuntimeStarted(connection, record)
    return
  }
  connection.prepareStatement(
    """
    INSERT INTO feature_task_runtime_sessions (
      session_id, feature_size, issue_key, feature_name
    ) VALUES (?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.sessionId,
      record.featureSize,
      record.issueKey,
      record.featureName,
    )
    statement.executeUpdate()
  }
}

private fun updateFeatureTaskRuntimeStarted(connection: Connection, record: FeatureTaskRuntimeStartedRecord) {
  connection.prepareStatement(
    """
    UPDATE feature_task_runtime_sessions SET
      feature_size = ?,
      issue_key = ?,
      feature_name = ?
    WHERE session_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.featureSize,
      record.issueKey,
      record.featureName,
      record.sessionId,
    )
    statement.executeUpdate()
  }
}

fun saveFeatureTaskRuntimeFinished(
  connection: Connection,
  record: FeatureTaskRuntimeFinishedRecord,
): TerminalSaveOutcome {
  val completedPhaseIdsJson = listJson(record.completedPhaseIds)
  val phaseOutcomesJson = JsonSupport.mapToJsonString(record.phaseOutcomes)
  if (rowExists(connection, "feature_task_runtime_sessions", record.sessionId)) {
    if (lifecycleAlreadyFinished(connection, "feature_task_runtime_sessions", record.sessionId)) {
      incrementDuplicateTerminalFinishedEvents(connection, "feature_task_runtime_sessions", record.sessionId)
      return TerminalSaveOutcome.DUPLICATE
    }
    updateFeatureTaskRuntimeFinished(connection, record, completedPhaseIdsJson, phaseOutcomesJson)
  } else {
    insertFeatureTaskRuntimeFinished(connection, record, completedPhaseIdsJson, phaseOutcomesJson)
  }
  return TerminalSaveOutcome.FIRST_TERMINAL
}

private fun updateFeatureTaskRuntimeFinished(
  connection: Connection,
  record: FeatureTaskRuntimeFinishedRecord,
  completedPhaseIdsJson: String,
  phaseOutcomesJson: String,
) {
  connection.prepareStatement(
    """
    UPDATE feature_task_runtime_sessions SET
      completion_status = ?,
      completed_phase_ids = ?,
      phase_outcomes = ?,
      last_incomplete_phase = ?,
      blocked_reason = ?,
      resolved_branch = ?,
      review_fix_iteration_count = ?,
      audit_gap_iteration_count = ?,
      audit_first_pass_convergence = ?,
      audit_recurring_gap_count = ?,
      audit_new_gap_count = ?,
      audit_attempted_repair_item_count = ?,
      audit_resolved_repair_item_count = ?,
      regeneration_activation_count = ?,
      regeneration_attempt_count = ?,
      regeneration_outcome_counts_json = ?,
      estimated_phase_tokens_json = ?,
      estimated_total_tokens = ?,
      finished_at = CURRENT_TIMESTAMP
    WHERE session_id = ?
      AND (finished_event_emitted_at IS NULL OR completion_status = 'stale')
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.completionStatus,
      completedPhaseIdsJson,
      phaseOutcomesJson,
      record.lastIncompletePhase,
      record.blockedReason,
      record.resolvedBranch,
      record.reviewFixIterationCount,
      record.auditGapIterationCount,
      record.auditFirstPassConvergence,
      record.auditRecurringGapCount,
      record.auditNewGapCount,
      record.auditAttemptedRepairItemCount,
      record.auditResolvedRepairItemCount,
      record.regenerationActivationCount,
      record.regenerationAttemptCount,
      regenerationOutcomeCountsJson(record),
      record.estimatedPhaseTokenBreakdownJson,
      record.estimatedTotalTokens,
      record.sessionId,
    )
    statement.executeUpdate()
  }
}

private fun regenerationOutcomeCountsJson(record: FeatureTaskRuntimeFinishedRecord): String? =
  record.regenerationOutcomeCounts.takeIf { it.isNotEmpty() }?.let { JsonSupport.mapToJsonString(it) }

private fun insertFeatureTaskRuntimeFinished(
  connection: Connection,
  record: FeatureTaskRuntimeFinishedRecord,
  completedPhaseIdsJson: String,
  phaseOutcomesJson: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO feature_task_runtime_sessions (
      session_id, completion_status, completed_phase_ids,
      phase_outcomes, last_incomplete_phase, blocked_reason,
      resolved_branch, review_fix_iteration_count, audit_gap_iteration_count,
      audit_first_pass_convergence, audit_recurring_gap_count, audit_new_gap_count,
      audit_attempted_repair_item_count, audit_resolved_repair_item_count,
      regeneration_activation_count, regeneration_attempt_count, regeneration_outcome_counts_json,
      estimated_phase_tokens_json, estimated_total_tokens, finished_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.sessionId,
      record.completionStatus,
      completedPhaseIdsJson,
      phaseOutcomesJson,
      record.lastIncompletePhase,
      record.blockedReason,
      record.resolvedBranch,
      record.reviewFixIterationCount,
      record.auditGapIterationCount,
      record.auditFirstPassConvergence,
      record.auditRecurringGapCount,
      record.auditNewGapCount,
      record.auditAttemptedRepairItemCount,
      record.auditResolvedRepairItemCount,
      record.regenerationActivationCount,
      record.regenerationAttemptCount,
      regenerationOutcomeCountsJson(record),
      record.estimatedPhaseTokenBreakdownJson,
      record.estimatedTotalTokens,
    )
    statement.executeUpdate()
  }
}
