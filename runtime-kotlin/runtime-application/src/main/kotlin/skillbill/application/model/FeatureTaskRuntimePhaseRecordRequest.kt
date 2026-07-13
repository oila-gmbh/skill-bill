package skillbill.application.model

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction

/**
 * Request to persist one per-phase record. Carries only runtime-owned facts plus the validated
 * output artifact; the recorder mints timestamps and duration, so none ever crosses from an agent.
 */
data class FeatureTaskRuntimePhaseStateRequest(
  val workflowId: String,
  val phaseId: String,
  val status: String,
  val attemptCount: Int,
  val resolvedAgentId: String,
  val finished: Boolean,
  val outputArtifact: String? = null,
  /** Present only on a terminal blocked record so blocked-ness survives ledger pruning. */
  val blockedReason: String? = null,
  /** Runtime-minted backward-edge context for the resume watermark; never agent-reported. */
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val reviewPassNumber: Int? = null,
)

/**
 * Request to append one phase ledger entry. The recorder mints the timestamp and the monotonic
 * sequence, so the caller never supplies time or ordering.
 */
data class FeatureTaskRuntimePhaseLedgerRequest(
  val workflowId: String,
  val action: FeatureTaskRuntimePhaseLedgerAction,
  val phaseId: String,
  val attemptCount: Int,
  val resolvedAgentId: String? = null,
  val fixLoopIteration: Int? = null,
  val blockedReason: String? = null,
  /** Runtime-minted backward-edge trail, distinct from attempt_count; never agent-reported. */
  val loopId: String? = null,
  val edgeIteration: Int? = null,
)
