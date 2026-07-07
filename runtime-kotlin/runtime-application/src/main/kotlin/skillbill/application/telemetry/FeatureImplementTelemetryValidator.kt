package skillbill.application.telemetry

import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest

private val auditResults = listOf("all_pass", "had_gaps", "skipped", "not_reached")
private val validationResults = listOf("pass", "fail", "skipped", "not_reached")
private val featureImplementSources = listOf("production", "test", "synthetic", "unknown")
private val featureImplementSessionIdPattern = Regex("""^fis-[A-Za-z0-9][A-Za-z0-9_-]*$""")
private val completionStatuses =
  listOf("completed", "abandoned_at_planning", "abandoned_at_implementation", "abandoned_at_review", "error")

fun validateFeatureImplementStarted(request: FeatureImplementStartedRequest): String? = listOfNotNull(
  validateEnum(request.featureSize, featureSizes, "feature_size"),
  validateEnum(request.source, featureImplementSources, "source"),
  validateEnum(request.issueKeyType, issueKeyTypes, "issue_key_type"),
  validateNonBlank(request.featureName, "feature_name"),
  validateNonBlank(request.issueKey, "issue_key"),
  validateIssueKeyType(request),
  validatePositive(request.acceptanceCriteriaCount, "acceptance_criteria_count"),
  validateSpecInputTypes(request),
  validateSpecWordCount(request),
  validateNonBlank(request.specSummary, "spec_summary"),
  request.specInputTypes.firstNotNullOfOrNull { validateEnum(it, specInputTypes, "spec_input_types") },
).firstOrNull()

fun validateFeatureImplementFinished(request: FeatureImplementFinishedRequest): String? = listOfNotNull(
  validateFeatureImplementSessionId(request.sessionId),
  validateEnum(request.source, featureImplementSources, "source"),
  validateEnum(request.completionStatus, completionStatuses, "completion_status"),
  validateEnum(request.featureFlagPattern, featureFlagPatterns, "feature_flag_pattern"),
  validateEnum(request.auditResult, auditResults, "audit_result"),
  validateEnum(request.validationResult, validationResults, "validation_result"),
  validateEnum(request.boundaryHistoryValue, historySignalValues, "boundary_history_value"),
  validateFeatureImplementChildSteps(request.childSteps),
  validateCompletedFeatureImplementFinished(request),
).firstOrNull()

private fun validateFeatureImplementSessionId(sessionId: String): String? = when {
  sessionId.isBlank() -> "session_id must not be blank."
  !sessionId.matches(featureImplementSessionIdPattern) -> "session_id must be a feature-task session id."
  else -> null
}

private fun validateCompletedFeatureImplementFinished(request: FeatureImplementFinishedRequest): String? {
  if (request.completionStatus != "completed") {
    return null
  }
  return listOfNotNull(
    validatePositive(request.planTaskCount, "plan_task_count"),
    validatePositive(request.planPhaseCount, "plan_phase_count"),
    validatePositive(request.tasksCompleted, "tasks_completed"),
    validatePositive(request.reviewIterations, "review_iterations"),
    validatePositive(request.auditIterations, "audit_iterations"),
    validateCompletedAuditResult(request.auditResult),
    validateCompletedValidationResult(request.validationResult),
  ).firstOrNull()
}

private fun validateCompletedAuditResult(auditResult: String): String? = when (auditResult) {
  "skipped", "not_reached" -> "audit_result must not be $auditResult when completion_status is completed."
  else -> null
}

private fun validateCompletedValidationResult(validationResult: String): String? = when (validationResult) {
  "skipped", "not_reached" -> "validation_result must not be $validationResult when completion_status is completed."
  else -> null
}
