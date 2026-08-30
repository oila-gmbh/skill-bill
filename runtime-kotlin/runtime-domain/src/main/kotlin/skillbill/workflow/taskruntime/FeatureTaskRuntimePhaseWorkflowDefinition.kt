package skillbill.workflow.taskruntime

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionTemplate

/**
 * The experimental runtime-driven feature-task pipeline definition, fully independent
 * from `FeatureImplementWorkflowDefinition`.
 *
 * The phase set is a DAG, not a chain: `requiredArtifactsByStep` encodes each phase's
 * upstream dependency set (the producing-phase ids whose latest output it consumes).
 * [phaseDeclarations] adds the derived-context declarations that the `WorkflowDefinition`
 * shape cannot express.
 */
object FeatureTaskRuntimePhaseWorkflowDefinition {
  const val PHASE_PREPLAN: String = "preplan"
  const val PHASE_PLAN: String = "plan"
  const val PHASE_IMPLEMENT: String = "implement"
  const val PHASE_IMPLEMENT_FIX: String = "implement_fix"
  const val PHASE_REVIEW: String = "review"
  const val PHASE_BUILD: String = "build"
  const val PHASE_VERIFY_FINDINGS: String = "verify_findings"
  const val PHASE_AUDIT: String = "audit"
  const val PHASE_VALIDATE: String = "validate"
  const val PHASE_WRITE_HISTORY: String = "write_history"
  const val PHASE_COMMIT_PUSH: String = "commit_push"
  const val PHASE_PR: String = "pr"

  const val DERIVED_CONTEXT_DIFF: String = "diff"
  const val DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE: String = "scoped_repository_state"

  // `review` is delivered the shared evidence projection and `pr` is not, so the two can no longer share
  // one diff key: the review keys now name a delivered reference, while PR keeps reading the branch diff
  // itself. Splitting the key rather than the instruction keeps PR's behaviour byte-identical.
  const val DERIVED_CONTEXT_PR_BRANCH_DIFF: String = "pr_branch_diff"

  // The M1 review->implement_fix remediation loop id, named once so durable accounting and telemetry
  // (the finished-event review-fix iteration count) reference the same loop the backward edge mints.
  const val REVIEW_FIX_LOOP_ID: String = "review_fix"

  // The audit->implement remediation loop id, named once so durable accounting and telemetry
  // (the finished-event audit-gap iteration count) reference the same loop the backward edge mints.
  const val AUDIT_GAP_LOOP_ID: String = "audit_gap"

  const val SEMANTIC_LOOP_WARNING_THRESHOLD: Int = 3

  const val PREPLAN_REGENERATION_LOOP_ID: String = "regenerate_preplan"

  const val MAX_RECORD_REGENERATION_ATTEMPTS: Int = 2

  val REGENERATION_LOOP_ID_BY_PRODUCER: Map<String, String> = emptyMap()

  val REGENERATION_PRODUCER_BY_CONSUMER: Map<String, String> = emptyMap()

  // Phases whose attempt watermark a review-generation restart rewinds. Only these carry a non-zero
  // evidence generation, so every other phase keeps a generation-blind key and a byte-identical
  // re-write after a restart stays an idempotent no-op.
  val GENERATION_SCOPED_PHASE_IDS: Set<String> = setOf(PHASE_REVIEW, PHASE_IMPLEMENT_FIX)

  val REGENERATION_LOOP_IDS: Set<String> = REGENERATION_LOOP_ID_BY_PRODUCER.values.toSet()

  fun isRegenerationLoopId(loopId: String): Boolean = loopId in REGENERATION_LOOP_IDS

  // Mutating phases reconcile the working tree to an intended target state. They are the phases the
  // idempotency contract governs: re-entering or resuming one must converge to target, treating an
  // already-applied change as a no-op rather than re-applying it. `implement` mutates from
  // intended-state plan inputs; `implement_fix` reconciles the current tree against the review
  // findings on the `review_fix` loop. Callers MUST consult this predicate rather than hardcoding a
  // single phase id.
  private val MUTATING_PHASES: Set<String> = setOf(PHASE_IMPLEMENT, PHASE_IMPLEMENT_FIX)

  fun isMutatingPhase(phaseId: String): Boolean = phaseId in MUTATING_PHASES

  // Producer phases re-run on schema-invalid or retryable-terminal output until the output is valid.
  // Downstream phases (`write_history`, `commit_push`, `pr`) block on the first invalid output.
  private val OUTPUT_RETRY_PHASES: Set<String> = setOf(
    PHASE_PREPLAN,
    PHASE_PLAN,
    PHASE_IMPLEMENT,
    PHASE_IMPLEMENT_FIX,
    PHASE_REVIEW,
    PHASE_VERIFY_FINDINGS,
    PHASE_BUILD,
    PHASE_AUDIT,
    PHASE_VALIDATE,
  )

  fun retriesOnInvalidOutput(phaseId: String): Boolean = phaseId in OUTPUT_RETRY_PHASES

  val definition: WorkflowDefinition = FeatureTaskRuntimePhaseWorkflowGraph.definition

  /** Legacy contract id retained only for compatibility rejection and regression assertions. */
  const val UPSTREAM_PHASE_RECEIPT_CONTRACT_ID: String = "feature_task_runtime.upstream_phase_receipt"

  /** Version of the retired [UPSTREAM_PHASE_RECEIPT_CONTRACT_ID]. */
  const val UPSTREAM_PHASE_RECEIPT_CONTRACT_VERSION: String = "0.1"

