package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCeremony
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCeremonyScaling
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeExecutablePlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanCommitment
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePrePlanningDigest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePreplanCeremony
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewScope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

/**
 * The experimental runtime-driven feature-task pipeline definition, fully independent
 * from `FeatureImplementWorkflowDefinition`.
 *
 * The phase set is a DAG, not a chain: `requiredArtifactsByStep` encodes each phase's
 * upstream dependency set (the producing-phase ids whose latest output it consumes).
 * [phaseDeclarations] adds the derived-context declarations that the `WorkflowDefinition`
 * shape cannot express.
 */
@Suppress("TooManyFunctions")
object FeatureTaskRuntimePhaseWorkflowDefinition {
  const val PHASE_PREPLAN: String = "preplan"
  const val PHASE_PLAN: String = "plan"
  const val PHASE_IMPLEMENT: String = "implement"
  const val PHASE_PLAN_FIX: String = "plan_fix"
  const val PHASE_IMPLEMENT_FIX: String = "implement_fix"
  const val PHASE_REVIEW: String = "review"
  const val PHASE_BUILD: String = "build"
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

  // The shared advisory threshold for the semantic remediation loops (`review_fix`, `audit_gap`). Both
  // loops are unbounded by design — the verdict, never a count, settles them — so crossing this many
  // iterations warns the operator once and remediation continues. Declared here once so no seam can
  // drift to its own literal.
  const val SEMANTIC_LOOP_WARNING_THRESHOLD: Int = 3

  const val REMEDIATION_CHURN_CONSECUTIVE_ROUND_THRESHOLD: Int = 3

  const val REMEDIATION_ESCALATION_EVIDENCE_MIN_CONSECUTIVE_ROUNDS: Int = 1

  // SKILL-140: per-producer regeneration loop ids. A launch seam that quarantines an upstream
  // producer's rejected durable record re-enters that producer under its own bounded loop, so each
  // producer's regeneration cap and telemetry count are tracked independently of the others.
  const val PREPLAN_REGENERATION_LOOP_ID: String = "regenerate_preplan"
  const val PLAN_REGENERATION_LOOP_ID: String = "regenerate_plan"
  const val IMPLEMENT_REGENERATION_LOOP_ID: String = "regenerate_implement"

  // The pinned per-edge regeneration cap: a quarantined producer is re-run at most this many times
  // before the run blocks durably naming the quarantined record, producing phase, and attempt count.
  const val MAX_RECORD_REGENERATION_ATTEMPTS: Int = 2

  // The pinned consumer-phase -> (producing phase, regeneration loop id) mapping the launch seam and
  // the backward edges share. Each consumer that parses exactly one bounded planning projection from a
  // producer maps to that producer's regeneration edge. A consumer absent from this map has no
  // attributable producer, so its rejection blocks durably rather than re-entering an impossible edge.
  val REGENERATION_LOOP_ID_BY_PRODUCER: Map<String, String> = mapOf(
    PHASE_PREPLAN to PREPLAN_REGENERATION_LOOP_ID,
    PHASE_PLAN to PLAN_REGENERATION_LOOP_ID,
    PHASE_IMPLEMENT to IMPLEMENT_REGENERATION_LOOP_ID,
  )

  // Consumer phase -> the producer whose bounded planning projection it parses at its launch seam.
  val REGENERATION_PRODUCER_BY_CONSUMER: Map<String, String> = mapOf(
    PHASE_PLAN to PHASE_PREPLAN,
    PHASE_IMPLEMENT to PHASE_PLAN,
    PHASE_AUDIT to PHASE_IMPLEMENT,
  )

