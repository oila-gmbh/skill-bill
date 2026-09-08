package skillbill.ports.subtaskreview

/**
 * The machine-readable keys the runtime's review verification gates read from a phase's output,
 * shared by the gate ([FeatureTaskRuntimeRunner]) and the prompt that instructs the agent to emit them
 * ([FeatureTaskRuntimePhasePromptComposer]) so the two cannot drift.
 */
object FeatureTaskRuntimeVerificationSignalKeys {
  /** Top-level verdict string both verifying gates accept as an explicit advance/remediation signal. */
  const val VERDICT = "verdict"

  /** produced_outputs key the review gate reads: the findings array (an empty [] affirms no Blocker). */
  const val REVIEW_FINDINGS = "findings"

  /**
   * produced_outputs key carrying the Review run ID the pass's `bill-code-review` invocation reported.
   */
  const val REVIEW_RUN_ID = "review_run_id"

  const val FINDINGS_VERIFICATION_DISPOSITIONS = "finding_dispositions"
}
