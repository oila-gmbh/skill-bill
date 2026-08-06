package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Quarantine-and-regenerate is the declared migration story. A record written at any pre-SKILL-157
 * contract version carries the old two-pass remediation ceiling, so it loud-fails at the read seam
 * rather than being silently migrated or reinterpreted.
 */
class GoalSubtaskReviewStateLegacyContractTest {
  private fun currentRecord() = GoalSubtaskReviewState.initial(
    reviewBaseSha = "c".repeat(40),
    baselineUntrackedPaths = emptyList(),
    codeReviewMode = CodeReviewExecutionMode.AUTO,
  ).toArtifactMap()

  @Test
  fun `every legacy contract version loud-fails through the typed error with no silent migration`() {
    listOf("0.1", "0.2", "0.3").forEach { legacyVersion ->
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
  fun `a legacy record is never reinterpreted under the unbounded pass semantics`() {
    val legacy = currentRecord().toMutableMap().apply {
      put("contract_version", "0.3")
      put("code_review_mode", "inline")
    }

    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.fromArtifactMap(legacy)
    }
  }

  @Test
  fun `the durable contract version is the commit-focused-accounting 0_5`() {
    assertEquals("0.5", GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION)
  }
}
