package skillbill.workflow.taskruntime.model

/**
 * Pure domain models for the structured review verdict that drives the `review_fix` backward edge. A
 * review emits a [FeatureTaskRuntimeReviewVerdict]: its findings classify the run as
 * [FeatureTaskRuntimeVerdict.CHANGES_REQUESTED] (any Blocker or Major that still needs remediation) or
 * [FeatureTaskRuntimeVerdict.APPROVED]. The classification is a pure function of the findings — prose
 * alone cannot advance past a remediable finding — and the findings are carried into the
 * `implement_fix` briefing. No raw maps live in the model: the runner decodes wire output into these
 * types.
 *
 * Two severity gates are deliberately distinct. [FeatureTaskRuntimeReviewSeverity.requiresRemediation]
 * (Blocker + Major) drives the backward edge and the set of findings a fix pass must clear: a review
 * with only Major findings still reopens `implement_fix` so those are fixed in the same pass rather
 * than deferred to the unaddressed-findings ledger. [FeatureTaskRuntimeReviewSeverity.blocksAdvance]
 * (Blocker only) drives the terminal loud block on cap exhaustion: after the bounded remediation the
 * run moves on with a surviving Major but still stops on a surviving Blocker, so a real breakage is
 * never shipped.
 */

/**
 * Severity of a single review finding. Blocker and Major both require remediation in the fix pass;
 * only Blocker blocks advancing once the remediation budget is exhausted.
 */
enum class FeatureTaskRuntimeReviewSeverity(val wireValue: String) {
  BLOCKER("blocker"),
  MAJOR("major"),
  MINOR("minor"),
  NIT("nit"),
  ;

  /**
   * Whether a finding of this severity blocks advancing past review once the bounded remediation
   * budget is exhausted. Only a surviving Blocker is a hard stop; a surviving Major moves on.
   */
  val blocksAdvance: Boolean
    get() = this == BLOCKER

  /**
   * Whether a finding of this severity must be remediated in the same review pass. Blocker and Major
   * both reopen `implement_fix`; Minor and Nit are recorded in the ledger and never reopen the loop.
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
 * [FeatureTaskRuntimeVerdict.APPROVED]). [remediationFindings] are the Blocker and Major findings a
 * fix pass must clear; [unresolvedFindings] are the Blocker-only findings carried into the loud block
 * on cap exhaustion.
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

  /** Blocker-only findings that hard-block the run once the remediation budget is exhausted. */
  val unresolvedFindings: List<FeatureTaskRuntimeReviewFinding>
    get() = findings.filter { it.severity.blocksAdvance }
}
