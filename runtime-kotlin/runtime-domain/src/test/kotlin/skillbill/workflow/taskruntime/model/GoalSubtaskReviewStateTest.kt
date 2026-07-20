package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalSubtaskReviewStateTest {
  @Test
  fun `completed passes record immutable execution modes while legacy records remain byte stable`() {
    val initial = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    )
    assertEquals(
      initial.toArtifactMap(),
      GoalSubtaskReviewState.fromArtifactMap(initial.toArtifactMap()).toArtifactMap(),
    )

    val completed = initial.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    )
    assertEquals(CodeReviewExecutionMode.DELEGATED, completed.passResults.single().executedMode)
    val recordedPass = (completed.toArtifactMap()["pass_results"] as List<*>).single() as Map<*, *>
    assertEquals("delegated", recordedPass["executed_mode"])
  }

  @Test
  fun `a recorded mode outside the immutable sequence fails loudly`() {
    val initial = GoalSubtaskReviewState.initial(
      reviewBaseSha = "b".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    )
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskReviewState.fromArtifactMap(
        initial.toArtifactMap() + mapOf(
          "completed_pass_count" to 1,
          "pass_results" to listOf(
            mapOf(
              "pass_number" to 1,
              "verdict" to "approved",
              "review_result_artifact" to "goal_subtask_review_results.1",
              "unresolved_finding_count" to 0,
              "findings" to emptyList<Any>(),
              "executed_mode" to "inline",
            ),
          ),
        ),
      )
    }
  }

  @Test
  fun `an existing reservation is reused after interruption`() {
    val reserved = GoalSubtaskReviewState.initial(
      reviewBaseSha = "c".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    ).reserveNextPass()

    assertEquals(reserved, reserved.reserveNextPass())
    assertEquals(1, reserved.reservedPassNumber)
    assertEquals(0, reserved.completedPassCount)
  }

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
  fun `a second pass with only major findings never reaches the cap disposition`() {
    val firstPass = GoalSubtaskReviewState.initial(
      reviewBaseSha = "e".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Unsafe mutation")),
    )

    val secondPass = firstPass.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 2,
      findings = listOf(
        GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior"),
        GoalSubtaskReviewCompactFinding("nit", "Service", "Naming"),
      ),
    )

    assertFalse(secondPass.reviewCapReached)
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, secondPass.passResults.last().verdict)
  }

  @Test
  fun `review_cap_reached is rejected when pass two carries no blocker finding`() {
    val state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "f".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    )

    assertFailsWith<IllegalArgumentException> {
      state.copy(
        completedPassCount = GOAL_SUBTASK_REVIEW_MAX_PASSES,
        disposition = GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED,
        passResults = (1..GOAL_SUBTASK_REVIEW_MAX_PASSES).map { passNumber ->
          GoalSubtaskReviewPassResult(
            passNumber = passNumber,
            verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
            reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber",
            unresolvedFindingCount = 1,
            findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
          )
        },
      )
    }
  }

  @Test
  fun `a user-directed review skip is durable and prevents later review reservation`() {
    val skipped = GoalSubtaskReviewState.initial(
      reviewBaseSha = "d".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    )

    assertTrue(skipped.reviewSkippedByUser)
    assertEquals(skipped, skipped.reserveNextPass())
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
        state.toArtifactMap() + (
          "pass_results" to listOf(
            mapOf(
              "pass_number" to 1,
              "verdict" to "arbitrary",
              "review_result_artifact" to "goal_subtask_review_results.1",
              "unresolved_finding_count" to 0,
              "findings" to emptyList<Any>(),
            ),
          )
          ) + ("completed_pass_count" to 1),
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