  // Phases whose attempt watermark a review-generation restart rewinds. Only these carry a non-zero
  // evidence generation, so every other phase keeps a generation-blind key and a byte-identical
  // re-write after a restart stays an idempotent no-op.
  val GENERATION_SCOPED_PHASE_IDS: Set<String> = setOf(PHASE_REVIEW, PHASE_PLAN_FIX, PHASE_IMPLEMENT_FIX)

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
    PHASE_PLAN_FIX,
    PHASE_IMPLEMENT_FIX,
    PHASE_REVIEW,
    PHASE_BUILD,
    PHASE_AUDIT,
    PHASE_VALIDATE,
  )

  fun retriesOnInvalidOutput(phaseId: String): Boolean = phaseId in OUTPUT_RETRY_PHASES

  val definition: WorkflowDefinition = WorkflowDefinition(
    skillName = "bill-feature-task",
    workflowName = "bill-feature-task",
    workflowIdPrefix = "wftr",
    defaultSessionPrefix = "ftr",
    contractVersion = WORKFLOW_STATE_CONTRACT_VERSION,
    workflowStatuses = setOf("pending", "running", "completed", "failed", "abandoned", "blocked", "paused"),
    stepStatuses = setOf("pending", "running", "completed", "failed", "blocked", "skipped", "paused"),
    terminalStatuses = setOf("completed", "failed", "abandoned"),
    defaultInitialStepId = PHASE_PREPLAN,
    stepIds =
    listOf(
      PHASE_PREPLAN,
      PHASE_PLAN,
      PHASE_IMPLEMENT,
      PHASE_AUDIT,
      PHASE_PLAN_FIX,
      PHASE_IMPLEMENT_FIX,
      PHASE_REVIEW,
      PHASE_BUILD,
      PHASE_VALIDATE,
      PHASE_WRITE_HISTORY,
      PHASE_COMMIT_PUSH,
      PHASE_PR,
    ),
    stepLabels =
    mapOf(
      PHASE_PREPLAN to "Phase 1: Pre-plan",
      PHASE_PLAN to "Phase 2: Plan",
      PHASE_IMPLEMENT to "Phase 3: Implement",
      PHASE_AUDIT to "Phase 4: Completeness Audit",
      PHASE_PLAN_FIX to "Phase 4a: Plan Fix",
      PHASE_IMPLEMENT_FIX to "Phase 4b: Implement Fix",
      PHASE_REVIEW to "Phase 5: Code Review",
      PHASE_BUILD to "Phase 5a: Build",
      PHASE_VALIDATE to "Phase 6: Quality Validation",
      PHASE_WRITE_HISTORY to "Phase 7: Boundary History",
      PHASE_COMMIT_PUSH to "Phase 8: Commit and Push",
      PHASE_PR to "Phase 9: Pull Request",
    ),
    requiredArtifactsByStep =
    mapOf(
      PHASE_PREPLAN to emptyList(),
      PHASE_PLAN to listOf(PHASE_PREPLAN),
      PHASE_IMPLEMENT to listOf(PHASE_PLAN),
      PHASE_AUDIT to listOf(PHASE_PLAN, PHASE_IMPLEMENT),
      PHASE_PLAN_FIX to listOf(PHASE_REVIEW, PHASE_PREPLAN, PHASE_PLAN),
      PHASE_IMPLEMENT_FIX to listOf(PHASE_REVIEW, PHASE_PLAN_FIX),
      PHASE_REVIEW to listOf(PHASE_AUDIT),
      PHASE_BUILD to listOf(PHASE_IMPLEMENT, PHASE_AUDIT),
      PHASE_VALIDATE to listOf(PHASE_IMPLEMENT, PHASE_AUDIT),
      PHASE_WRITE_HISTORY to listOf(PHASE_IMPLEMENT, PHASE_VALIDATE),
      PHASE_COMMIT_PUSH to listOf(PHASE_IMPLEMENT, PHASE_VALIDATE, PHASE_WRITE_HISTORY),
      PHASE_PR to listOf(PHASE_IMPLEMENT, PHASE_COMMIT_PUSH),
    ),
    resumeActions =
    mapOf(
      PHASE_PREPLAN to "Re-run the preplan phase from the run-invariants, then persist the validated digest output.",
      PHASE_PLAN to "Resume planning from the latest preplan digest, then persist the validated plan output.",
      PHASE_IMPLEMENT to
        "Resume implementation reconciliation from the immutable initial preplan and plan outputs when an " +
        "audit-gap loop is active, then persist the validated output.",
      PHASE_PLAN_FIX to
        "Resume the plan-fix phase from the latest review findings, the remediation repair ledger, and the " +
        "immutable initial preplan and plan outputs, then persist the validated repair plan.",
      PHASE_IMPLEMENT_FIX to
        "Resume the implement-fix phase from the latest repair plan and review findings, reconciling the " +
        "current tree, then persist the validated output.",
      PHASE_AUDIT to "Resume the completeness audit from the latest plan and implement outputs.",
      PHASE_REVIEW to "Resume code review from the latest implement and audit outputs and the derived diff context.",
      PHASE_BUILD to "Resume compile/build proof from the latest implement and audit outputs.",
      PHASE_VALIDATE to "Resume quality validation from the latest implement and audit outputs.",
      PHASE_WRITE_HISTORY to
        "Resume boundary history writing from the latest implement and settled build or validate output.",
      PHASE_COMMIT_PUSH to
        "Resume commit/push after verifying implement, the settled quality gate, and write_history outputs are current.",
      PHASE_PR to "Resume PR creation from the latest implement output, commit output, and derived diff context.",
    ),
    continuationReferenceSections = emptyMap(),
    continuationDirectives = emptyMap(),
    continuationArtifactOrder = emptyList(),
    openPriorStepsCompleted = false,
    // The per-phase records store is always persisted for a completed run, whereas no top-level
    // `pr` artifact is ever written; point the completed-run summary pointer at the store that
    // actually exists so resumeView's "done" next-action dereferences real persisted state.
    completedTerminalSummaryArtifact = FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY,
    workflowMode = "runtime",
    requiredArtifactPresenceResolver = FeatureTaskRuntimeRequiredArtifactPresenceResolver,
  )

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
    const val AUDIT_CLEARANCE: String = "feature_task_runtime.audit_clearance"
    const val AUDIT_REPAIR_REQUEST: String = "feature_task_runtime.audit_repair_request"
    const val REVIEW_CLEARANCE: String = "feature_task_runtime.review_clearance"
    const val REVIEW_REPAIR_REQUEST: String = "feature_task_runtime.review_repair_request"
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
  }

  @Suppress("LongParameterList")
  fun phaseProjection(
    consumerPhaseId: String,
    producingPhaseId: String,
    name: String,
    contractId: String,
    fields: List<String>,
    checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
      FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
    required: Boolean = true,
  ): PhaseHandoffProjectionDeclaration = PhaseHandoffProjectionDeclaration(
    consumerPhaseId = consumerPhaseId,
    sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(producingPhaseId),
    projectionName = name,
    projectionContractId = contractId,
    projectionContractVersion = PhaseProjectionContract.VERSION,
    promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
    budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
    declaredFieldNames = fields,
    checkpointPolicy = checkpointPolicy,
    required = required,
  )

  fun auditRemediationProjections(): List<PhaseHandoffProjectionDeclaration> = listOf(
    executablePlanDeclaration(PHASE_IMPLEMENT),
    phaseProjection(
      consumerPhaseId = PHASE_IMPLEMENT,
      producingPhaseId = PHASE_AUDIT,
      name = "audit_repair_request",
      contractId = PhaseProjectionContract.AUDIT_REPAIR_REQUEST,
      fields = listOf(
        "unmet_criteria",
        "repository_checkpoint",
      ),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    ),
  )

  fun reviewRetryProjections(): List<PhaseHandoffProjectionDeclaration> = listOf(
    phaseProjection(
      consumerPhaseId = PHASE_REVIEW,
      producingPhaseId = PHASE_IMPLEMENT_FIX,
      name = "change_receipt",
      contractId = PhaseProjectionContract.CHANGE_RECEIPT,
      fields = listOf("changed_paths", "tests_added", "tests_updated", "deviations", "repository_checkpoint"),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    ),
    phaseProjection(
      consumerPhaseId = PHASE_REVIEW,
      producingPhaseId = PHASE_AUDIT,
      name = "audit_clearance",
      contractId = PhaseProjectionContract.AUDIT_CLEARANCE,
      fields = listOf("clearance_status", "review_scope", "repository_checkpoint"),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    ),
  )

  /**
   * Concrete bounded planning projections for the preplan->plan, plan->implement, and
   * plan+implement->audit edges (AC-003/005/008/011). Each names its source, the concrete projection
   * contract id/version, the prompt-visible field set declared by the owning domain model, and a
   * budget; the consumer cannot widen the shape at runtime. The plan_commitment narrows the source
   * executable plan to its obligation-only subset for audit.
   */
  fun preplanningDigestDeclaration(
    consumerPhaseId: String,
    producingPhaseId: String = PHASE_PREPLAN,
  ): PhaseHandoffProjectionDeclaration = PhaseHandoffProjectionDeclaration(
    consumerPhaseId = consumerPhaseId,
    sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(producingPhaseId),
    projectionName = "${producingPhaseId}_preplanning_digest",
    projectionContractId = FeatureTaskRuntimePlanningProjectionContract.PREPLANNING_DIGEST_ID,
    projectionContractVersion = FeatureTaskRuntimePlanningProjectionContract.VERSION,
    promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
    budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
    declaredFieldNames = FeatureTaskRuntimePrePlanningDigest.DECLARED_FIELD_NAMES,
    checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
    required = true,
  )

  fun executablePlanDeclaration(
    consumerPhaseId: String,
    producingPhaseId: String = PHASE_PLAN,
  ): PhaseHandoffProjectionDeclaration = PhaseHandoffProjectionDeclaration(
    consumerPhaseId = consumerPhaseId,
    sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(producingPhaseId),
    projectionName = "${producingPhaseId}_executable_plan",
    projectionContractId = FeatureTaskRuntimePlanningProjectionContract.EXECUTABLE_PLAN_ID,
    projectionContractVersion = FeatureTaskRuntimePlanningProjectionContract.VERSION,
    promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
    budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
    declaredFieldNames = FeatureTaskRuntimeExecutablePlan.DECLARED_FIELD_NAMES,
    checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
    required = true,
  )

  fun planCommitmentDeclaration(
    consumerPhaseId: String,
    producingPhaseId: String = PHASE_PLAN,
  ): PhaseHandoffProjectionDeclaration = PhaseHandoffProjectionDeclaration(
    consumerPhaseId = consumerPhaseId,
    sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(producingPhaseId),
    projectionName = "${producingPhaseId}_plan_commitment",
    projectionContractId = FeatureTaskRuntimePlanningProjectionContract.PLAN_COMMITMENT_ID,
    projectionContractVersion = FeatureTaskRuntimePlanningProjectionContract.VERSION,
    promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
    budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
    declaredFieldNames = FeatureTaskRuntimePlanCommitment.DECLARED_FIELD_NAMES,
    checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
    required = true,
  )

  fun implementationReceiptDeclaration(
    consumerPhaseId: String,
    producingPhaseId: String = PHASE_IMPLEMENT,
  ): PhaseHandoffProjectionDeclaration = PhaseHandoffProjectionDeclaration(
    consumerPhaseId = consumerPhaseId,
    sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(producingPhaseId),
    projectionName = "${producingPhaseId}_implementation_receipt",
    projectionContractId = FeatureTaskRuntimePlanningProjectionContract.IMPLEMENTATION_RECEIPT_ID,
    projectionContractVersion = FeatureTaskRuntimePlanningProjectionContract.VERSION,
    promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
    budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
    declaredFieldNames = FeatureTaskRuntimeImplementationReceipt.DECLARED_FIELD_NAMES,
    // AC-012: the receipt is a producer claim, so audit refreshes its repository-derived context from a
    // freshly resolved checkpoint rather than inspecting whatever tree happens to be current. The
    // producer's own claims survive the refresh; only the repository evidence is re-derived.
    checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    required = true,
  )

  /**
   * The phase-neutral shared review evidence for the current checkpoint, delivered as a reference.
   *
   * `required = false` is load-bearing: the artifact is a derived cache, and a required declaration would
   * turn an absent or unreadable one into a hard launch rejection instead of the re-derivation AC-010
   * mandates. Absent evidence omits the projection; the phase still launches.
   */
  fun sharedReviewEvidenceDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    PhaseHandoffProjectionDeclaration(
      consumerPhaseId = consumerPhaseId,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence,
      projectionName = SHARED_REVIEW_EVIDENCE_PROJECTION_NAME,
      projectionContractId = FeatureTaskRuntimePlanningProjectionContract.SHARED_REVIEW_EVIDENCE_ID,
      projectionContractVersion = FeatureTaskRuntimePlanningProjectionContract.VERSION,
      promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
      budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
      declaredFieldNames = FeatureTaskRuntimeSharedReviewEvidenceReference.DECLARED_FIELD_NAMES,
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      required = false,
    )

  fun repairLedgerDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    PhaseHandoffProjectionDeclaration(
      consumerPhaseId = consumerPhaseId,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.RepairLedger,
      projectionName = REPAIR_LEDGER_PROJECTION_NAME,
      projectionContractId = PhaseProjectionContract.REPAIR_LEDGER,
      projectionContractVersion = PhaseProjectionContract.VERSION,
      promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
      budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
      declaredFieldNames = listOf(REPAIR_LEDGER_PROJECTION_NAME),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
      required = false,
    )

  const val REPAIR_LEDGER_PROJECTION_NAME: String = "repair_ledger"

  /** The projection name the rewritten derived-context instructions point the agent at. */
  const val SHARED_REVIEW_EVIDENCE_PROJECTION_NAME: String = "shared_review_evidence"

  /**
   * Closed-world projection matrix for every phase. Every upstream edge has an explicit typed
   * declaration; an omitted phase or edge is a contract error rather than permission to deliver a
   * complete producer receipt.
   */
  private val PHASE_PROJECTION_MATRIX: Map<String, List<PhaseHandoffProjectionDeclaration>> = mapOf(
    PHASE_PREPLAN to emptyList(),
    PHASE_PLAN to listOf(preplanningDigestDeclaration(PHASE_PLAN)),
    PHASE_IMPLEMENT to listOf(executablePlanDeclaration(PHASE_IMPLEMENT)),
    // The shared evidence is a floor for audit, never a replacement for its scoped repository read: it is
    // appended to the existing declarations, which stay exactly as they were.
    PHASE_AUDIT to listOf(
      planCommitmentDeclaration(PHASE_AUDIT),
      implementationReceiptDeclaration(PHASE_AUDIT),
      sharedReviewEvidenceDeclaration(PHASE_AUDIT),
    ),
    PHASE_PLAN_FIX to listOf(
      phaseProjection(
        PHASE_PLAN_FIX,
        PHASE_REVIEW,
        "review_repair_request",
        PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
        listOf("unresolved_blocker_findings", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
      ),
      repairLedgerDeclaration(PHASE_PLAN_FIX),
      preplanningDigestDeclaration(PHASE_PLAN_FIX),
      executablePlanDeclaration(PHASE_PLAN_FIX),
    ),
    PHASE_IMPLEMENT_FIX to listOf(
      phaseProjection(
        PHASE_IMPLEMENT_FIX,
        PHASE_REVIEW,
        "review_repair_request",
        PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
        listOf("unresolved_blocker_findings", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
      ),
      phaseProjection(
        PHASE_IMPLEMENT_FIX,
        PHASE_PLAN_FIX,
        "repair_plan",
        PhaseProjectionContract.REPAIR_PLAN,
        listOf("repair_plan"),
        required = false,
      ),
      repairLedgerDeclaration(PHASE_IMPLEMENT_FIX),
    ),
    PHASE_REVIEW to listOf(
      phaseProjection(
        PHASE_REVIEW,
        PHASE_AUDIT,
        "audit_clearance",
        PhaseProjectionContract.AUDIT_CLEARANCE,
        listOf("clearance_status", "review_scope", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      sharedReviewEvidenceDeclaration(PHASE_REVIEW),
    ),
    PHASE_VALIDATE to listOf(
      phaseProjection(
        PHASE_VALIDATE,
        PHASE_IMPLEMENT,
        "validation_request",
        PhaseProjectionContract.VALIDATION_REQUEST,
        listOf(
          "validation_strategy",
          "changed_paths",
          "required_checks",
          "repository_checkpoint",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        PHASE_VALIDATE,
        PHASE_AUDIT,
        "audit_clearance",
        PhaseProjectionContract.AUDIT_CLEARANCE,
        listOf("verdict", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
    ),
    PHASE_BUILD to listOf(
      phaseProjection(
        PHASE_BUILD,
        PHASE_IMPLEMENT,
        "validation_request",
        PhaseProjectionContract.VALIDATION_REQUEST,
        listOf(
          "validation_strategy",
          "changed_paths",
          "required_checks",
          "repository_checkpoint",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        PHASE_BUILD,
        PHASE_AUDIT,
        "audit_clearance",
        PhaseProjectionContract.AUDIT_CLEARANCE,
        listOf("verdict", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
    ),
    PHASE_WRITE_HISTORY to listOf(
      phaseProjection(
        PHASE_WRITE_HISTORY,
        PHASE_IMPLEMENT,
        "boundary_candidates",
        PhaseProjectionContract.BOUNDARY_CANDIDATES,
        listOf("changed_paths", "boundary_candidates"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        PHASE_WRITE_HISTORY,
        PHASE_VALIDATE,
        "validation_receipt",
        PhaseProjectionContract.VALIDATION_RECEIPT,
        listOf(
          "validation_status",
          "checks",
          "repository_checkpoint",
          "gate_run_count",
          "gate_runs",
          "suppression_justifications",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        PHASE_WRITE_HISTORY,
        PHASE_BUILD,
        "build_receipt",
        PhaseProjectionContract.BUILD_RECEIPT,
        listOf(
          "validation_status",
          "checks",
          "repository_checkpoint",
          "gate_run_count",
          "gate_runs",
          "suppression_justifications",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
    ),
    PHASE_COMMIT_PUSH to listOf(
      phaseProjection(
        PHASE_COMMIT_PUSH,
        PHASE_IMPLEMENT,
        "commit_request",
        PhaseProjectionContract.COMMIT_REQUEST,
        listOf(
          "path_inventory",
          "required_inclusions",
          "required_exclusions",
          "branch_identity",
          "gate_attestations",
          "repository_checkpoint",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        PHASE_COMMIT_PUSH,
        PHASE_VALIDATE,
        "validation_receipt",
        PhaseProjectionContract.VALIDATION_RECEIPT,
        listOf(
          "validation_status",
          "checks",
          "repository_checkpoint",
          "gate_run_count",
          "gate_runs",
          "suppression_justifications",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        PHASE_COMMIT_PUSH,
        PHASE_BUILD,
        "build_receipt",
        PhaseProjectionContract.BUILD_RECEIPT,
        listOf(
          "validation_status",
          "checks",
          "repository_checkpoint",
          "gate_run_count",
          "gate_runs",
          "suppression_justifications",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        PHASE_COMMIT_PUSH,
        PHASE_WRITE_HISTORY,
        "history_receipt",
        PhaseProjectionContract.HISTORY_RECEIPT,
        listOf("changed_paths", "decisions_recorded"),
      ),
    ),
    PHASE_PR to listOf(
      phaseProjection(
        PHASE_PR,
        PHASE_IMPLEMENT,
        "pr_request",
        PhaseProjectionContract.PR_REQUEST,
        listOf(
          "completed_task_ids",
          "changed_paths",
          "tests_added",
          "tests_updated",
          "deviations",
          "validation_summary",
          "base_branch",
          "diff_reference",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        PHASE_PR,
        PHASE_COMMIT_PUSH,
        "commit_receipt",
        PhaseProjectionContract.COMMIT_RECEIPT,
        listOf("commit_sha", "branch", "base_branch", "pushed"),
      ),
    ),
  )

  /**
   * Private producer evidence that a runtime-owned projector may combine. These records are never
   * delivered directly; the consumer still sees only the closed declaration in
   * [PHASE_PROJECTION_MATRIX].
   */
  fun runtimeProjectorProducerPhaseIds(consumerPhaseId: String): Set<String> = when (consumerPhaseId) {
    PHASE_VALIDATE -> setOf(PHASE_PLAN, PHASE_IMPLEMENT, PHASE_AUDIT)
    PHASE_BUILD -> setOf(PHASE_PLAN, PHASE_IMPLEMENT, PHASE_AUDIT)
    PHASE_WRITE_HISTORY -> setOf(PHASE_IMPLEMENT, PHASE_VALIDATE, PHASE_BUILD)
    PHASE_COMMIT_PUSH -> setOf(PHASE_IMPLEMENT, PHASE_VALIDATE, PHASE_BUILD, PHASE_WRITE_HISTORY)
    PHASE_PR -> setOf(PHASE_IMPLEMENT, PHASE_VALIDATE, PHASE_COMMIT_PUSH)
    else -> emptySet()
  }

  val phaseDeclarations: Map<String, FeatureTaskRuntimePhaseDeclaration> =
    definition.stepIds.associateWith { phaseId ->
      FeatureTaskRuntimePhaseDeclaration(
        phaseId = phaseId,
        projectionDeclarations = requireNotNull(PHASE_PROJECTION_MATRIX[phaseId]) {
          "No closed-world projection declaration for runtime phase '$phaseId'."
        },
        derivedContextKeys = when (phaseId) {
          PHASE_REVIEW -> listOf(DERIVED_CONTEXT_DIFF)
          PHASE_PR -> listOf(DERIVED_CONTEXT_PR_BRANCH_DIFF)
          // Audit compares the plan commitment and the receipt against the tree itself, so it needs
          // the scoped repository state at the envelope's checkpoint, not the branch-wide diff.
          PHASE_AUDIT -> listOf(DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE)
          else -> emptyList()
        },
      )
    }

  /**
   * Transition topology: the ordered [stepIds] forward pipeline plus the M1 `review_fix` and M2
   * `audit_gap` backward edges. The pipeline is audit-first: a clean run advances
   * `implement` -> `audit` -> `review`, so review only ever inspects a tree the audit already
   * declared complete. `implement_fix` sits between `audit` and `review` but is loop-only — the
   * forward edge skips it, so a clean run never launches a fix.
   *
   * An audit `gaps_found` verdict reopens the `[implement, audit]` span to reconcile implementation
   * against the failing criteria using the immutable initial planning context, then re-`audit`. That
   * span structurally excludes `review`, which now sits after `audit`. Audit-gap reconciliation is
   * unbounded because each new audit verdict is the authority on whether implementation is complete.
   *
   * A `review` `changes_requested` verdict reopens the `[plan_fix, review]` span. The edge declares no
   * finite cap: an unresolved advance-blocking finding is the sole re-entry signal, so remediation runs
   * as many rounds as those findings survive and the first clean review advances to `validate`
   * regardless of how many rounds preceded it. Blocker and Major both block advancing and both reopen
   * the span; Minor and Nit stay advisory. That span structurally excludes `audit`, so no review outcome
   * can reopen an audit repair plan.
   *
   * Inside the span the round runs `plan_fix` then `implement_fix`: `plan_fix` decides root cause before
   * any edit and may instead settle `escalated`, which has no successor and no edge, so it can neither
   * advance to `implement_fix` nor route back to `plan`. The `plan_fix` to `implement_fix` step is a
   * declared loop-only successor rather than a second backward edge, so one round mints exactly one
   * `review_fix` iteration.
   *
   * [FeatureTaskRuntimeTransitionDeclaration.entryGates] makes the ordering enforceable rather than
   * merely implied: `review` is unreachable until `audit` has settled `satisfied`, and any path that
   * would enter it earlier loud-fails.
   */
  val transitions: FeatureTaskRuntimeTransitionDeclaration =
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = definition.stepIds,
      entryGates = listOf(
        FeatureTaskRuntimePhaseEntryGate(
          phaseId = PHASE_REVIEW,
          requiredPhaseId = PHASE_AUDIT,
          requiredVerdict = FeatureTaskRuntimeVerdict.SATISFIED,
        ),
      ),
      backwardEdges = listOf(
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = PHASE_REVIEW,
          triggeringVerdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
          destinationPhaseId = PHASE_PLAN_FIX,
          loopId = REVIEW_FIX_LOOP_ID,
          perEdgeCap = null,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
          warnAfterIterations = SEMANTIC_LOOP_WARNING_THRESHOLD,
        ),
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = PHASE_AUDIT,
          triggeringVerdict = FeatureTaskRuntimeVerdict.GAPS_FOUND,
          destinationPhaseId = PHASE_IMPLEMENT,
          loopId = AUDIT_GAP_LOOP_ID,
          perEdgeCap = null,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
          warnAfterIterations = SEMANTIC_LOOP_WARNING_THRESHOLD,
        ),
        // SKILL-140: quarantine-and-regenerate edges. A consumer that rejects an upstream producer's
        // durable record at its launch seam re-enters that producer under a bounded cap; cap
        // exhaustion blocks durably (BLOCK, the default), naming the quarantined record.
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = PHASE_PLAN,
          triggeringVerdict = FeatureTaskRuntimeVerdict.RECORD_REJECTED,
          destinationPhaseId = PHASE_PREPLAN,
          loopId = PREPLAN_REGENERATION_LOOP_ID,
          perEdgeCap = MAX_RECORD_REGENERATION_ATTEMPTS,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        ),
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = PHASE_IMPLEMENT,
          triggeringVerdict = FeatureTaskRuntimeVerdict.RECORD_REJECTED,
          destinationPhaseId = PHASE_PLAN,
          loopId = PLAN_REGENERATION_LOOP_ID,
          perEdgeCap = MAX_RECORD_REGENERATION_ATTEMPTS,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        ),
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = PHASE_AUDIT,
          triggeringVerdict = FeatureTaskRuntimeVerdict.RECORD_REJECTED,
          destinationPhaseId = PHASE_IMPLEMENT,
          loopId = IMPLEMENT_REGENERATION_LOOP_ID,
          perEdgeCap = MAX_RECORD_REGENERATION_ATTEMPTS,
          capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        ),
      ),
      loopOnlyPhaseIds = setOf(PHASE_PLAN_FIX, PHASE_IMPLEMENT_FIX, PHASE_BUILD),
      loopOnlySuccessors = mapOf(PHASE_PLAN_FIX to PHASE_IMPLEMENT_FIX),
    )

  /** The declared backward edge for [loopId], or null when the id is not part of the topology. */
  fun backwardEdgeForLoop(loopId: String): FeatureTaskRuntimeBackwardEdge? =
    transitions.backwardEdges.firstOrNull { it.loopId == loopId }

  fun ceremonyScaling(featureSize: FeatureTaskRuntimeFeatureSize): FeatureTaskRuntimeCeremonyScaling =
    when (featureSize) {
      FeatureTaskRuntimeFeatureSize.SMALL -> FeatureTaskRuntimeCeremonyScaling(
        preplanCeremony = FeatureTaskRuntimePreplanCeremony.LIGHT,
        reviewScope = FeatureTaskRuntimeReviewScope.CURRENT_UNIT_OF_WORK,
        auditCeremony = FeatureTaskRuntimeAuditCeremony.LIGHT,
      )
      FeatureTaskRuntimeFeatureSize.MEDIUM,
      FeatureTaskRuntimeFeatureSize.LARGE,
      -> FeatureTaskRuntimeCeremonyScaling(
        preplanCeremony = FeatureTaskRuntimePreplanCeremony.FULL,
        reviewScope = FeatureTaskRuntimeReviewScope.BRANCH_DIFF,
        auditCeremony = FeatureTaskRuntimeAuditCeremony.FULL_PER_CRITERION,
      )
    }

  fun phaseDeclaration(
    phaseId: String,
    featureSize: FeatureTaskRuntimeFeatureSize,
  ): FeatureTaskRuntimePhaseDeclaration {
    val base = phaseDeclarations[phaseId] ?: error("No phase declaration for runtime phase '$phaseId'.")
    if (phaseId != PHASE_REVIEW) {
      return base
    }
    val reviewKey = when (ceremonyScaling(featureSize).reviewScope) {
      FeatureTaskRuntimeReviewScope.CURRENT_UNIT_OF_WORK -> "current_unit_of_work"
      FeatureTaskRuntimeReviewScope.BRANCH_DIFF -> "diff"
    }
    return base.copy(derivedContextKeys = listOf(reviewKey))
  }

  fun phaseDeclarationForQualityGate(
    phaseId: String,
    featureSize: FeatureTaskRuntimeFeatureSize,
    qualityGateSelection: FeatureTaskRuntimeQualityGateSelection,
  ): FeatureTaskRuntimePhaseDeclaration {
    val base = phaseDeclaration(phaseId, featureSize)
    if (phaseId != PHASE_WRITE_HISTORY && phaseId != PHASE_COMMIT_PUSH) {
      return base
    }
    val selectedGatePhase = when (qualityGateSelection) {
      FeatureTaskRuntimeQualityGateSelection.BUILD -> PHASE_BUILD
      FeatureTaskRuntimeQualityGateSelection.VALIDATE -> PHASE_VALIDATE
    }
    val omittedGatePhase = if (selectedGatePhase == PHASE_BUILD) PHASE_VALIDATE else PHASE_BUILD
    return base.copy(
      projectionDeclarations = base.projectionDeclarations.filter { declaration ->
        val source = declaration.sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput
        source?.producingPhaseId != omittedGatePhase
      },
    )
  }
}
