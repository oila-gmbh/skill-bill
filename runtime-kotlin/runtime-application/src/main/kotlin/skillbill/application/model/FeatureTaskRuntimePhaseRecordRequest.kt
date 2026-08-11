package skillbill.application.model

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

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
  val rejectedOutput: String? = null,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val repositoryFingerprint: String? = null,
  /** Present only on a terminal blocked record so blocked-ness survives ledger pruning. */
  val blockedReason: String? = null,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  val fileManifestBefore: List<String> = emptyList(),
  val fileManifestAfter: List<String> = emptyList(),
  val fileManifestIntroduced: List<String> = emptyList(),
  /** Runtime-minted backward-edge context for the resume watermark; never agent-reported. */
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val reviewPassNumber: Int? = null,
  /**
   * Canonical refs of the acceptance criteria this audit was asked to verify: the declared set minus
   * the criteria already durably closed. Runtime-derived, never agent-reported, and empty for every
   * non-audit phase.
   */
  val auditScopeCriterionRefs: List<String> = emptyList(),
  /**
   * The model/effort the phase's child was actually launched with, taken from the same resolved
   * value the launch argument was rendered from so the record cannot drift from the child.
   */
  val launchedModel: String? = null,
  val launchedEffort: String? = null,
  /**
   * True when this write knows the phase's launch outcome, making [launchedModel] and
   * [launchedEffort] authoritative *as a pair* — including their joint absence, for a child that
   * provably never launched (a provider-limit refusal or a pre-launch infra/seam failure). False
   * leaves the prior record's pair untouched, so a later block/pause/completion write cannot erase
   * or half-overwrite it.
   */
  val launchOutcomeKnown: Boolean = false,
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
