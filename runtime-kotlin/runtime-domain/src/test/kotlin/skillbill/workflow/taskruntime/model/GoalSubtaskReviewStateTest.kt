package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.review.CodeReviewExecutionMode

class GoalSubtaskReviewStateTest {
  @Test
  fun `two unresolved passes preserve the cap disposition without permitting a third pass`() {
    val firstPass = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = listOf("preexisting.tmp"),
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
    )

    val capped = firstPass.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Unsafe mutation")),
    )

    assertTrue(capped.reviewCapReached)
    assertEquals(2, capped.completedPassCount)
    assertEquals(FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED, capped.passResults.last().verdict)
    assertEquals(capped, capped.reserveNextPass())
  }

  @Test
  fun `strict artifact decoding rejects unknown and unsupported verdict fields`() {
    val state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "b".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    )

    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.fromArtifactMap(state.toArtifactMap() + ("unexpected" to true))
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.fromArtifactMap(
        state.toArtifactMap() + ("pass_results" to listOf(
          mapOf(
            "pass_number" to 1,
            "verdict" to "arbitrary",
            "review_result_artifact" to "goal_subtask_review_results.1",
            "unresolved_finding_count" to 0,
            "findings" to emptyList<Any>(),
          ),
        )) + ("completed_pass_count" to 1),
      )
    }
  }

  @Test
  fun `goal review persistence requires paired continuation and review state artifacts`() {
    val state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "c".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    )
    val continuation = FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = "SKILL-119",
      subtaskId = 2,
      suppressPr = true,
      goalBranch = "feat/SKILL-119",
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    )

    assertEquals(null, GoalSubtaskReviewArtifactDecoder.decode(emptyMap()))
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewArtifactDecoder.decode(
        mapOf(GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toArtifactMap()),
      )
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewArtifactDecoder.decode(
        mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap()),
      )
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewArtifactDecoder.decode(
        mapOf(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toArtifactMap(),
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to emptyMap<String, String>(),
        ),
      )
    }
  }
}
