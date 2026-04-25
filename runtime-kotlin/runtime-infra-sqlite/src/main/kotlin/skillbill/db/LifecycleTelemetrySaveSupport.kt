package skillbill.db

import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import java.sql.Connection

fun saveFeatureImplementStarted(connection: Connection, record: FeatureImplementStartedRecord) {
  connection.prepareStatement(
    """
    INSERT INTO feature_implement_sessions (
      session_id, issue_key_provided, issue_key_type, spec_input_types,
      spec_word_count, feature_size, feature_name, rollout_needed,
      acceptance_criteria_count, open_questions_count, spec_summary
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.sessionId,
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

fun saveFeatureImplementFinished(connection: Connection, record: FeatureImplementFinishedRecord) {
  val childStepsJson = listJson(record.childSteps)
  if (rowExists(connection, "feature_implement_sessions", record.sessionId)) {
    updateFeatureImplementFinished(connection, record, childStepsJson)
  } else {
    insertFeatureImplementFinished(connection, record, childStepsJson)
  }
}

fun saveQualityCheckStarted(connection: Connection, record: QualityCheckStartedRecord) {
  connection.prepareStatement(
    """
    INSERT INTO quality_check_sessions (
      session_id, routed_skill, detected_stack, scope_type, initial_failure_count
    ) VALUES (?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      record.sessionId,
      record.routedSkill,
      record.detectedStack,
      record.scopeType,
      record.initialFailureCount,
    )
    statement.executeUpdate()
  }
}

fun saveQualityCheckFinished(connection: Connection, record: QualityCheckFinishedRecord) {
  val failingCheckNamesJson = listJson(record.failingCheckNames)
  if (rowExists(connection, "quality_check_sessions", record.sessionId)) {
    connection.prepareStatement(
      """
      UPDATE quality_check_sessions SET
        final_failure_count = ?,
        iterations = ?,
        result = ?,
        failing_check_names = ?,
        unsupported_reason = ?,
        finished_at = CURRENT_TIMESTAMP
      WHERE session_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bind(
        record.finalFailureCount,
        record.iterations,
        record.result,
        failingCheckNamesJson,
        record.unsupportedReason,
        record.sessionId,
      )
      statement.executeUpdate()
    }
  } else {
    connection.prepareStatement(
      """
      INSERT INTO quality_check_sessions (
        session_id, final_failure_count, iterations, result,
        failing_check_names, unsupported_reason, finished_at
      ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
      """.trimIndent(),
    ).use { statement ->
      statement.bind(
        record.sessionId,
        record.finalFailureCount,
        record.iterations,
        record.result,
        failingCheckNamesJson,
        record.unsupportedReason,
      )
      statement.executeUpdate()
    }
  }
}

fun saveFeatureVerifyStarted(connection: Connection, record: FeatureVerifyStartedRecord) {
  connection.prepareStatement(
    """
    INSERT INTO feature_verify_sessions (
      session_id, acceptance_criteria_count, rollout_relevant, spec_summary
    ) VALUES (?, ?, ?, ?)
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

fun saveFeatureVerifyFinished(connection: Connection, record: FeatureVerifyFinishedRecord) {
  val gapsFoundJson = listJson(record.gapsFound)
  if (rowExists(connection, "feature_verify_sessions", record.sessionId)) {
    updateFeatureVerifyFinished(connection, record, gapsFoundJson)
  } else {
    insertFeatureVerifyFinished(connection, record, gapsFoundJson)
  }
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
      finished_at = CURRENT_TIMESTAMP
    WHERE session_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bind(
      featureImplementFinishedValues(record, childStepsJson, includeSessionFirst = false),
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
      session_id, completion_status, plan_correction_count,
      plan_task_count, plan_phase_count, feature_flag_used,
      feature_flag_pattern, files_created, files_modified,
      tasks_completed, review_iterations, audit_result,
      audit_iterations, validation_result, boundary_history_written,
      boundary_history_value, pr_created, plan_deviation_notes,
      child_steps_json, finished_at
    ) VALUES (
      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
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
