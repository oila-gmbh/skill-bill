package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalSubtaskReviewStateLegacyContractTest {
  private fun currentRecord() = GoalSubtaskReviewState.initial(
    reviewBaseSha = "c".repeat(40),
    baselineUntrackedPaths = emptyList(),
    codeReviewMode = CodeReviewExecutionMode.AUTO,
  ).toArtifactMap()

  @Test
  fun `every legacy contract version loud-fails through the typed error with no silent migration`() {
    listOf("0.1", "0.2", "0.3", "0.5").forEach { legacyVersion ->
      val legacy = currentRecord().toMutableMap().apply { put("contract_version", legacyVersion) }

      val error = assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
        GoalSubtaskReviewState.fromArtifactMap(legacy)
      }
      assertTrue(
        error.message.orEmpty().contains(legacyVersion),
        "The rejection must name the quarantined legacy contract version '$legacyVersion'.",
      )
    }
  }

  @Test
  fun `a legacy record is never reinterpreted under the single-round remediation semantics`() {
    val legacy = currentRecord().toMutableMap().apply {
      put("contract_version", "0.5")
      put("code_review_mode", "inline")
    }

    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.fromArtifactMap(legacy)
    }
  }

  @Test
  fun `the durable contract version is 0_8`() {
    assertEquals("0.8", GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION)
  }
}
