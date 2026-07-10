package skillbill.db.telemetry

import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import java.sql.Connection

fun saveFeatureImplementStarted(connection: Connection, record: FeatureImplementStartedRecord) {
  connection.prepareStatement(
    """
    INSERT INTO feature_implement_sessions (
      session_id, source, issue_key_provided, issue_key_type, spec_input_types,
      spec_word_count, feature_size, feature_name, rollout_needed,
      acceptance_criteria_count, open_questions_count, spec_summary, started_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.sessionId,
      record.source,
      record.issueKeyProvided.toSqlInt(),
      record.issueKeyType,
      listJson(record.specInputTypes),
      record.specWordCount,
      record.featureSize,
      record.featureName,
      record.rolloutNeeded.toSqlInt(),
      record.acceptanceCriteriaCount,
      record.openQuestionsCount,
      record.specSummary,
    )
    statement.executeUpdate()
  }
}

fun saveFeatureImplementFinished(connection: Connection, record: FeatureImplementFinishedRecord): TerminalSaveOutcome {
  val childStepsJson = listJson(record.childSteps)
  if (rowExists(connection, "feature_implement_sessions", record.sessionId)) {
    if (lifecycleAlreadyFinished(connection, "feature_implement_sessions", record.sessionId)) {
      incrementDuplicateTerminalFinishedEvents(connection, "feature_implement_sessions", record.sessionId)
      return TerminalSaveOutcome.DUPLICATE
    }
    updateFeatureImplementFinished(connection, record, childStepsJson)
  } else {
    insertFeatureImplementFinished(connection, record, childStepsJson)
  }
  return TerminalSaveOutcome.FIRST_TERMINAL
}

fun saveFeatureVerifyStarted(connection: Connection, record: FeatureVerifyStartedRecord) {
  connection.prepareStatement(
    """
    INSERT INTO feature_verify_sessions (
      session_id, acceptance_criteria_count, rollout_relevant, spec_summary, started_at
    ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.sessionId,
      record.acceptanceCriteriaCount,
      record.rolloutRelevant.toSqlInt(),
      record.specSummary,
    )
    statement.executeUpdate()
  }
}

fun saveFeatureVerifyFinished(connection: Connection, record: FeatureVerifyFinishedRecord): TerminalSaveOutcome {
  val gapsFoundJson = listJson(record.gapsFound)
  if (rowExists(connection, "feature_verify_sessions", record.sessionId)) {
    if (lifecycleAlreadyFinished(connection, "feature_verify_sessions", record.sessionId)) {
      incrementDuplicateTerminalFinishedEvents(connection, "feature_verify_sessions", record.sessionId)
      return TerminalSaveOutcome.DUPLICATE
    }
    updateFeatureVerifyFinished(connection, record, gapsFoundJson)
  } else {
    insertFeatureVerifyFinished(connection, record, gapsFoundJson)
  }
  return TerminalSaveOutcome.FIRST_TERMINAL
}

private fun updateFeatureImplementFinished(
  connection: Connection,
  record: FeatureImplementFinishedRecord,
  childStepsJson: String,
) {
  connection.prepareStatement(
    """
    UPDATE feature_implement_sessions SET
      completion_status = ?,
      plan_correction_count = ?,
      plan_task_count = ?,
      plan_phase_count = ?,
      feature_flag_used = ?,
      feature_flag_pattern = ?,
      files_created = ?,
      files_modified = ?,
      tasks_completed = ?,
      review_iterations = ?,
      audit_result = ?,
      audit_iterations = ?,
      validation_result = ?,
      boundary_history_written = ?,
      boundary_history_value = ?,
      pr_created = ?,
      plan_deviation_notes = ?,
      child_steps_json = ?,
      estimated_phase_tokens_json = ?,
      estimated_total_tokens = ?,
      finished_at = CURRENT_TIMESTAMP
    WHERE session_id = ?
      AND (finished_event_emitted_at IS NULL OR completion_status = 'stale')
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      featureImplementFinishedValues(record, childStepsJson, includeSessionFirst = false, includeSource = false),
    )
    statement.executeUpdate()
  }
}

private fun insertFeatureImplementFinished(
  connection: Connection,
  record: FeatureImplementFinishedRecord,
  childStepsJson: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO feature_implement_sessions (
      session_id, source, completion_status, plan_correction_count,
      plan_task_count, plan_phase_count, feature_flag_used,
      feature_flag_pattern, files_created, files_modified,
      tasks_completed, review_iterations, audit_result,
      audit_iterations, validation_result, boundary_history_written,
      boundary_history_value, pr_created, plan_deviation_notes,
      child_steps_json, estimated_phase_tokens_json, estimated_total_tokens, finished_at
    ) VALUES (
      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
      CURRENT_TIMESTAMP
    )
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      featureImplementFinishedValues(record, childStepsJson, includeSessionFirst = true),
    )
    statement.executeUpdate()
  }
}

private fun updateFeatureVerifyFinished(
  connection: Connection,
  record: FeatureVerifyFinishedRecord,
  gapsFoundJson: String,
) {
  connection.prepareStatement(
    """
    UPDATE feature_verify_sessions SET
      feature_flag_audit_performed = ?,
      review_iterations = ?,
      audit_result = ?,
      completion_status = ?,
      history_relevance = ?,
      history_helpfulness = ?,
      gaps_found = ?,
      finished_at = CURRENT_TIMESTAMP
    WHERE session_id = ?
      AND (finished_event_emitted_at IS NULL OR completion_status = 'stale')
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      featureVerifyFinishedValues(record, gapsFoundJson, includeSessionFirst = false),
    )
    statement.executeUpdate()
  }
}

private fun insertFeatureVerifyFinished(
  connection: Connection,
  record: FeatureVerifyFinishedRecord,
  gapsFoundJson: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO feature_verify_sessions (
      session_id, feature_flag_audit_performed, review_iterations,
      audit_result, completion_status, history_relevance,
      history_helpfulness, gaps_found, finished_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    """.trimIndent(),
  ).use { statement ->
    statement.bind(featureVerifyFinishedValues(record, gapsFoundJson, includeSessionFirst = true))
    statement.executeUpdate()
  }
}
