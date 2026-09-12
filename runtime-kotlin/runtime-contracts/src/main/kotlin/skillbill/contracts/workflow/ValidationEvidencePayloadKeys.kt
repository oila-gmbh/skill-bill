package skillbill.contracts.workflow

import skillbill.contracts.SharedPayloadKeys

object ValidationEvidencePayloadKeys {
  const val VALIDATION_EVIDENCE: String = "validation_evidence"
  const val VALIDATION_RESULT: String = "validation_result"
  const val CONTRACT_VERSION: String = SharedPayloadKeys.CONTRACT_VERSION
  const val RESULTS: String = "results"
  const val COMMAND: String = "command"
  const val EXIT_CODE: String = "exit_code"
  const val INTEGRITY_PROBLEM: String = "integrity_problem"
  const val COMPLETED_SUBTASK_VALIDATION: String = "completed_subtask_validation"
}
