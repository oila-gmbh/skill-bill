package skillbill.workflow.taskruntime.model

/**
 * Pure domain models for the structured review verdict that drives the `review_fix` backward edge. A
 * review emits a [FeatureTaskRuntimeReviewVerdict]: its findings classify the run as
 * [FeatureTaskRuntimeVerdict.CHANGES_REQUESTED] (any unresolved Blocker/Major) or
 * [FeatureTaskRuntimeVerdict.APPROVED]. The classification is a pure function of the findings — prose
 * alone cannot advance past a Blocker/Major — and the findings are carried into the `implement_fix`
 * briefing. No raw maps live in the model: the runner decodes wire output into these types.
 */

/** Severity of a single review finding. Blocker/Major are the advance-blocking severities. */
enum class FeatureTaskRuntimeReviewSeverity(val wireValue: String) {
  BLOCKER("blocker"),
  MAJOR("major"),
  MINOR("minor"),
  NIT("nit"),
  ;

  /** Whether a finding of this severity blocks advancing past review until resolved. */
  val blocksAdvance: Boolean
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
 * [FeatureTaskRuntimeReviewFinding.severity] blocks advance, else [FeatureTaskRuntimeVerdict.APPROVED]),
 * and [unresolvedFindings] are the advance-blocking findings carried into the loud block on cap
 * exhaustion.
 */
data class FeatureTaskRuntimeReviewVerdict(
  val findings: List<FeatureTaskRuntimeReviewFinding>,
) {
  val verdict: FeatureTaskRuntimeVerdict
    get() = if (findings.any { it.severity.blocksAdvance }) {
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
    } else {
      FeatureTaskRuntimeVerdict.APPROVED
    }

  val unresolvedFindings: List<FeatureTaskRuntimeReviewFinding>
    get() = findings.filter { it.severity.blocksAdvance }
}
