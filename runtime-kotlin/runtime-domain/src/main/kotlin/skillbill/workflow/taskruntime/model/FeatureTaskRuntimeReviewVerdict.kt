package skillbill.workflow.taskruntime.model

/**
 * Pure domain models for the structured review verdict that drives the `review_fix` backward edge. A
 * review emits a [FeatureTaskRuntimeReviewVerdict]: its findings classify the run as
 * [FeatureTaskRuntimeVerdict.CHANGES_REQUESTED] (any unresolved Blocker or Major) or
 * [FeatureTaskRuntimeVerdict.APPROVED]. The classification is a pure function of the findings — prose
 * alone cannot advance past a remediable finding — and the findings are carried into the
 * `implement_fix` briefing. No raw maps live in the model: the runner decodes wire output into these
 * types.
 *
 * Two severity gates share the Blocker-or-Major rule.
 * [FeatureTaskRuntimeReviewSeverity.requiresRemediation] drives the backward edge and the set of
 * findings a fix pass must clear: a surviving Blocker or Major reopens `implement_fix`.
 * [FeatureTaskRuntimeReviewSeverity.blocksAdvance] drives the terminal loud block on cap exhaustion:
 * the run still stops on a surviving Blocker or Major, so a real breakage is never shipped. Minor
 * and Nit findings are recorded in the ledger and never reopen the loop or hard-block advance.
 */

/**
 * Severity of a single review finding. Blocker and Major both require remediation in the fix pass
 * and both block advancing once the remediation budget is exhausted.
 */
enum class FeatureTaskRuntimeReviewSeverity(val wireValue: String) {
  BLOCKER("blocker"),
  MAJOR("major"),
  MINOR("minor"),
  NIT("nit"),
  ;

  /**
   * Whether a finding of this severity blocks advancing past review once the bounded remediation
   * budget is exhausted. A surviving Blocker or Major is a hard stop; Minor and Nit move on.
   */
  val blocksAdvance: Boolean
    get() = this == BLOCKER || this == MAJOR

  /**
   * Whether a finding of this severity must be remediated in the same review pass. A Blocker or
   * Major reopens `implement_fix`; Minor and Nit are recorded in the ledger and never reopen the loop.
   */
  val requiresRemediation: Boolean
    get() = this == BLOCKER || this == MAJOR

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeReviewSeverity =
      entries.firstOrNull { it.wireValue == value.trim().lowercase() }
        ?: throw IllegalArgumentException(
          "Unknown feature-task-runtime review severity '$value'. " +
            "Allowed: ${entries.joinToString { it.wireValue }}.",
        )
  }
}

/** One review finding: its [severity] and a human-readable [message] carried into the fix handoff. */
data class FeatureTaskRuntimeReviewFinding(
  val severity: FeatureTaskRuntimeReviewSeverity,
  val message: String,
) {
  init {
    require(message.isNotBlank()) { "FeatureTaskRuntimeReviewFinding.message must be non-blank." }
  }
}

/**
 * The structured verdict a `review` phase emits: the full ordered finding list. [verdict] is derived
 * purely from the findings ([FeatureTaskRuntimeVerdict.CHANGES_REQUESTED] when any finding
 * [FeatureTaskRuntimeReviewFinding.severity] requires remediation, else
 * [FeatureTaskRuntimeVerdict.APPROVED]). [remediationFindings] and [unresolvedFindings] are the
 * Blocker and Major findings a fix pass must clear and that hard-block the run once the remediation
 * budget is exhausted.
 */
data class FeatureTaskRuntimeReviewVerdict(
  val findings: List<FeatureTaskRuntimeReviewFinding>,
) {
  val verdict: FeatureTaskRuntimeVerdict
    get() = if (findings.any { it.severity.requiresRemediation }) {
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
    } else {
      FeatureTaskRuntimeVerdict.APPROVED
    }

  /** Blocker and Major findings the `implement_fix` pass must reconcile. */
  val remediationFindings: List<FeatureTaskRuntimeReviewFinding>
    get() = findings.filter { it.severity.requiresRemediation }

  /** Blocker and Major findings that hard-block the run once the remediation budget is exhausted. */
  val unresolvedFindings: List<FeatureTaskRuntimeReviewFinding>
    get() = findings.filter { it.severity.blocksAdvance }
}
