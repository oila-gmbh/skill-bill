package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.model.UNADDRESSED_FINDING_CATEGORIES
import skillbill.goalrunner.model.UNADDRESSED_FINDING_SEVERITIES
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.persistence.DatabaseSessionFactory

@Inject
class UnaddressedFindingsLedgerService(private val database: DatabaseSessionFactory) {
  fun ledger(issueKey: String, dbOverride: String? = null): UnaddressedFindingsLedger =
    database.read(dbOverride) { unitOfWork ->
      if (!unitOfWork.unaddressedFindings.issueExists(issueKey)) {
        throw UnaddressedFindingsLedgerAbsentError("No goal exists for issue key '$issueKey'.")
      }
      val findings = unitOfWork.unaddressedFindings.fetchLedger(issueKey)
      findings.forEach { finding ->
        if (
          finding.issueKey != issueKey || finding.workflowId.isBlank() || finding.subtaskId <= 0 ||
          finding.reviewPassNumber <= 0 || finding.findingOrdinal <= 0 || finding.location.isBlank() ||
          finding.summary.isBlank() || finding.severity !in UNADDRESSED_FINDING_SEVERITIES ||
          finding.issueCategory !in UNADDRESSED_FINDING_CATEGORIES
        ) {
          throw InvalidUnaddressedFindingsLedgerSchemaError(
            "Malformed unaddressed-findings ledger row for issue '$issueKey'.",
          )
        }
      }
      UnaddressedFindingsLedger(issueKey, findings)
    }
}
