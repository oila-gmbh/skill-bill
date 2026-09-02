package skillbill.application.goalrunner

import skillbill.application.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.error.InvalidUnaddressedFindingsLedgerSchemaError
import skillbill.error.UnaddressedFindingsLedgerAbsentError
import skillbill.goalrunner.model.UnaddressedFindingsLedger

fun resolveUnaddressedFindingsLedger(
  service: UnaddressedFindingsLedgerService?,
  issueKey: String,
  dbPathOverride: String?,
): UnaddressedFindingsLedger? {
  if (service == null) return null
  return try {
    service.ledger(issueKey, dbPathOverride)
  } catch (_: UnaddressedFindingsLedgerAbsentError) {
    UnaddressedFindingsLedger(issueKey, emptyList())
  } catch (_: InvalidUnaddressedFindingsLedgerSchemaError) {
    null
  }
}
