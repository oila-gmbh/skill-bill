package skillbill.application.featuretask

object FeatureTaskRuntimeAttemptBudgets {
  const val MAX_OUTPUT_GATE_RETRY_ATTEMPTS: Int = 1
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
      val attemptWord = if (failureCount == 1) "attempt" else "attempts"
      "Phase '$phaseId' exhausted the bounded output-gate correction budget after " +
        "$failureCount $attemptWord (cap=$MAX_OUTPUT_GATE_RETRY_ATTEMPTS); the run blocks rather than relaunching."
    } else {
      null
    }
  }

  fun malformedOutputBlockReason(phaseId: String, malformedAttemptCount: Int): String? =
    outputGateBlockReason(phaseId, malformedAttemptCount)

  /**
   * Whether a round that reported it tried and could not close a finding may try that finding again.
   *
   * One retry per finding, tracked by finding reference rather than by attempt count: a first failed
   * fix is often a first reading of the defect, and a second attempt costs one session. A finding the
   * round declares unresolved twice is a genuine dead end, and re-entering it a third time buys
   * nothing an operator would not have to decide anyway.
   */
  fun unresolvedFindingBlockReason(
    phaseId: String,
    unresolved: Set<String>,
    priorUnresolved: Set<String>,
    detail: String,
  ): String? {
    require(unresolved.isNotEmpty()) { "unresolved must name at least one finding, was empty." }
    val repeated = unresolved.intersect(priorUnresolved).ifEmpty { return null }
    return "Phase '$phaseId' reported the same review findings unresolved on two consecutive " +
      "attempts: ${repeated.sorted().joinToString(", ")}. It had its retry at each of them and the " +
      "finding still stands, so the run blocks for an operator rather than spending a third session " +
      "on it. Reported: $detail"
  }

  /**
   * Whether a round that left carried review findings out of its repair receipt may run again.
   *
   * The omitted set is the budget, not an attempt count: a re-entry has to account for at least one
   * more finding than the last one did, so the loop is bounded by the number of carried findings and
   * a producer that keeps dropping the same finding stops after one send-back. An attempt count
   * cannot express this — the flat output-gate cap blocked rounds that had real repair work left,
   * which is what routed a dropped finding to an operator instead of back to the phase.
   *
   * [omitted] and [priorOmitted] are finding references, so growth and substitution both read as
   * "no progress": a round that trades one omission for another has closed nothing.
   */
  fun findingCoverageBlockReason(phaseId: String, omitted: Set<String>, priorOmitted: Set<String>?): String? {
    require(omitted.isNotEmpty()) { "omitted must name at least one finding, was empty." }
    if (priorOmitted == null) return null
    val progressed = omitted.size < priorOmitted.size && omitted.all(priorOmitted::contains)
    if (progressed) return null
    return "Phase '$phaseId' was sent back for the review findings its repair receipt left out and " +
      "accounted for none of them: ${omitted.sorted().joinToString(", ")}. Re-entering it would " +
      "repeat a round that made no progress on coverage. A round that cannot close a finding " +
      "declares it with outcome 'attempted_unresolved' and a reason; leaving it out is not an " +
      "outcome, so the run blocks for an operator."
  }
}
