package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SKILL-142 AC-018: quarantine-and-regenerate is the declared migration story, so a legacy `0.1`
 * review-state record loud-fails rather than being silently migrated.
 */
class GoalSubtaskReviewStateLegacyContractTest {
  @Test
  fun `a legacy 0_1 record loud-fails through the typed error with no silent migration`() {
    val current = GoalSubtaskReviewState.initial(
      reviewBaseSha = "c".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    )
    val legacy = current.toArtifactMap().toMutableMap().apply { put("contract_version", "0.1") }

    val error = assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.fromArtifactMap(legacy)
    }
    assertTrue(
      error.message.orEmpty().contains("0.1"),
      "The rejection must name the quarantined legacy contract version.",
    )
  }

  @Test
  fun `the durable contract version is no longer 0_1`() {
    assertEquals("0.2", GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION)
  }
}
