package skillbill.application.goalrunner
import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.workflow.decomposition.model.IssueKey

fun resolveUnaddressedFindingsLedger(
  service: UnaddressedFindingsLedgerService?,
  issueKey: IssueKey,
): UnaddressedFindingsLedger? {
  if (service == null) return null
  return try {
    service.ledger(issueKey)
  } catch (_: UnaddressedFindingsLedgerAbsentError) {
    UnaddressedFindingsLedger(issueKey, emptyList())
  } catch (_: InvalidUnaddressedFindingsLedgerSchemaError) {
    null
  }
}
