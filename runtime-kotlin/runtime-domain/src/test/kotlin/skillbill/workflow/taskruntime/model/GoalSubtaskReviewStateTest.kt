package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalSubtaskReviewStateTest {
  @Test
  fun `completed passes record immutable execution modes while legacy records remain byte stable`() {
    val initial = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
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
    assertEquals(CodeReviewExecutionMode.INLINE, completed.passResults.single().executedMode)
    val recordedPass = (completed.toArtifactMap()["pass_results"] as List<*>).single() as Map<*, *>
    assertEquals("inline", recordedPass["executed_mode"])

    val legacyCompletedArtifact = completed.toArtifactMap().toMutableMap().apply {
      put(
        "pass_results",
        (getValue("pass_results") as List<*>).map { rawPass ->
          (rawPass as Map<*, *>).filterKeys { it != "executed_mode" }
        },
      )
    }
    assertEquals(
      legacyCompletedArtifact,
      GoalSubtaskReviewState.fromArtifactMap(legacyCompletedArtifact).toArtifactMap(),
    )
  }

  @Test
  fun `a recorded mode outside the immutable sequence fails loudly`() {
    val initial = GoalSubtaskReviewState.initial(
      reviewBaseSha = "b".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
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
              "executed_mode" to "delegated",
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
  fun `unresolved blockers keep reserving the next pass with contiguous watermarks`() {
    var state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "a".repeat(40),
      baselineUntrackedPaths = listOf("preexisting.tmp"),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )

    (1..10).forEach { passNumber ->
      val reserved = state.reserveNextPass()
      assertEquals(passNumber, reserved.reservedPassNumber, "Pass $passNumber must be reservable.")
      assertEquals(passNumber - 1, reserved.completedPassCount)
      state = reserved.completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository$passNumber", "Unsafe mutation")),
      )
      assertEquals(passNumber, state.completedPassCount)
      assertFalse(state.reviewCapReached, "No pass count may mint a cap disposition.")
      assertFalse(state.pausedForOperatorDecision, "No pass count may mint a pause.")
      assertEquals(
        (1..passNumber).toList(),
        state.passResults.map(GoalSubtaskReviewPassResult::passNumber),
        "Pass results must stay ordered and contiguous.",
      )
    }
  }

  @Test
  fun `the first blocker-free pass settles regardless of how many passes preceded it`() {
    var state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "9".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )
    repeat(6) {
      state = state.reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = emptyList(),
      )
    }

    val settled = state.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    )

    assertEquals(7, settled.completedPassCount)
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, settled.passResults.last().verdict)
    assertFalse(settled.reviewCapReached)
    assertFalse(settled.pausedForOperatorDecision)
  }

  @Test
  fun `arbitrary positive pass numbers survive serialization and resume`() {
    var state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "7".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )
    repeat(12) {
      state = state.reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = emptyList(),
      )
    }
    val reservedThirteenth = state.acknowledgeSummariesThrough(12).reserveNextPass()

    val decoded = GoalSubtaskReviewState.fromArtifactMap(reservedThirteenth.toArtifactMap())

    assertEquals(reservedThirteenth, decoded)
    assertEquals(13, decoded.reservedPassNumber)
    assertEquals(12, decoded.completedPassCount)
    assertEquals(12, decoded.emittedPassCount)
    assertEquals((1..12).toList(), decoded.passResults.map(GoalSubtaskReviewPassResult::passNumber))
  }

  @Test
  fun `a non-positive pass number is rejected`() {
    listOf(0, -1).forEach { passNumber ->
      assertFailsWith<IllegalArgumentException> {
        GoalSubtaskReviewPassResult(
          passNumber = passNumber,
          verdict = FeatureTaskRuntimeVerdict.APPROVED,
          reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber",
          unresolvedFindingCount = 0,
          findings = emptyList(),
        )
      }
    }
  }

  @Test
  fun `a later pass with only major findings can carry the legacy cap disposition`() {
    val firstPass = GoalSubtaskReviewState.initial(
      reviewBaseSha = "e".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Unsafe mutation")),
    )

    val secondPass = firstPass.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
    ).copy(disposition = GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED)

    assertTrue(secondPass.reviewCapReached)
    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, secondPass.passResults.last().verdict)
  }

  @Test
  fun `review_cap_reached is rejected when the last completed pass carries no Blocker or Major`() {
    val state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "f".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )

    assertFailsWith<IllegalArgumentException> {
      state.copy(
        completedPassCount = 2,
        disposition = GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED,
        passResults = (1..2).map { passNumber ->
          GoalSubtaskReviewPassResult(
            passNumber = passNumber,
            verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
            reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber",
            unresolvedFindingCount = 1,
            findings = listOf(GoalSubtaskReviewCompactFinding("minor", "Service", "Naming polish")),
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
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to mapOf("1" to "stale raw review result"),
        ),
      )
    }
  }

  @Test
  fun `review invalidation clears raw results through an empty map that stays decodable`() {
    val continuation = FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = "SKILL-135",
      subtaskId = 3,
      suppressPr = true,
      goalBranch = "feat/SKILL-135",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )
    val completed = GoalSubtaskReviewState.initial(
      reviewBaseSha = "d".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    )
    val invalidated = GoalSubtaskReviewState.initial(
      reviewBaseSha = completed.reviewBaseSha,
      baselineUntrackedPaths = completed.baselineUntrackedPaths,
      codeReviewMode = completed.codeReviewMode,
    )

    val artifacts = mapOf(
      FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap(),
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to invalidated.toArtifactMap(),
      GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to emptyMap<String, String>(),
    )

    val decoded = assertNotNull(GoalSubtaskReviewArtifactDecoder.decode(artifacts))
    assertEquals(0, decoded.state.completedPassCount)
    assertEquals(emptyMap(), decoded.rawResults)
  }

  @Test
  fun `Major-only itemised findings with a positive unresolved count block advance`() {
    val pass = GoalSubtaskReviewPassResult(
      passNumber = 1,
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.1",
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
    )
    assertTrue(pass.blocksAdvance)
    assertFalse(pass.findings.single().isBlocker)
    assertTrue(pass.findings.single().blocksAdvance)
  }

  @Test
  fun `Minor-only itemised findings do not block advance`() {
    val pass = GoalSubtaskReviewPassResult(
      passNumber = 1,
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.1",
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("minor", "Naming", "Prefer clearer name")),
    )
    assertFalse(pass.blocksAdvance)
    assertFalse(pass.findings.single().blocksAdvance)
  }

  @Test
  fun `positive unresolvedFindingCount with empty findings remains blocking`() {
    val pass = GoalSubtaskReviewPassResult(
      passNumber = 1,
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.1",
      unresolvedFindingCount = 2,
      findings = emptyList(),
    )
    assertTrue(pass.blocksAdvance)
  }

  @Test
  fun `toArtifactMap and fromArtifactMap preserve durable key set including blocker_dispositions`() {
    val state = GoalSubtaskReviewState.initial(
      reviewBaseSha = "e".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Unsafe mutation")),
      blockerDispositions = listOf(
        GoalSubtaskBlockerDisposition(
          findingId = "F-001",
          verdict = GoalSubtaskBlockerDispositionVerdict.UNRESOLVED,
          evidence = listOf("still open"),
        ),
      ),
    )
    val encoded = state.toArtifactMap()
    assertTrue("blocker_dispositions" in encoded)
    assertTrue(
      (encoded["pass_results"] as List<*>).all { pass ->
        "unresolved_finding_count" in (pass as Map<*, *>)
      },
    )
    val roundTripped = GoalSubtaskReviewState.fromArtifactMap(encoded).toArtifactMap()
    assertEquals(encoded.keys, roundTripped.keys)
    assertEquals(encoded, roundTripped)
  }
}
