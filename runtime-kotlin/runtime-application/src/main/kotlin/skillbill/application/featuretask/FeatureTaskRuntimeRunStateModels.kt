package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal data class FeatureTaskRuntimeNonOutputAttempt(val paused: Boolean, val reason: String)

internal val NON_OUTPUT_LEDGER_ACTIONS = setOf(
  FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
  FeatureTaskRuntimePhaseLedgerAction.PAUSED,
)

internal const val REVIEW_INVALIDATION_AGENT_ID: String = "audit-gate-migration"

internal data class InFlightReentry(
  val destinationPhaseId: String,
  val edgeIteration: Int,
  val drivingVerdict: FeatureTaskRuntimeVerdict,
  val span: List<String>,
  val completedAfterEdge: Set<String>,
  val edgeSequenceNumber: Int,
) {
  val resumePhaseId: String
    get() = span.firstOrNull { phaseId -> phaseId !in completedAfterEdge } ?: destinationPhaseId
}
