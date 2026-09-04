package skillbill.application.subtaskreview

import skillbill.contracts.JsonSupport
import skillbill.ports.review.ReviewRepository
import skillbill.review.ReviewFindingActionability
import skillbill.review.ReviewFindingFieldCodec
import skillbill.review.context.model.requireRepositoryRelativePath
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment

internal data class StructuredGoalReviewFinding(
  val severity: String,
  val message: String,
  val issueCategory: String,
  val location: String,
  val compactLabel: String,
  val findingId: String? = null,
  val repositoryPath: String? = null,
  val claimVerdict: ReviewClaimVerdict? = null,
  val scopeDisposition: ReviewScopeDisposition? = null,
  val citations: List<ReviewFindingCitation> = emptyList(),
  val severityAdjustment: ReviewSeverityAdjustment? = null,
)

object GoalSubtaskReviewStructuredFindingsParse {
  internal fun structuredFindings(
    output: Map<String, Any?>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<StructuredGoalReviewFinding> {
    val findings = output["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("findings") as? List<*>
      ?: return emptyList()
    return findings.mapNotNull { entry ->
      val finding = JsonSupport.anyToStringAnyMap(entry) ?: return@mapNotNull null
      val severity = (finding["severity"] as? String)?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        ?: return@mapNotNull null
      val message = (finding["message"] as? String)?.trim()?.takeIf(String::isNotBlank)
        ?: return@mapNotNull null
      val overlay = ReviewFindingActionability.overlayOf(
        findingRef = ReviewFindingFieldCodec.findingRefOf(
          finding["id"],
          finding["finding_id"],
          finding["f_number"],
        ),
        recordedVerdicts = recordedVerdicts,
        encoded = ReviewFindingFieldCodec.recordedFieldsOf(
          claimVerdict = finding["claim_verdict"],
          scopeDisposition = finding["scope_disposition"],
          citations = finding["citations"],
          severityAdjustment = finding["severity_adjustment"],
        ),
      )
      StructuredGoalReviewFinding(
        severity = severity,
        message = message,
        issueCategory = sequenceOf(finding["issue_category"], finding["category"])
          .filterIsInstance<String>().firstOrNull()?.trim()?.lowercase() ?: "other",
        location = sequenceOf(finding["location"], finding["artifact_ref"])
          .filterIsInstance<String>().firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: "<unknown>",
        compactLabel = GoalSubtaskReviewSummarySanitize.labelFor(finding, message),
        findingId = ReviewFindingFieldCodec.findingRefOf(
          finding["id"],
          finding["finding_id"],
          finding["f_number"],
        ),
        repositoryPath = admissibleRepositoryPath(finding["repository_path"] as? String),
        claimVerdict = overlay.claimVerdict,
        scopeDisposition = overlay.scopeDisposition,
        citations = overlay.citations,
        severityAdjustment = overlay.severityAdjustment,
      )
    }
  }

  fun reviewRunIdOf(output: Map<String, Any?>): String? = (
    output["produced_outputs"]
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID) as? String
    )?.trim()?.takeIf(String::isNotBlank)

  fun recordedVerdicts(reviews: ReviewRepository, output: Map<String, Any?>): List<ReviewFindingVerdict> {
    val reviewRunId = reviewRunIdOf(output) ?: return emptyList()
    return reviews.fetchFindingVerdicts(reviewRunId)
  }

  internal fun verificationBoundaryFindingPaths(finding: StructuredGoalReviewFinding): List<String> {
    val paths = mutableListOf<String>()
    finding.repositoryPath?.let { paths += it }
    finding.citations.map(ReviewFindingCitation::path).filter { it.isNotBlank() }.forEach { paths += it }
    pathFromLocationLine(finding.location)?.let { paths += it }
    return paths.distinct()
  }

  private fun admissibleRepositoryPath(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
      requireRepositoryRelativePath(trimmed)
      trimmed
    }.getOrNull()
  }

  private fun pathFromLocationLine(location: String): String? {
    val token = location.trim()
    if (token.isBlank() || token == "<unknown>") return null
    val colon = token.lastIndexOf(':')
    val candidate = if (colon > 0) {
      val line = token.substring(colon + 1).trim().toIntOrNull()
      if (line != null && line >= 1) {
        token.substring(0, colon).trim().takeIf(String::isNotBlank)
      } else {
        token
      }
    } else {
      token
    }
    return admissibleRepositoryPath(candidate)
  }
}
