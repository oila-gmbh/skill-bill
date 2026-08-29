package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY

internal object FeatureTaskRuntimePhaseWorkflowGraph {
  val definition: WorkflowDefinition = WorkflowDefinition(
    skillName = "bill-feature-task",
    workflowName = "bill-feature-task",
    workflowIdPrefix = "wftr",
    defaultSessionPrefix = "ftr",
    contractVersion = WORKFLOW_STATE_CONTRACT_VERSION,
    workflowStatuses = setOf("pending", "running", "completed", "failed", "abandoned", "blocked", "paused"),
    stepStatuses = setOf("pending", "running", "completed", "failed", "blocked", "skipped", "paused"),
    terminalStatuses = setOf("completed", "failed", "abandoned"),
    defaultInitialStepId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
    stepIds =
    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
    ),
    stepLabels =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to "Phase 1: Pre-plan",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to "Phase 2: Plan",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to "Phase 3: Implement",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to "Phase 4: Completeness Audit",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to "Phase 5: Code Review",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to "Phase 5a: Verify Findings",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to "Phase 5b: Implement Fix",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to "Phase 5c: Build",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to "Phase 6: Quality Validation",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to "Phase 7: Boundary History",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to "Phase 8: Commit and Push",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to "Phase 9: Pull Request",
    ),
    requiredArtifactsByStep =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to emptyList(),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
        listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
        listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
        listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to
        listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to
        listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      ),
    ),
    resumeActions =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
        "Re-run the preplan phase from the run-invariants, then persist the validated planning prose output.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
        "Resume planning from the latest preplan prose, then persist the validated planning prose output.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
        "Resume implementation reconciliation from the immutable initial preplan and plan outputs when an " +
        "audit-gap loop is active, then persist the validated output.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to
        "Resume the implement-fix phase from the latest verified findings, reconciling the " +
        "current tree, then persist the validated output.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
        "Resume the completeness audit from the latest plan and implement outputs.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
        "Resume code review from the latest implement and audit outputs and the derived " +
        "diff context.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to
        "Resume finding verification from the latest review output and in-flight dispositions " +
        "without re-running review.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to
        "Resume compile/build proof from the latest plan and audit outputs.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to
        "Resume quality validation from the latest plan and audit outputs.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
        "Resume boundary history writing from the latest implement and settled build or " +
        "validate output.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
        "Resume commit/push after verifying implement, the settled quality gate, " +
        "and write_history outputs are current.",
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
        "Resume PR creation from the latest implement output, commit output, and derived " +
        "diff context.",
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
}
