package skillbill.application.featuretask

internal object FeatureTaskRuntimeAttemptBudgets {
  const val MAX_OUTPUT_GATE_RETRY_ATTEMPTS: Int = 2
  const val MAX_FORMAT_RETRY_ATTEMPTS: Int = MAX_OUTPUT_GATE_RETRY_ATTEMPTS
  const val MAX_PROCESS_FAILURE_ATTEMPTS: Int = 3

  fun processFailureBlockReason(phaseId: String, processFailureCount: Int, lastFailureReason: String?): String? {
    require(processFailureCount >= 0) {
      "processFailureCount must be >= 0, was $processFailureCount."
    }
    if (processFailureCount < MAX_PROCESS_FAILURE_ATTEMPTS) return null
    val last = lastFailureReason?.takeIf(String::isNotBlank)?.let { " Last failure: $it" }.orEmpty()
    return "Phase '$phaseId' failed to execute $processFailureCount times " +
      "(cap=$MAX_PROCESS_FAILURE_ATTEMPTS) without reaching its output gate; the run blocks rather than " +
      "relaunching a process that keeps dying. No repair attempt was consumed.$last"
  }

  fun outputGateBlockReason(phaseId: String, failureCount: Int): String? {
    require(failureCount >= 1) {
      "failureCount must be >= 1, was $failureCount."
    }
    return if (failureCount >= MAX_OUTPUT_GATE_RETRY_ATTEMPTS) {
      "Phase '$phaseId' exhausted the bounded output-gate correction budget after " +
        "$failureCount attempts (cap=$MAX_OUTPUT_GATE_RETRY_ATTEMPTS); the run blocks rather than relaunching."
    } else {
      null
    }
  }

  fun malformedOutputBlockReason(phaseId: String, malformedAttemptCount: Int): String? =
    outputGateBlockReason(phaseId, malformedAttemptCount)
}
