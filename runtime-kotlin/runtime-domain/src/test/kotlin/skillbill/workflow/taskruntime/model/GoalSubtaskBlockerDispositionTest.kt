package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-142 AC-010 to AC-014 and AC-017, carried to the SKILL-157 unbounded loop: the evidenced
 * per-Blocker disposition, never a pass count, decides whether remediation continues.
 */
class GoalSubtaskBlockerDispositionTest {
  private fun reservedPassTwo(): GoalSubtaskReviewState = GoalSubtaskReviewState.initial(
    reviewBaseSha = "b".repeat(40),
    baselineUntrackedPaths = emptyList(),
    codeReviewMode = CodeReviewExecutionMode.AUTO,
  ).reserveNextPass().completeReservedPass(
    verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
    unresolvedFindingCount = 1,
    findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Unsafe mutation")),
  ).reserveNextPass()

  private fun disposition(id: String, verdict: GoalSubtaskBlockerDispositionVerdict) =
    GoalSubtaskBlockerDisposition(id, verdict, listOf("changed line settling $id"))

  @Test
  fun `every blocker resolved or superseded advances the child`() {
    val settled = reservedPassTwo().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
      blockerDispositions = listOf(
        disposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED),
        disposition("F-002", GoalSubtaskReviewDispositionFixtures.SUPERSEDED),
      ),
    )
    assertFalse(settled.pausedForOperatorDecision)
    assertFalse(settled.reviewCapReached)
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, settled.passResults.last().verdict)
  }

  @Test
  fun `an unresolved blocker reserves the next remediation pass instead of pausing or blocking`() {
    val unresolved = reservedPassTwo().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Still unsafe")),
      blockerDispositions = listOf(
        disposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED),
        disposition("F-002", GoalSubtaskReviewDispositionFixtures.UNRESOLVED),
      ),
    )
    assertFalse(unresolved.pausedForOperatorDecision, "The unbounded loop remediates instead of pausing itself.")
    assertFalse(unresolved.reviewCapReached, "No pass count exhausts the remediation loop.")
    assertEquals(1, unresolved.unresolvedBlockerDispositions.size)
    assertEquals(2, unresolved.completedPassCount)
    assertEquals(3, unresolved.reserveNextPass().reservedPassNumber)
    assertTrue(
      unresolved.acceptsOperatorDecision,
      "An operator may still take over a subtask carrying an unresolved Blocker.",
    )
  }

  @Test
  fun `dispositions and their evidence survive encode and decode`() {
    val paused = reservedPassTwo().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Still unsafe")),
      blockerDispositions = listOf(disposition("F-002", GoalSubtaskReviewDispositionFixtures.UNRESOLVED)),
    )
    val reloaded = GoalSubtaskReviewState.fromArtifactMap(paused.toArtifactMap())
    assertEquals(paused.blockerDispositions, reloaded.blockerDispositions)
    assertEquals(paused.toArtifactMap(), reloaded.toArtifactMap())
    assertEquals(2, reloaded.completedPassCount, "Resume must never re-reserve a consumed pass.")
    assertEquals(paused.reviewBaseSha, reloaded.reviewBaseSha)
    assertEquals(paused.baselineUntrackedPaths, reloaded.baselineUntrackedPaths)
  }

  @Test
  fun `an unevidenced disposition is rejected at the parse seam`() {
    assertFailsWith<IllegalArgumentException> {
      GoalSubtaskBlockerDisposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED, emptyList())
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskBlockerDisposition.fromArtifactMap(
        mapOf("finding_id" to "F-001", "verdict" to "resolved"),
        "blocker_dispositions[0]",
      )
    }
  }

  @Test
  fun `major findings stay out of disposition scope`() {
    val settled = reservedPassTwo().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
      blockerDispositions = listOf(disposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED)),
    )
    assertEquals(listOf("F-001"), settled.blockerDispositions.map { it.findingId })
    assertTrue(settled.passResults.last().findings.any { it.severity == "major" })
    assertFalse(settled.passResults.last().blocksAdvance, "blocksAdvance stays Blocker-only.")
  }

  @Test
  fun `the review result artifact still equals the prefix plus its exact pass number`() {
    val settled = reservedPassTwo().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
      blockerDispositions = listOf(disposition("F-001", GoalSubtaskReviewDispositionFixtures.RESOLVED)),
    )
    assertEquals(
      "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.2",
      settled.passResults.last().reviewResultArtifact,
    )
    assertEquals(2, settled.completedPassCount)
  }

  @Test
  fun `the operator decision vocabulary is bounded and only applies while paused`() {
    val paused = reservedPassTwo().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Still unsafe")),
      blockerDispositions = listOf(disposition("F-002", GoalSubtaskReviewDispositionFixtures.UNRESOLVED)),
    )
    GoalSubtaskOperatorDecision.entries.forEach { decision ->
      val decided = paused.applyOperatorDecision(decision)
      assertEquals(decision, decided.operatorDecision)
      assertEquals(2, decided.completedPassCount, "retry_fix is a disposition round, never a new pass.")
      assertEquals(null, decided.reservedPassNumber)
    }
    assertEquals(
      setOf("retry_fix", "accept_and_advance", "abandon_subtask"),
      GoalSubtaskOperatorDecision.entries.map { it.wireValue }.toSet(),
    )
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      GoalSubtaskOperatorDecision.fromWire("skip_review")
    }
  }

  @Test
  fun `the bounded disposition summary carries no location bearing evidence`() {
    val paused = reservedPassTwo().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(GoalSubtaskReviewCompactFinding("blocker", "Repository", "Still unsafe")),
      blockerDispositions = listOf(
        GoalSubtaskBlockerDisposition(
          "F-002",
          GoalSubtaskReviewDispositionFixtures.UNRESOLVED,
          listOf("src/main/kotlin/skillbill/Repo.kt:42 still mutates in place"),
        ),
      ),
    )
    val summary = paused.boundedDispositionSummary().toString()
    assertFalse(summary.contains("Repo.kt"), "No path may reach a goal-facing surface.")
    assertFalse(summary.contains(":42"), "No line number may reach a goal-facing surface.")
    assertTrue(summary.contains("unresolved"))
    assertTrue(
      paused.toArtifactMap().toString().contains("Repo.kt"),
      "Location-bearing evidence stays in the durable artifact for goal findings retrieval.",
    )
  }
}

private object GoalSubtaskReviewDispositionFixtures {
  val RESOLVED = GoalSubtaskBlockerDispositionVerdict.RESOLVED
  val UNRESOLVED = GoalSubtaskBlockerDispositionVerdict.UNRESOLVED
  val SUPERSEDED = GoalSubtaskBlockerDispositionVerdict.SUPERSEDED
}
