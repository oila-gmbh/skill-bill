package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidWorkflowStateSchemaError

/**
 * Effect-free typed verdict a verifying phase's structured output yields, which the bounded-cyclic
 * transition function reads to decide whether to re-enter an upstream phase or progress forward. The
 * abstraction is intentionally generic: this subtask defines only the type and a default
 * [ADVANCE]-only verdict for phases with no verifying output; concrete review/audit verdict schemas
 * are added in later subtasks.
 *
 * [wireValue] is stable for durable persistence and [fromWire] is a strict decode mirroring
 * [FeatureTaskRuntimePhaseLedgerAction]'s wire pattern, so a persisted verdict round-trips and an
 * unknown wire value loud-fails rather than being coerced.
 */
data class FeatureTaskRuntimeVerdict(
  val wireValue: String,
) {
  init {
    require(wireValue.isNotBlank()) { "FeatureTaskRuntimeVerdict.wireValue must be non-blank." }
  }

  companion object {
    /** The default verdict for a phase with no verifying output: always progresses forward. */
    val ADVANCE: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("advance")

    /** A review verdict with no unresolved Blocker findings: the run advances past review. */
    val APPROVED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("approved")

    /**
     * A review verdict carrying unresolved Blocker findings: the run takes the `review_fix`
     * backward edge to the `implement_fix` phase to reconcile them on the current tree.
     */
    val CHANGES_REQUESTED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("changes_requested")

    /** Goal-only continuation verdict: unresolved critical findings reached the two-pass cap. */
    val REVIEW_CAP_REACHED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("review_cap_reached")

    val REVIEW_SKIPPED_BY_USER: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("review_skipped_by_user")

    /** An audit verdict with no unmet acceptance criteria: the run advances past audit to validate. */
    val SATISFIED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("satisfied")

    /**
     * An audit verdict carrying unmet acceptance criteria: the run takes the `audit_gap` backward edge
     * to `implement` to reconcile against the failing criteria using the initial planning context.
     */
    val GAPS_FOUND: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("gaps_found")

    /**
     * The closed audit vocabulary. [fromWire] deliberately accepts any non-blank value so durable
     * records round-trip, so a consumer that acts on an audit verdict — the `review` entry gate —
     * matches against this set rather than trusting an arbitrary emitted string.
     */
    val AUDIT_VERDICTS: Set<FeatureTaskRuntimeVerdict> = setOf(SATISFIED, GAPS_FOUND)

    fun fromWire(value: String): FeatureTaskRuntimeVerdict =
      value.takeIf(String::isNotBlank)?.let(::FeatureTaskRuntimeVerdict)
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime verdict wire value must be a non-blank string, was '$value'.",
        )
  }
}
