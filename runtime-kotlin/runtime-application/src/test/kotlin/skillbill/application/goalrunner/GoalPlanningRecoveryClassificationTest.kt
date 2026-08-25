package skillbill.application.goalrunner

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GoalPlanningRecoveryClassificationTest {
  @Test
  fun `phase output contract version mismatch classifies as hard reset`() {
    val error = IncompatibleGoalPlanningPreparationRecoveryError(
      workflowId = "wftr-parent",
      subtaskId = 2,
      reason = "stored import provenance differs from the hydration request at " +
        "phase_output_contract_version",
    )

    assertEquals(GoalPlanningRecoveryKind.HARD_RESET, classifyGoalPlanningRecovery(error))
    val blocked = goalPlanningChildImportConflictBlockedReason("SKILL-200", 2, error)
    assertContains(blocked, goalPlanningHardResetRemedy("SKILL-200"))
    assertContains(blocked, FEATURE_TASK_RUNTIME_CONTRACT_VERSION)
    assertEquals(false, blocked.contains("goal replan"))
  }

  @Test
  fun `regenerated after hydration classifies as scoped replan`() {
    val error = IncompatibleGoalPlanningPreparationRecoveryError(
      workflowId = "wftr-parent",
      subtaskId = 2,
      reason = "stored goal planning 'plan' record for subtask 2 was already imported by this child " +
        "and the stored version now fails its projection contract. This occurs when the shared " +
        "preplan or subtask plan was regenerated after the child was hydrated, making the " +
        "previously-imported bytes stale. Projection failure: produced_outputs missing",
    )

    assertEquals(GoalPlanningRecoveryKind.SCOPED_REPLAN, classifyGoalPlanningRecovery(error))
    val blocked = goalPlanningChildImportConflictBlockedReason("SKILL-200", 2, error)
    assertContains(blocked, staleChildPlanningRecoveryCommand("SKILL-200", 2))
    assertEquals(false, blocked.contains("--hard"))
  }

  @Test
  fun `preparation schema phase output provenance failure classifies as hard reset via cause`() {
    val cause = InvalidGoalPlanningPreparationSchemaError(
      sourceLabel = "wftr-parent",
      fieldPath = "provenance.phase_output_contract_version",
      reason = "must be the constant value '$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'. Existing workflow " +
        "state is incompatible; hard-reset it.",
    )
    assertEquals(
      GoalPlanningRecoveryKind.HARD_RESET,
      classifyGoalPlanningRecovery("stored preparation failed", cause),
    )
  }
}
