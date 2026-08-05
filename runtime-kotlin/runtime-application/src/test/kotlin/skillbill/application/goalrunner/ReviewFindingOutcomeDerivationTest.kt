package skillbill.application.goalrunner

import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-136 subtask 6 AC-004. Coverage must come from the fix loop, not from optional manual triage
 * — that dependence is what left only 13% of runs with any recorded outcome.
 */
class ReviewFindingOutcomeDerivationTest {
  @Test
  fun `every finding a pass produces receives an outcome without any manual triage`() {
    val findings = List(3) { index -> finding(ordinal = index + 1, findingId = "F-00${index + 1}") }

    val outcomes = GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
      supersededFindings = emptyList(),
      currentFindings = findings,
      blockerDispositions = emptyList(),
    )

    assertEquals(3, outcomes.size, "Every finding the run produced must get an outcome row.")
    assertTrue(
      outcomes.all { it.outcome == ReviewFindingOutcome.CARRIED },
      "A finding the current pass still reports is carried until a later pass retires it.",
    )
    assertEquals(listOf(1, 2, 3), outcomes.map { it.findingOrdinal })
  }

  @Test
  fun `a run producing zero findings records nothing and does not error`() {
    val outcomes = GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
      supersededFindings = emptyList(),
      currentFindings = emptyList(),
      blockerDispositions = emptyList(),
    )

    assertEquals(emptyList(), outcomes)
  }

  @Test
  fun `a finding an earlier pass reported and this pass does not is recorded as addressed`() {
    val addressed = finding(ordinal = 1, findingId = "F-001")
    val stillOpen = finding(ordinal = 2, findingId = "F-002")

    val outcomes = GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
      supersededFindings = listOf(addressed, stillOpen),
      currentFindings = listOf(stillOpen.copy(reviewPassNumber = 2, findingOrdinal = 1)),
      blockerDispositions = emptyList(),
    )

    assertEquals(
      ReviewFindingOutcome.ADDRESSED,
      outcomes.single { it.findingId == "F-001" }.outcome,
      "The fix loop retired F-001, so it was addressed.",
    )
    assertEquals(
      listOf(ReviewFindingOutcome.CARRIED),
      outcomes.filter { it.findingId == "F-002" }.map { it.outcome },
      "F-002 survives into this pass, so it stays carried and is not double-recorded.",
    )
  }

  @Test
  fun `an explicit blocker disposition overrides the cross-pass inference`() {
    val superseded = finding(ordinal = 1, findingId = "F-001")

    val outcomes = GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
      supersededFindings = listOf(superseded),
      currentFindings = emptyList(),
      blockerDispositions = listOf(
        GoalSubtaskBlockerDisposition(
          findingId = "F-001",
          verdict = GoalSubtaskBlockerDispositionVerdict.SUPERSEDED,
          evidence = listOf("src/First.kt:7 no longer exists"),
        ),
      ),
    )

    assertEquals(
      ReviewFindingOutcome.REJECTED,
      outcomes.single().outcome,
      "An explicitly superseded Blocker is rejected, not silently counted as addressed.",
    )
  }

  @Test
  fun `a finding without an id is never declared addressed on no evidence`() {
    val anonymous = finding(ordinal = 1, findingId = null)

    val outcomes = GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
      supersededFindings = listOf(anonymous),
      currentFindings = emptyList(),
      blockerDispositions = emptyList(),
    )

    assertEquals(
      emptyList(),
      outcomes,
      "An unmatched anonymous finding keeps the carried outcome its own pass already recorded.",
    )
  }

  @Test
  fun `an unresolvable key is retained as unresolved rather than guessed`() {
    val outcomes = GoalSubtaskReviewSummaryReducer.reviewFindingOutcomes(
      supersededFindings = emptyList(),
      currentFindings = listOf(finding(ordinal = 1, findingId = "F-001")),
      blockerDispositions = emptyList(),
    )

    val outcome = outcomes.single()
    assertEquals(null, outcome.reviewRunId, "The workflow loop imports no review run, so there is no key to record.")
    assertEquals("unresolved", outcome.keyState)
  }

  private fun finding(ordinal: Int, findingId: String?) = UnaddressedFinding(
    issueKey = "SKILL-136",
    subtaskId = 6,
    workflowId = "wf-1",
    reviewPassNumber = 1,
    findingOrdinal = ordinal,
    severity = "major",
    issueCategory = "data_persistence",
    location = "src/File.kt:$ordinal",
    summary = "Finding $ordinal",
    findingId = findingId,
  )
}
