package skillbill.application.featuretask

import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.goal.model.GoalSubtaskReviewPassResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A round that closed every finding verification let through must settle. Before this, coverage
 * measured against the raw review pass, so the stage that exists to drop unsound findings dropped
 * one and the next gate blocked the round for not filing paperwork on it — with both real findings
 * already fixed on the tree.
 */
class FeatureTaskRuntimeRefutedFindingCoverageTest {
  private val sha = "d".repeat(40)

  private val surviving = GoalSubtaskReviewCompactFinding(
    severity = "minor",
    label = "CapmoProjectCatalogRepository",
    text = "inlines the row-to-domain conversion the mapper already owns",
    findingId = "F-001",
  )
  private val alsoSurviving = GoalSubtaskReviewCompactFinding(
    severity = "minor",
    label = "CapmoWeatherForecastRepositoryAutoWeatherTest",
    text = "the test passes whatever operation the transport is handed",
    findingId = "F-002",
  )
  private val refuted = GoalSubtaskReviewCompactFinding(
    severity = "nit",
    label = "WeatherForecastQuery",
    text = "the hourly selection is never read by the auto path",
    findingId = "F-003",
  )

  @Test
  fun `a round that closed every surviving finding settles when verification refuted the rest`() {
    val state = reviewStateCarrying(surviving, alsoSurviving, refuted)
    val receipt = receiptAddressing(surviving, alsoSurviving)

    assertNull(featureTaskRuntimeRepairReceiptSettleRejection(receipt, state, refutedFindingIds = setOf("F-003")))
  }

  @Test
  fun `the same round is still rejected while nothing records the refutation`() {
    val state = reviewStateCarrying(surviving, alsoSurviving, refuted)
    val receipt = receiptAddressing(surviving, alsoSurviving)

    val rejection = assertNotNull(featureTaskRuntimeRepairReceiptSettleRejection(receipt, state))
    assertTrue(rejection.contains("/repair_receipt/entries"))
  }

  @Test
  fun `a refutation waives only the finding it names`() {
    val state = reviewStateCarrying(surviving, alsoSurviving, refuted)
    val receipt = receiptAddressing(surviving)

    assertNotNull(
      featureTaskRuntimeRepairReceiptSettleRejection(receipt, state, refutedFindingIds = setOf("F-003")),
    )
    assertEquals(
      listOf("F-002"),
      featureTaskRuntimeRepairReceiptOmittedFindings(receipt, state, setOf("F-003"))
        .map { finding -> finding.findingId },
    )
  }

  @Test
  fun `a refuted finding is never named in the reason the next attempt is sent`() {
    val state = reviewStateCarrying(surviving, alsoSurviving, refuted)
    val omitted = featureTaskRuntimeRepairReceiptOmittedFindings(
      receiptAddressing(surviving),
      state,
      setOf("F-003"),
    )

    val reason = featureTaskRuntimeOmittedFindingsRetryReason(omitted)
    assertTrue(reason.contains("F-002"))
    assertTrue(!reason.contains("F-003"))
  }

  @Test
  fun `a refutation naming a finding the pass never carried waives nothing`() {
    val state = reviewStateCarrying(surviving, alsoSurviving)
    val receipt = receiptAddressing(surviving)

    assertEquals(
      listOf("F-002"),
      featureTaskRuntimeRepairReceiptOmittedFindings(receipt, state, setOf("F-009"))
        .map { finding -> finding.findingId },
    )
  }

  /**
   * Review may omit the ref entirely. Coverage stabilizes refs before it measures, so such a finding
   * is still owed an entry rather than passing silently on an identity it never had.
   */
  @Test
  fun `a carried finding whose review omitted its ref is still owed an entry`() {
    val unnamed = GoalSubtaskReviewCompactFinding(
      severity = "major",
      label = "Policy",
      text = "the review output carried no finding id",
    )
    val state = reviewStateCarrying(surviving, unnamed)
    val receipt = receiptAddressing(surviving)

    assertNotNull(featureTaskRuntimeRepairReceiptSettleRejection(receipt, state))
    // Stabilization hands the unnamed finding the first ref no carried finding already holds.
    assertEquals(
      listOf("F-002"),
      featureTaskRuntimeRepairReceiptOmittedFindings(receipt, state).map { finding -> finding.findingId },
    )
  }

  private fun receiptAddressing(vararg findings: GoalSubtaskReviewCompactFinding) = FeatureTaskRuntimeRepairReceipt(
    roundNumber = 1,
    preFixCheckpointSha = sha,
    entries = findings.map { finding ->
      FeatureTaskRuntimeRepairReceiptEntry(
        outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
        findingId = requireNotNull(finding.findingId),
      )
    },
  )

  private fun reviewStateCarrying(vararg findings: GoalSubtaskReviewCompactFinding) = GoalSubtaskReviewState(
    reviewBaseSha = sha,
    baselineUntrackedPaths = emptyList(),
    codeReviewMode = CodeReviewExecutionMode.INLINE,
    completedPassCount = 1,
    remediationBaseSha = sha,
    passResults = listOf(
      GoalSubtaskReviewPassResult(
        passNumber = 1,
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        reviewResultArtifact = "goal_subtask_review_results.1",
        unresolvedFindingCount = findings.size,
        findings = findings.toList(),
      ),
    ),
  )
}
