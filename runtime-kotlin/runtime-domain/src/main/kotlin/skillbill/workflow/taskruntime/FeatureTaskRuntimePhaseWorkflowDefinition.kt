package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration

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
  const val PHASE_PLAN: String = "plan"
  const val PHASE_IMPLEMENT: String = "implement"
  const val PHASE_REVIEW: String = "review"
  const val PHASE_AUDIT: String = "audit"
  const val PHASE_VALIDATE: String = "validate"

  val definition: WorkflowDefinition = WorkflowDefinition(
    skillName = "feature-task-runtime",
    workflowName = "feature-task-runtime",
    workflowIdPrefix = "wftr",
    defaultSessionPrefix = "ftr",
    contractVersion = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
    workflowStatuses = setOf("pending", "running", "completed", "failed", "abandoned", "blocked"),
    stepStatuses = setOf("pending", "running", "completed", "failed", "blocked", "skipped"),
    terminalStatuses = setOf("completed", "failed", "abandoned"),
    defaultInitialStepId = PHASE_PLAN,
    stepIds =
    listOf(
      PHASE_PLAN,
      PHASE_IMPLEMENT,
      PHASE_REVIEW,
      PHASE_AUDIT,
      PHASE_VALIDATE,
    ),
    stepLabels =
    mapOf(
      PHASE_PLAN to "Phase 1: Plan",
      PHASE_IMPLEMENT to "Phase 2: Implement",
      PHASE_REVIEW to "Phase 3: Code Review",
      PHASE_AUDIT to "Phase 4: Completeness Audit",
      PHASE_VALIDATE to "Phase 5: Quality Validation",
    ),
    requiredArtifactsByStep =
    mapOf(
      PHASE_PLAN to emptyList(),
      PHASE_IMPLEMENT to listOf(PHASE_PLAN),
      PHASE_REVIEW to listOf(PHASE_IMPLEMENT),
      PHASE_AUDIT to listOf(PHASE_PLAN, PHASE_IMPLEMENT, PHASE_REVIEW),
      PHASE_VALIDATE to listOf(PHASE_IMPLEMENT, PHASE_AUDIT),
    ),
    resumeActions =
    mapOf(
      PHASE_PLAN to "Re-run the plan phase from the run-invariants, then persist the validated plan output.",
      PHASE_IMPLEMENT to "Resume implementation from the latest plan output, then persist the validated output.",
      PHASE_REVIEW to "Resume code review from the latest implement output and the derived diff context.",
      PHASE_AUDIT to "Resume the completeness audit from the latest plan, implement, and review outputs.",
      PHASE_VALIDATE to "Resume quality validation from the latest implement and audit outputs.",
    ),
    continuationReferenceSections = emptyMap(),
    continuationDirectives = emptyMap(),
    continuationArtifactOrder = emptyList(),
    openPriorStepsCompleted = false,
    completedTerminalSummaryArtifact = PHASE_VALIDATE,
  )

  /**
   * Per-phase declarations: consumed upstream phase ids (mirroring
   * [WorkflowDefinition.requiredArtifactsByStep]) plus derived-context keys.
   * `review` is the only phase that declares a derived `diff` context.
   */
  val phaseDeclarations: Map<String, FeatureTaskRuntimePhaseDeclaration> =
    definition.stepIds.associateWith { phaseId ->
      FeatureTaskRuntimePhaseDeclaration(
        phaseId = phaseId,
        consumedUpstreamPhaseIds = definition.requiredArtifactsByStep[phaseId].orEmpty(),
        derivedContextKeys = if (phaseId == PHASE_REVIEW) listOf("diff") else emptyList(),
      )
    }
}
