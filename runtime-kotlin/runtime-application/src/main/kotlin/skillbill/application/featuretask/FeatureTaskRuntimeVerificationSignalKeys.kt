package skillbill.application.featuretask

/**
 * The machine-readable keys the runtime's review/audit verification gates read from a phase's output,
 * shared by the gate ([FeatureTaskRuntimeRunner]) and the prompt that instructs the agent to emit them
 * ([FeatureTaskRuntimePhasePromptComposer]) so the two cannot drift. A verifying-phase prompt that names
 * a key the gate no longer reads — or omits one it does — is the exact prompt/gate disagreement this
 * feature exists to prevent; binding both sides to these constants makes such drift a compile-time edit
 * in one place, and a contract test asserts the composed prompt names each gate key.
 */
internal object FeatureTaskRuntimeVerificationSignalKeys {
  /** Top-level verdict string both verifying gates accept as an explicit advance/remediation signal. */
  const val VERDICT = "verdict"

  /** produced_outputs key the review gate reads: the findings array (an empty [] affirms no Blocker). */
  const val REVIEW_FINDINGS = "findings"

  /** produced_outputs key the audit gate reads: unmet acceptance criteria (an empty [] affirms all met). */
  const val AUDIT_UNMET_CRITERIA = "unmet_criteria"

  /** Compact agent-facing audit gaps; normalized into the durable unmet-criteria and repair-plan model. */
  const val AUDIT_GAPS = "gaps"

  /** produced_outputs key for Minor/Nit audit findings that never reopen implementation. */
  const val AUDIT_NON_BLOCKING_FINDINGS = "non_blocking_findings"

  /**
   * Spelling agents reach for in place of [AUDIT_UNMET_CRITERIA]. It is rejected, not accepted: while it
   * was an accepted alias, an audit emitting only this key was classified `gaps_found` by the verdict
   * readers but skipped the repair-plan gate, which keys on the canonical name, and the run blocked much
   * later on a misleading durability message. One canonical representation is the only way the gate and
   * the verdict cannot disagree.
   */
  const val AUDIT_FAILING_CRITERIA_REJECTED_ALIAS = "failing_criteria"
}
