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
    val routedSkill = extractSummaryValue(text, "routed_skill")
    val specialistReviews = extractSpecialistReviews(text)
    return ImportedReview(
      reviewRunId = reviewRunId,
      reviewSessionId = reviewSessionId,
      rawText = text,
      routedSkill = routedSkill,
      detectedScope = extractSummaryValue(text, "detected_scope"),
      detectedStack = extractSummaryValue(text, "detected_stack"),
      executionMode = extractSummaryValue(text, "execution_mode"),
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
}
