package skillbill.goalrunner.model

import skillbill.review.model.ReviewIssueCategory

val UNADDRESSED_FINDING_SEVERITIES: Set<String> = setOf("blocker", "major", "minor", "nit")
val UNADDRESSED_FINDING_CATEGORIES: Set<String> = ReviewIssueCategory.entries.mapTo(linkedSetOf()) { it.wireValue }
val UNADDRESSED_FINDING_DEFAULT_CATEGORY: String = ReviewIssueCategory.OTHER.wireValue

/**
 * An unrecognized severity is already treated as the lowest, non-blocking rank when the runtime
 * decides advancement, so the ledger records it in the taxonomy's lowest bucket rather than
 * persisting a row the retrieval surface would reject.
 */
const val UNADDRESSED_FINDING_DEFAULT_SEVERITY: String = "nit"

fun normalizedUnaddressedFindingCategory(issueCategory: String): String =
  issueCategory.takeIf { it in UNADDRESSED_FINDING_CATEGORIES } ?: UNADDRESSED_FINDING_DEFAULT_CATEGORY

fun normalizedUnaddressedFindingSeverity(severity: String): String =
  severity.takeIf { it in UNADDRESSED_FINDING_SEVERITIES } ?: UNADDRESSED_FINDING_DEFAULT_SEVERITY

/**
 * The terminal disposition the workflow fix loop reached for one finding. Recorded from loop state
 * for every finding a run produces, never inferred from agent prose, so accepted/rejected coverage
 * does not depend on optional manual triage.
 */
enum class ReviewFindingOutcome(val wireValue: String) {
  /** The fix loop addressed the finding before the subtask advanced. */
  ADDRESSED("addressed"),

  /** The finding survived into the terminal state unresolved and is carried forward. */
  CARRIED("carried"),

  /** The loop explicitly rejected the finding (false positive or declined fix). */
  REJECTED("rejected"),
  ;

  companion object {
    fun fromWireValue(wireValue: String): ReviewFindingOutcome = entries.firstOrNull { it.wireValue == wireValue }
      ?: error("Unsupported review finding outcome '$wireValue'.")
  }
}

data class UnaddressedFinding(
  val issueKey: String,
  val subtaskId: Int,
  val workflowId: String,
  val reviewPassNumber: Int,
  val findingOrdinal: Int,
  val severity: String,
  val issueCategory: String,
  val location: String,
  val summary: String,
  /**
   * The shared key joining this workflow-loop finding to an imported review run. Both stay null when
   * no review run was imported for the pass; the pair is then read as unresolved rather than being
   * bucketed to a guessed run.
   */
  val reviewRunId: String? = null,
  val findingId: String? = null,
)

/** One finding's terminal outcome, keyed identically to the ledger row it came from. */
data class ReviewFindingOutcomeRecord(
  val workflowId: String,
  val reviewPassNumber: Int,
  val findingOrdinal: Int,
  val outcome: ReviewFindingOutcome,
  val reviewRunId: String? = null,
  val findingId: String? = null,
) {
  val keyState: String = if (reviewRunId != null && findingId != null) "resolved" else "unresolved"
}

fun UnaddressedFinding.toOutcomeRecord(outcome: ReviewFindingOutcome): ReviewFindingOutcomeRecord =
  ReviewFindingOutcomeRecord(
    workflowId = workflowId,
    reviewPassNumber = reviewPassNumber,
    findingOrdinal = findingOrdinal,
    outcome = outcome,
    reviewRunId = reviewRunId,
    findingId = findingId,
  )

data class UnaddressedFindingsLedger(
  val issueKey: String,
  val findings: List<UnaddressedFinding>,
) {
  val severityBreakdown: Map<String, Int> = UNADDRESSED_FINDING_SEVERITIES.associateWith { severity ->
    findings.count { it.severity == severity }
  }
}
