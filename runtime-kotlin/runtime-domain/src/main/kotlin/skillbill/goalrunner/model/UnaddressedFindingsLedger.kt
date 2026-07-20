package skillbill.goalrunner.model

val UNADDRESSED_FINDING_SEVERITIES: Set<String> = setOf("blocker", "major", "minor", "nit")
val UNADDRESSED_FINDING_CATEGORIES: Set<String> = setOf(
  "architecture", "performance", "platform-correctness", "security", "testing",
  "api-contracts", "persistence", "reliability", "ui", "ux-accessibility", "other",
)

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
