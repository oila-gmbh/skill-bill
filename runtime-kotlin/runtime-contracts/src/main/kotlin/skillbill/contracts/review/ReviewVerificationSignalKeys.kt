package skillbill.contracts.review

import skillbill.contracts.SharedPayloadKeys

object ReviewVerificationSignalKeys {
  const val VERDICT = SharedPayloadKeys.VERDICT
  const val REVIEW_FINDINGS = "findings"
  const val REVIEW_RUN_ID = "review_run_id"
  const val EVIDENCE_COVERAGE_COMPLETE = "evidence_coverage_complete"
  const val FINDINGS_VERIFICATION_DISPOSITIONS = "finding_dispositions"
  const val CITATION_DIAGNOSTICS = "citation_diagnostics"
}
