package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDerivationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.REPAIR_RECEIPT_MAX_TEXT_UTF8_BYTES
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeDemotedPayloadShapePolicingTest {
  @Test
  fun `audit without verdict stays indecisive instead of advancing`() {
    val envelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "status" to "completed",
      "summary" to "audit finished",
      "produced_outputs" to mapOf("gaps" to emptyList<Any>()),
    )
    assertNull(
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        envelope,
      ),
    )
    assertIs<FeatureTaskRuntimeDerivationResult.Indecisive>(
      FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(
        FeatureTaskRuntimeDerivationContext(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          outputText = """{"status":"completed","summary":"audit finished"}""",
          outputMap = envelope,
        ),
      ),
    )
  }

  @Test
  fun `verify_findings without finding_dispositions stays indecisive instead of advancing`() {
    val envelope = mapOf(
      "phase_id" to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      "status" to "completed",
      "summary" to "verification finished",
      "produced_outputs" to emptyMap<String, Any?>(),
    )
    assertNull(
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        envelope,
      ),
    )
    assertIs<FeatureTaskRuntimeDerivationResult.Indecisive>(
      FeatureTaskRuntimePhaseOutputDerivation.deriveRoutingVerdict(
        FeatureTaskRuntimeDerivationContext(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
          outputText = """{"status":"completed","summary":"verification finished"}""",
          outputMap = envelope,
          reviewFindingIds = setOf("F-001"),
        ),
      ),
    )
  }

  @Test
  fun `audit satisfied without gaps array still fails the load-bearing routing gate`() {
    val envelope = mapOf(
      "verdict" to "satisfied",
      "produced_outputs" to mapOf("evidence" to "complete"),
    )
    assertNotNull(FeatureTaskRuntimeOutputVerification.auditGapPayloadError(envelope))
    assertNull(
      FeatureTaskRuntimeOutputVerification.verdictFor(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        envelope,
      ),
    )
  }

  @Test
  fun `repair receipt over-length entry text parses as rejected not missing`() {
    val overLength = "x".repeat(REPAIR_RECEIPT_MAX_TEXT_UTF8_BYTES + 1)
    val parsed = featureTaskRuntimeParseRepairReceipt(
      mapOf(
        "repair_receipt" to mapOf(
          "contract_version" to "0.1",
          "entries" to listOf(
            mapOf(
              "finding_id" to "F-001",
              "severity" to "blocker",
              "label" to "Foo",
              "text" to overLength,
              "outcome" to "addressed",
              "constructs" to listOf(mapOf("symbol" to "Foo.member")),
              "intent" to "close the finding",
            ),
          ),
        ),
      ),
      remediationBaseSha = "a".repeat(40),
      roundNumber = 1,
    )
    val rejected = assertIs<FeatureTaskRuntimeRepairReceiptRejected>(parsed)
    assertContains(rejected.rejectionDetail, "/repair_receipt/entries/0/text")
  }

  @Test
  fun `repair receipt path-shaped symbol parses as rejected not missing`() {
    val parsed = featureTaskRuntimeParseRepairReceipt(
      mapOf(
        "repair_receipt" to mapOf(
          "contract_version" to "0.1",
          "entries" to listOf(
            mapOf(
              "finding_id" to "F-001",
              "severity" to "blocker",
              "label" to "Foo",
              "text" to "unsafe mutation at the seam",
              "outcome" to "addressed",
              "constructs" to listOf(mapOf("symbol" to "/src/Foo.kt")),
              "intent" to "close the finding",
            ),
          ),
        ),
      ),
      remediationBaseSha = "a".repeat(40),
      roundNumber = 1,
    )
    val rejected = assertIs<FeatureTaskRuntimeRepairReceiptRejected>(parsed)
    assertContains(rejected.rejectionDetail, "constructs")
  }

  @Test
  fun `repair receipt finding_ref alias parses as valid not rejected`() {
    val parsed = featureTaskRuntimeParseRepairReceipt(
      mapOf(
        "repair_receipt" to mapOf(
          "contract_version" to "0.1",
          "entries" to listOf(
            mapOf(
              "finding_ref" to "F-001",
              "severity" to "blocker",
              "label" to "Foo",
              "text" to "unsafe mutation at the seam",
              "outcome" to "addressed",
              "constructs" to listOf(mapOf("symbol" to "Foo.member")),
              "intent" to "close the finding",
            ),
          ),
        ),
      ),
      remediationBaseSha = "a".repeat(40),
      roundNumber = 1,
    )
    assertIs<FeatureTaskRuntimeRepairReceiptValid>(parsed)
  }

  @Test
  fun `repair receipt covering none of three carried findings names coverage shortfall`() {
    val state = reviewStateCarrying(
      finding("F-001"),
      finding("F-002"),
      finding("F-003"),
    )
    val receipt = receiptFor(finding("F-001"))
    val rejection = assertNotNull(featureTaskRuntimeRepairReceiptSettleRejection(receipt, state))
    assertContains(rejection, "/repair_receipt/entries")
    assertTrue(rejection.contains("every finding carried into this round"))
  }

  private fun finding(id: String) = GoalSubtaskReviewCompactFinding(
    severity = "blocker",
    label = "Foo",
    text = "finding $id",
    findingId = id,
  )

  private fun receiptFor(vararg findings: GoalSubtaskReviewCompactFinding) = FeatureTaskRuntimeRepairReceipt(
    roundNumber = 1,
    preFixCheckpointSha = "b".repeat(40),
    entries = findings.map { carried ->
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = carried.severity,
        label = carried.label,
        text = carried.text,
        outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
        constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Foo.member")),
        intent = "close the finding",
        findingId = requireNotNull(carried.findingId),
      )
    },
  )

  private fun reviewStateCarrying(vararg findings: GoalSubtaskReviewCompactFinding) = GoalSubtaskReviewState(
    reviewBaseSha = "b".repeat(40),
    baselineUntrackedPaths = emptyList(),
    codeReviewMode = skillbill.workflow.model.CodeReviewExecutionMode.INLINE,
    completedPassCount = 1,
    remediationBaseSha = "b".repeat(40),
    passResults = listOf(
      GoalSubtaskReviewPassResult(
        passNumber = 1,
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        reviewResultArtifact = "goal_subtask_review_results.1",
        unresolvedFindingCount = findings.size,
        findings = findings.toList(),
        reviewRunId = "review-run-1",
      ),
    ),
  )
}
