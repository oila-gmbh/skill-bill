package skillbill.review

import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewIssueCategory

object ReviewParser {
  fun parseReview(text: String): ImportedReview {
    val reviewRunId =
      requireMatch(
        reviewRunIdPattern,
        text,
        "Review output is missing 'Review run ID: <review-run-id>'.",
      )
    val reviewSessionId =
      requireMatch(
        reviewSessionIdPattern,
        text,
        "Review output is missing 'Review session ID: <review-session-id>'.",
      )
    val routedSkill = normalizeRoutedSkill(extractSummaryValue(text, "routed_skill"))
    val specialistReviews = extractSpecialistReviews(text)
    return ImportedReview(
      reviewRunId = reviewRunId,
      reviewSessionId = reviewSessionId,
      rawText = text,
      routedSkill = routedSkill,
      detectedScope = extractSummaryValue(text, "detected_scope"),
      detectedStack = extractSummaryValue(text, "detected_stack"),
      executionMode = parseExecutionMode(text),
      specialistReviews = specialistReviews,
      findings = parseReviewFindings(text).map { finding ->
        finding.copy(
          issueCategory =
          resolveReviewIssueCategory(
            explicitCategory = finding.issueCategory.takeUnless { it == ReviewIssueCategory.OTHER.wireValue },
            routedSkill = routedSkill,
            specialistReviews = specialistReviews,
            finding = finding,
          ),
        )
      },
    )
  }

  private fun parseExecutionMode(text: String): String? {
    val reported = reportedExecutionModePattern.find(text)?.groups?.get("value")?.value?.trim()
      ?: return null
    return extractSummaryValue(text, "execution_mode")
      ?: throw IllegalArgumentException(
        "Review output reported an unknown execution mode '$reported'. Allowed: inline, delegated.",
      )
  }
}
