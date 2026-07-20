package skillbill.goalrunner.model

import skillbill.review.model.ReviewIssueCategory

val UNADDRESSED_FINDING_SEVERITIES: Set<String> = setOf("blocker", "major", "minor", "nit")
val UNADDRESSED_FINDING_CATEGORIES: Set<String> = ReviewIssueCategory.entries.mapTo(linkedSetOf()) { it.wireValue }

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
