package skillbill.application

internal fun validateFeatureImplementChildSteps(childSteps: List<Map<String, Any?>>): String? =
  childSteps.firstNotNullOfOrNull(::validateFeatureImplementChildStep)

private fun validateFeatureImplementChildStep(childStep: Map<String, Any?>): String? {
  val skill = childStep.stringValue("skill")
  if (skill.isBlank()) {
    return "child_steps skill must not be blank."
  }
  return when {
    skill.endsWith("code-review") || "-code-review-" in skill -> validateReviewChildStep(childStep)
    skill == "bill-code-check" -> validateQualityCheckChildStep(childStep)
    skill == "bill-pr-description" -> validatePrDescriptionChildStep(childStep)
    else -> null
  }
}

private fun validateReviewChildStep(childStep: Map<String, Any?>): String? = listOfNotNull(
  validatePresent(childStep, "total_findings"),
  validatePresent(childStep, "accepted_findings"),
  validatePresent(childStep, "rejected_findings"),
  validatePresent(childStep, "unresolved_findings"),
  validatePresent(childStep, "accepted_rate"),
  validatePresent(childStep, "rejected_rate"),
  validateFindingIssueCategories(childStep, "accepted_finding_details"),
  validateFindingIssueCategories(childStep, "rejected_finding_details"),
).firstOrNull()

private fun validateQualityCheckChildStep(childStep: Map<String, Any?>): String? = listOfNotNull(
  validatePresent(childStep, "result"),
  validatePresent(childStep, "iterations"),
  validatePresent(childStep, "initial_failure_count"),
  validatePresent(childStep, "final_failure_count"),
  validateQualityCheckFailureDetails(childStep),
).firstOrNull()

private fun validatePrDescriptionChildStep(childStep: Map<String, Any?>): String? = listOfNotNull(
  validatePresent(childStep, "pr_created"),
  validatePresent(childStep, "commit_count"),
  validatePresent(childStep, "files_changed_count"),
).firstOrNull()

private fun validatePresent(payload: Map<String, Any?>, fieldName: String): String? =
  if (payload.containsKey(fieldName)) null else "child_steps $fieldName is required."

private fun validateFindingIssueCategories(childStep: Map<String, Any?>, fieldName: String): String? {
  val details = childStep[fieldName] as? List<*> ?: return null
  val missingCategory = details.filterIsInstance<Map<*, *>>().any { detail ->
    detail["issue_category"]?.toString().isNullOrBlank()
  }
  return if (missingCategory) {
    "child_steps $fieldName entries require issue_category."
  } else {
    null
  }
}

private fun validateQualityCheckFailureDetails(childStep: Map<String, Any?>): String? {
  if (childStep.intValue("final_failure_count") == 0) {
    return null
  }
  val hasFailingCheckNames = childStep.containsKey("failing_check_names")
  val hasFailingCheckDetails = childStep.containsKey("failing_check_details")
  return if (hasFailingCheckNames || hasFailingCheckDetails) {
    null
  } else {
    "child_steps failing check details are required for bill-code-check."
  }
}

private fun Map<String, Any?>.stringValue(key: String): String = this[key]?.toString().orEmpty()

private fun Map<String, Any?>.intValue(key: String): Int = when (val value = this[key]) {
  is Number -> value.toInt()
  is String -> value.toIntOrNull() ?: 0
  else -> 0
}
