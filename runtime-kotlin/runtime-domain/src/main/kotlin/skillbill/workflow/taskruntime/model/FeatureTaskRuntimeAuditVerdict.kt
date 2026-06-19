package skillbill.workflow.taskruntime.model

/**
 * Pure domain models for the structured audit verdict that drives the `audit_gap` backward edge. An
 * audit emits a [FeatureTaskRuntimeAuditVerdict]: its unmet criteria classify the run as
 * [FeatureTaskRuntimeVerdict.GAPS_FOUND] (any acceptance criterion still unmet) or
 * [FeatureTaskRuntimeVerdict.SATISFIED]. The classification is a pure function of the unmet criteria —
 * prose alone cannot advance past an unmet criterion — and the gaps are carried into the re-plan /
 * re-implement briefing. No raw maps live in the model: the runner decodes wire output into these types.
 */

/** One unmet acceptance criterion: a human-readable [message] scoping the re-plan/re-implement handoff. */
data class FeatureTaskRuntimeAuditCriterionGap(
  val message: String,
) {
  init {
    require(message.isNotBlank()) { "FeatureTaskRuntimeAuditCriterionGap.message must be non-blank." }
  }
}

/**
 * The structured verdict an `audit` phase emits: the unmet acceptance criteria. [verdict] is derived
 * purely from the gaps ([FeatureTaskRuntimeVerdict.GAPS_FOUND] when any criterion is unmet, else
 * [FeatureTaskRuntimeVerdict.SATISFIED]), and [unmetCriteria] are carried into the re-plan handoff and
 * the loud block on cap exhaustion.
 */
data class FeatureTaskRuntimeAuditVerdict(
  val unmetCriteria: List<FeatureTaskRuntimeAuditCriterionGap>,
) {
  val verdict: FeatureTaskRuntimeVerdict
    get() = if (unmetCriteria.isEmpty()) {
      FeatureTaskRuntimeVerdict.SATISFIED
    } else {
      FeatureTaskRuntimeVerdict.GAPS_FOUND
    }
}