  /**
   * Closed downstream projection contracts. These names are deliberately consumer-oriented: a
   * producer's complete phase envelope remains private, while each edge receives only the fields
   * listed by its declaration.
   */
  object PhaseProjectionContract {
    const val VERSION: String = "0.1"
    const val PHASE_PROSE: String = "feature_task_runtime.phase_prose"
    const val AUDIT_CLEARANCE: String = "feature_task_runtime.audit_clearance"
    const val REVIEW_CLEARANCE: String = "feature_task_runtime.review_clearance"
    const val REVIEW_REPAIR_REQUEST: String = "feature_task_runtime.review_repair_request"
    const val FINDINGS_VERIFICATION_INPUT: String = "feature_task_runtime.findings_verification_input"
    const val FINDINGS_VERIFICATION_DISPOSITIONS: String = "feature_task_runtime.findings_verification_dispositions"
    const val REPAIR_LEDGER: String = "feature_task_runtime.repair_ledger"
    const val REPAIR_PLAN: String = "feature_task_runtime.repair_plan"
    const val CHANGE_RECEIPT: String = "feature_task_runtime.change_receipt"
    const val VALIDATION_REQUEST: String = "feature_task_runtime.validation_request"
    const val VALIDATION_RECEIPT: String = "feature_task_runtime.validation_receipt"
    const val BUILD_RECEIPT: String = "feature_task_runtime.build_receipt"
    const val BOUNDARY_CANDIDATES: String = "feature_task_runtime.boundary_candidates"
    const val HISTORY_RECEIPT: String = "feature_task_runtime.history_receipt"
    const val COMMIT_REQUEST: String = "feature_task_runtime.commit_request"
    const val COMMIT_RECEIPT: String = "feature_task_runtime.commit_receipt"
    const val PR_REQUEST: String = "feature_task_runtime.pr_request"
    const val PRIOR_GAP_MEMORY: String = "feature_task_runtime.prior_gap_memory"
  }

  fun phaseProjection(template: PhaseHandoffProjectionTemplate): PhaseHandoffProjectionDeclaration =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.phaseProjection(template)

  fun auditRemediationProjections(): List<PhaseHandoffProjectionDeclaration> =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.auditRemediationProjections()

  fun phaseProseDeclaration(
    consumerPhaseId: String,
    producingPhaseId: String = PHASE_PREPLAN,
    checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
      FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  ): PhaseHandoffProjectionDeclaration = FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.phaseProseDeclaration(
    consumerPhaseId,
    producingPhaseId,
    checkpointPolicy,
  )

  /**
   * The phase-neutral shared review evidence for the current checkpoint, delivered as a reference.
   *
   * `required = false` is load-bearing: the artifact is a derived cache, and a required declaration would
   * turn an absent or unreadable one into a hard launch rejection instead of the re-derivation AC-010
   * mandates. Absent evidence omits the projection; the phase still launches.
   */
  fun sharedReviewEvidenceDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.sharedReviewEvidenceDeclaration(consumerPhaseId)

  fun repairLedgerDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.repairLedgerDeclaration(consumerPhaseId)

  const val REPAIR_LEDGER_PROJECTION_NAME: String = "repair_ledger"

  /** The projection name the rewritten derived-context instructions point the agent at. */
  const val SHARED_REVIEW_EVIDENCE_PROJECTION_NAME: String = "shared_review_evidence"

  /** The projection name the prior-gap-memory declaration delivers under. */
  const val PRIOR_GAP_MEMORY_PROJECTION_NAME: String = "prior_gap_memory"

  /**
   * The runtime-derived prior-gap memory for an `audit_gap` remediation round, delivered to the
   * implement re-entry and the audit that follows it. `required = false` is load-bearing for AC-004:
   * absent memory must omit the projection rather than reject the launch of an in-flight workflow
   * that predates the projection.
   */
  fun priorGapMemoryDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.priorGapMemoryDeclaration(consumerPhaseId)

  /**
   * Private producer evidence that a runtime-owned projector may combine. These records are never
   * delivered directly; the consumer still sees only the closed declaration in
   * [phaseDeclarations].
   */
  fun runtimeProjectorProducerPhaseIds(consumerPhaseId: String): Set<String> =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.runtimeProjectorProducerPhaseIds(consumerPhaseId)

  val phaseDeclarations: Map<String, FeatureTaskRuntimePhaseDeclaration> =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.phaseDeclarations(definition)

  /**
   * Transition topology: the ordered [stepIds] forward pipeline plus the M1 `review_fix` and M2
   * `audit_gap` backward edges. The pipeline is audit-first: a clean run advances
   * `implement` -> `audit` -> `review` -> `verify_findings` -> `validate`, skipping loop-only
   * `implement_fix`.
   *
   * An audit `gaps_found` verdict reopens the `[implement, audit]` span to reconcile implementation
   * against the failing criteria using the immutable initial planning context, then re-`audit`.
   *
   * A `verify_findings` `findings_verified` verdict takes the single bounded `review_fix` backward
   * edge to `implement_fix` (perEdgeCap 1, cap exhaustion ADVANCE). The run always advances to
   * `validate` after that one fix round regardless of unresolved findings. `review` records its
   * verdict and never routes.
   *
   * [FeatureTaskRuntimeTransitionDeclaration.entryGates] makes the ordering enforceable rather than
   * merely implied: `review` is unreachable until `audit` has settled `satisfied`, and
   * `implement_fix` is unreachable until `verify_findings` has settled `findings_verified`.
   */
  val transitions: FeatureTaskRuntimeTransitionDeclaration =
    FeatureTaskRuntimePhaseWorkflowTransitions.transitions(definition)
}
