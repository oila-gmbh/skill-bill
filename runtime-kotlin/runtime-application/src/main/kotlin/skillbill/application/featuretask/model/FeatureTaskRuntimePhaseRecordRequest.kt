package skillbill.application.featuretask.model

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition

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
   * The model/effort this attempt was launched *from*: the same resolved value the `--model` argument
   * is rendered from, stamped by the running write before the child is spawned. It is not a
   * post-spawn observation — a kill in the window before the spawn leaves it on a `running` record
   * whose child never started, and it holds only as long as every agent adapter forwards
   * `modelOverride` verbatim. Read it as "what this attempt asked for", which is what answers "which
   * model is this phase on"; the settling writes are what turn it into a statement about a child
   * that ran.
   */
  val launchedModel: String? = null,
  val launchedEffort: String? = null,
  /**
   * True when this write knows the phase's launch outcome, making [launchedModel] and
   * [launchedEffort] authoritative *as a pair* — including their joint absence. Two writes set it:
   * the running write, which stamps the directive the launch argument is rendered from, and the
   * settling writes for exactly the launch exits where `LaunchResult.childNeverLaunched` holds —
   * that getter's KDoc owns the set, so consult it rather than restating it here. False leaves the
   * prior record's pair untouched, so a later block/pause/completion write around a child that did
   * run cannot erase or half-overwrite it.
   */
  val launchOutcomeKnown: Boolean = false,
  val reviewRunId: String? = null,
  val findingVerificationCheckpoint:
  List<FeatureTaskRuntimeFindingVerificationDisposition>? = null,
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
