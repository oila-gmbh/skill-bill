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

  /** produced_outputs key the review gate reads: the findings array (an empty [] affirms no Blocker/Major). */
  const val REVIEW_FINDINGS = "findings"

  /** produced_outputs key the audit gate reads: unmet acceptance criteria (an empty [] affirms all met). */
  const val AUDIT_UNMET_CRITERIA = "unmet_criteria"

  /** Back-compat alias the audit gate also accepts in place of [AUDIT_UNMET_CRITERIA]. */
  const val AUDIT_FAILING_CRITERIA_ALIAS = "failing_criteria"

  /**
   * produced_outputs key an audit can set when it finds the spec/accepted planning decisions conflict.
   * The runtime blocks the loop instead of treating that as another remediable audit gap.
   */
  const val AUDIT_SPEC_DECISION_CONFLICT = "spec_decision_conflict"
}
