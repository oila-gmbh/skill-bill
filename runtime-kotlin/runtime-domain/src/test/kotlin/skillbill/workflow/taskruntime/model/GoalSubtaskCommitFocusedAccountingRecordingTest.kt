package skillbill.workflow.taskruntime.model

import skillbill.workflow.model.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * AC-003: a delegated pass's commit-focused accounting reaches durable lifecycle state through the
 * only production constructor of a pass result, and an inline pass records none rather than
 * fabricating a commit sequence identity it never had.
 */
class GoalSubtaskCommitFocusedAccountingRecordingTest {
  private val accounting = GoalSubtaskCommitFocusedAccounting(
    commitSequenceDigest = "a".repeat(64),
    commitCount = 6,
    laneCount = 2,
    focusedCommitCount = 4,
    skippedCommitCount = 2,
    integrationTerminalOutcome = "completed",
    laneBundleSizes = mapOf("bill-kotlin-code-review-security" to 2048L),
    laneSegmentCounts = mapOf("bill-kotlin-code-review-security" to 2),
    incompleteLanes = listOf("bill-kotlin-code-review-testing"),
    parentAnalysisPairs = 12,
    parentAnalysisBytes = 4096,
    integrationFindingCount = 1,
  )

  private fun reservedFirstPass() = GoalSubtaskReviewState.initial(
    reviewBaseSha = "b".repeat(40),
    baselineUntrackedPaths = emptyList(),
    // Pinned delegated: under AUTO the first pass resolves to the inline lane, which by contract
    // carries no commit-focused accounting at all.
    codeReviewMode = CodeReviewExecutionMode.DELEGATED,
  ).reserveNextPass()

  @Test
  fun `a delegated pass records its commit-focused accounting and survives a durable round trip`() {
    val state = reservedFirstPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
      commitFocusedAccounting = accounting,
    )

    assertEquals(accounting, state.passResults.single().commitFocusedAccounting)
    val decoded = GoalSubtaskReviewState.fromArtifactMap(state.toArtifactMap())
    assertEquals(accounting, decoded.passResults.single().commitFocusedAccounting)
  }

  @Test
  fun `an inline review pass records no commit-focused accounting`() {
    val inlinePass = GoalSubtaskReviewState.initial(
      reviewBaseSha = "b".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
      commitFocusedAccounting = accounting,
    ).passResults.single()

    assertEquals(CodeReviewExecutionMode.INLINE, inlinePass.executedMode)
    assertNull(inlinePass.commitFocusedAccounting)
  }

  @Test
  fun `a skipped integration pass must name why it was not applicable`() {
    val skipped = accounting.copy(
      integrationTerminalOutcome = GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE,
      focusedCommitCount = 0,
      skippedCommitCount = 6,
      integrationSkipReason = "the commit sequence carries a single commit",
    )

    assertEquals(
      skipped,
      GoalSubtaskCommitFocusedAccounting.fromArtifactMap(skipped.toArtifactMap(), "accounting"),
    )
  }
}
