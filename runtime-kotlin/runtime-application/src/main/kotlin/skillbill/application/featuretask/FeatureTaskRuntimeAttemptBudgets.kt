package skillbill.application.featuretask

/**
 * Hard stop budgets for attempts that never produce repairable output. Schema-invalid and
 * incomplete-work retries are uncapped; a dead process or unparseable payload is not.
 */
internal object FeatureTaskRuntimeAttemptBudgets {
  /** Malformed serialization gets its own bounded correction budget. */
  const val MAX_FORMAT_RETRY_ATTEMPTS: Int = 3

  /**
   * Attempts that died before the schema gate — an unspawnable process, a timeout, a non-zero exit —
   * get their own bounded budget. A process that never produced output produced no invalid output
   * either, so charging it to a repair loop both exhausts that loop on unrepairable failures and
   * mislabels the block.
   */
  const val MAX_PROCESS_FAILURE_ATTEMPTS: Int = 3

  /**
   * Blocks a phase whose process kept dying before it could produce anything. States the process
   * failure and carries the last one verbatim, so the block reads as what it is rather than as a
   * repair loop that ran out of attempts on invalid output.
   */
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

  fun malformedOutputBlockReason(phaseId: String, malformedAttemptCount: Int): String? {
    require(malformedAttemptCount >= 1) {
      "malformedAttemptCount must be >= 1, was $malformedAttemptCount."
    }
    return if (malformedAttemptCount >= MAX_FORMAT_RETRY_ATTEMPTS) {
      "Phase '$phaseId' exhausted the bounded output-format correction budget after " +
        "$malformedAttemptCount malformed attempts (cap=$MAX_FORMAT_RETRY_ATTEMPTS); semantic repair " +
        "attempts were not consumed."
    } else {
      null
    }
  }
}
