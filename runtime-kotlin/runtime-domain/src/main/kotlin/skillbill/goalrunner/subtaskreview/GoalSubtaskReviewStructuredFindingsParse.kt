package skillbill.goalrunner.subtaskreview

import skillbill.contracts.JsonCodec
import skillbill.goalrunner.subtaskreview.model.StructuredGoalReviewFinding
import skillbill.review.ReviewFindingActionability
import skillbill.review.ReviewFindingFieldCodec
import skillbill.review.context.model.requireRepositoryRelativePath
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict

object GoalSubtaskReviewStructuredFindingsParse {
  fun structuredFindings(
    output: Map<String, Any?>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<StructuredGoalReviewFinding> {
    val findings = output["produced_outputs"]
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get("findings") as? List<*>
      ?: return emptyList()
    return findings.mapNotNull { entry ->
      val finding = JsonCodec.anyToStringAnyMap(entry) ?: return@mapNotNull null
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
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID) as? String
    )?.trim()?.takeIf(String::isNotBlank)

  fun recordedVerdicts(
    fetchFindingVerdicts: (String) -> List<ReviewFindingVerdict>,
    output: Map<String, Any?>,
  ): List<ReviewFindingVerdict> {
    val reviewRunId = reviewRunIdOf(output) ?: return emptyList()
    return fetchFindingVerdicts(reviewRunId)
  }

  fun verificationBoundaryFindingPaths(finding: StructuredGoalReviewFinding): List<String> {
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
