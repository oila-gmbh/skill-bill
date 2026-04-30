package skillbill.application

import skillbill.application.model.FeatureVerifyFinishedRequest

private val auditResults = listOf("all_pass", "had_gaps", "skipped")
private val featureVerifyCompletionStatuses = listOf("completed", "abandoned_at_review", "abandoned_at_audit", "error")

fun validateFeatureVerifyFinished(request: FeatureVerifyFinishedRequest): String? = listOfNotNull(
  validateEnum(request.auditResult, auditResults, "audit_result"),
  validateEnum(request.completionStatus, featureVerifyCompletionStatuses, "completion_status"),
  validateEnum(request.historyRelevance, historySignalValues, "history_relevance"),
  validateEnum(request.historyHelpfulness, historySignalValues, "history_helpfulness"),
).firstOrNull()
