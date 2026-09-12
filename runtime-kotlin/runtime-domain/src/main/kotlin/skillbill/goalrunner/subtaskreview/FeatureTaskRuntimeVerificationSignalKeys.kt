package skillbill.goalrunner.subtaskreview

import skillbill.contracts.review.ReviewVerificationSignalKeys

object FeatureTaskRuntimeVerificationSignalKeys {
  const val VERDICT = ReviewVerificationSignalKeys.VERDICT
  const val REVIEW_FINDINGS = ReviewVerificationSignalKeys.REVIEW_FINDINGS
  const val REVIEW_RUN_ID = ReviewVerificationSignalKeys.REVIEW_RUN_ID
  const val REPOSITORY_CHECKPOINT = ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT
  const val REPOSITORY_CHECKPOINT_FINGERPRINT = ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT
  const val EVIDENCE_COVERAGE_COMPLETE = ReviewVerificationSignalKeys.EVIDENCE_COVERAGE_COMPLETE
  const val FINDINGS_VERIFICATION_DISPOSITIONS = ReviewVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS
  const val CITATION_DIAGNOSTICS = ReviewVerificationSignalKeys.CITATION_DIAGNOSTICS
}
