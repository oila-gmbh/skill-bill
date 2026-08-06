package skillbill.review.model

data class ImportedFinding(
  val findingId: String,
  val severity: String,
  val confidence: String,
  val location: String,
  val description: String,
  val findingText: String,
  val issueCategory: String = ReviewIssueCategory.OTHER.wireValue,
  val laneSkillName: String? = null,
)

/**
 * One review lane a run actually launched, with its identity taken from the composed launch plan.
 * [resolutionState] is `unresolved` only for a lane a run reported that the plan does not contain;
 * its [laneSkillName] then holds the reported text verbatim.
 */
data class ReviewRunLane(
  val laneSkillName: String,
  val packSlug: String,
  val area: String,
  val depth: Int,
  val required: Boolean,
  val orderIndex: Int,
  val originLayerChain: List<String>,
  val resolutionState: String,
  /**
   * Durable single-pass disposition. Deliberately has no default: a row may only claim `complete`
   * where a durable complete result was observed, so every writer states it explicitly and an
   * unknown disposition can never fail open into skipped resume coverage.
   */
  val reviewDisposition: String,
  val bundleCompositionDigest: String? = null,
  val segmentAccountingJson: String? = null,
  val unreviewedSegmentIds: List<String> = emptyList(),
  val budgetDimension: String? = null,
)

data class ReviewLaneEffectivenessRow(
  val routedSkillCanonical: String,
  val packSlug: String,
  val area: String,
  val totalFindings: Int,
  val acceptedFindings: Int,
  val rejectedFindings: Int,
)

data class ImportedReview(
  val reviewRunId: String,
  val reviewSessionId: String,
  val rawText: String,
  val routedSkill: String?,
  val detectedScope: String?,
  val detectedStack: String?,
  val executionMode: String?,
  val specialistReviews: List<String>,
  val findings: List<ImportedFinding>,
  val routedSkillCanonical: String = "unresolved",
  val detectedStackCanonical: String = "unresolved",
  val detectedScopeCanonical: String = "unresolved",
  val detectedScopeDetail: String? = null,
  /** Plan-sourced lane attribution for this run; empty only when no launch plan could be composed. */
  val planLanes: List<ReviewRunLane> = emptyList(),
)

data class ReviewSummary(
  val reviewRunId: String,
  val reviewSessionId: String?,
  val routedSkill: String?,
  val detectedScope: String?,
  val detectedStack: String?,
  val executionMode: String?,
  val specialistReviewsRaw: String?,
  val reviewFinishedAt: String?,
  val reviewFinishedEventEmittedAt: String?,
  val orchestratedRun: Boolean,
  val routedSkillCanonical: String = "unresolved",
  val detectedStackCanonical: String = "unresolved",
  val detectedScopeCanonical: String = "unresolved",
  val detectedScopeDetail: String? = null,
)

data class NormalizedStackLabel(
  val stack: String,
  val detail: String? = null,
  val fallback: Boolean = false,
  val fallbackReason: String? = null,
)

data class FindingMetadata(
  val findingId: String,
  val severity: String,
  val confidence: String,
)

data class NumberedFinding(
  val number: Int,
  val findingId: String,
  val severity: String,
  val confidence: String,
  val location: String,
  val description: String,
)

data class TriageDecision(
  val number: Int,
  val findingId: String,
  val outcomeType: String,
  val note: String,
)

data class FeedbackRequest(
  val reviewRunId: String,
  val findingIds: List<String>,
  val eventType: String,
  val note: String,
)

data class FeedbackTelemetryOptions(
  val enabled: Boolean? = null,
  val level: String? = null,
  val routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
)

data class FindingOutcomeRow(
  val reviewRunId: String,
  val findingId: String,
  val severity: String,
  val confidence: String,
  val issueCategory: String,
  val location: String,
  val description: String,
  val outcomeType: String,
  val note: String,
)
