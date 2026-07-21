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
)

data class UnaddressedFindingsLedger(
  val issueKey: String,
  val findings: List<UnaddressedFinding>,
) {
  val severityBreakdown: Map<String, Int> = UNADDRESSED_FINDING_SEVERITIES.associateWith { severity ->
    findings.count { it.severity == severity }
  }
}
