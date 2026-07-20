package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCeremony
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCeremonyScaling
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePreplanCeremony
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewScope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

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
  const val PHASE_AUDIT: String = "audit"
  const val PHASE_VALIDATE: String = "validate"
  const val PHASE_WRITE_HISTORY: String = "write_history"
  const val PHASE_COMMIT_PUSH: String = "commit_push"
  const val PHASE_PR: String = "pr"

  // The M1 review->implement_fix remediation loop id, named once so durable accounting and telemetry
  // (the finished-event review-fix iteration count) reference the same loop the backward edge mints.
  const val REVIEW_FIX_LOOP_ID: String = "review_fix"

  // The audit->implement remediation loop id, named once so durable accounting and telemetry
  // (the finished-event audit-gap iteration count) reference the same loop the backward edge mints.
  const val AUDIT_GAP_LOOP_ID: String = "audit_gap"

  // Mutating phases reconcile the working tree to an intended target state. They are the phases the
  // idempotency contract governs: re-entering or resuming one must converge to target, treating an
  // already-applied change as a no-op rather than re-applying it. `implement` mutates from
  // intended-state plan inputs; `implement_fix` reconciles the current tree against the review
  // findings on the `review_fix` loop. Callers MUST consult this predicate rather than hardcoding a
  // single phase id.
  private val MUTATING_PHASES: Set<String> = setOf(PHASE_IMPLEMENT, PHASE_IMPLEMENT_FIX)

  fun isMutatingPhase(phaseId: String): Boolean = phaseId in MUTATING_PHASES

  val definition: WorkflowDefinition = WorkflowDefinition(
    skillName = "bill-feature-task",
    workflowName = "bill-feature-task",
    workflowIdPrefix = "wftr",
    defaultSessionPrefix = "ftr",
    contractVersion = WORKFLOW_STATE_CONTRACT_VERSION,
    workflowStatuses = setOf("pending", "running", "completed", "failed", "abandoned", "blocked"),
    stepStatuses = setOf("pending", "running", "completed", "failed", "blocked", "skipped"),
    terminalStatuses = setOf("completed", "failed", "abandoned"),
    defaultInitialStepId = PHASE_PREPLAN,
    stepIds =
    listOf(
      PHASE_PREPLAN,
      PHASE_PLAN,
      PHASE_IMPLEMENT,
      PHASE_IMPLEMENT_FIX,
      PHASE_REVIEW,
      PHASE_AUDIT,
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
      PHASE_IMPLEMENT_FIX to "Phase 3b: Implement Fix",
      PHASE_REVIEW to "Phase 4: Code Review",
      PHASE_AUDIT to "Phase 5: Completeness Audit",
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
      PHASE_IMPLEMENT_FIX to listOf(PHASE_PLAN, PHASE_IMPLEMENT, PHASE_REVIEW),
      PHASE_REVIEW to listOf(PHASE_IMPLEMENT),
      PHASE_AUDIT to listOf(PHASE_PLAN, PHASE_IMPLEMENT, PHASE_REVIEW),
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
      PHASE_IMPLEMENT_FIX to
        "Resume the implement-fix phase from the latest review findings, reconciling the current tree, " +
        "then persist the validated output.",
      PHASE_REVIEW to "Resume code review from the latest implement output and the derived diff context.",
      PHASE_AUDIT to "Resume the completeness audit from the latest plan, implement, and review outputs.",
      PHASE_VALIDATE to "Resume quality validation from the latest implement and audit outputs.",
      PHASE_WRITE_HISTORY to
        "Resume boundary history writing from the latest implement and validate outputs.",
      PHASE_COMMIT_PUSH to
        "Resume commit/push after verifying implement, validate, and write_history outputs are current.",
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

  /**
   * Per-phase declarations: consumed upstream phase ids (mirroring
   * [WorkflowDefinition.requiredArtifactsByStep]) plus derived-context keys.
   * `review` and `pr` declare derived `diff` context for branch-diff inspection.
   */
  val phaseDeclarations: Map<String, FeatureTaskRuntimePhaseDeclaration> =
    definition.stepIds.associateWith { phaseId ->
      FeatureTaskRuntimePhaseDeclaration(
        phaseId = phaseId,
        consumedUpstreamPhaseIds = definition.requiredArtifactsByStep[phaseId].orEmpty(),
        derivedContextKeys = if (phaseId in setOf(PHASE_REVIEW, PHASE_PR)) listOf("diff") else emptyList(),
      )
    }

  /**
   * Transition topology: the ordered [stepIds] forward pipeline plus the M1 `review_fix` and M2
   * `audit_gap` backward edges. `implement_fix` sits between `implement` and `review` in the pipeline
   * but is loop-only — the forward edge skips it, so a clean run advances `implement` -> `review` and
   * never launches a fix. A `review` `changes_requested` verdict reopens the `[implement_fix, review]`
   * span (the backward destination precedes the source), bounded at one review->fix iteration; an
   * `approved` verdict advances to `audit`, while exhausting that remediation budget with the review
   * still unsatisfied blocks the run instead of advancing. An audit
   * `gaps_found` verdict reopens the
   * `[implement, audit]` span to reconcile implementation against the failing criteria using the
   * immutable initial planning context and re-pass through `review` (incl. its `review_fix`
   * loop) before re-`audit`. Audit-gap reconciliation is unbounded because each new audit verdict is
   * the authority on whether implementation is complete; the first `satisfied` verdict advances to
   * `validate`.
   */
  val transitions: FeatureTaskRuntimeTransitionDeclaration =
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = definition.stepIds,
      backwardEdges = listOf(
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = PHASE_REVIEW,
          triggeringVerdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
          destinationPhaseId = PHASE_IMPLEMENT_FIX,
          loopId = REVIEW_FIX_LOOP_ID,
          perEdgeCap = 1,
          capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
        ),
        FeatureTaskRuntimeBackwardEdge(
          fromPhaseId = PHASE_AUDIT,
          triggeringVerdict = FeatureTaskRuntimeVerdict.GAPS_FOUND,
          destinationPhaseId = PHASE_IMPLEMENT,
          loopId = AUDIT_GAP_LOOP_ID,
          perEdgeCap = null,
        ),
      ),
      loopOnlyPhaseIds = setOf(PHASE_IMPLEMENT_FIX),
    )

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
}
