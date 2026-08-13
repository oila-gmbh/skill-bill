package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeRepairReceiptParserTest {
  private val sha = "b".repeat(40)
  private val otherSha = "c".repeat(40)

  @Test
  fun `a receipt whose checkpoint sha does not match the durable remediation base is rejected payload-free`() {
    val receipt = receiptFor(
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = "blocker",
        label = "Type",
        text = "unsafe mutation at the seam",
        outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
        constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Type.member")),
        intent = "close the finding at Type.member",
      ),
    )
    val reason = assertNotNull(featureTaskRuntimeRepairReceiptAnchorRejection(receipt, otherSha))
    assertTrue(reason.contains("pre_fix_checkpoint_sha"))
    assertTrue(!reason.contains(sha) && !reason.contains(otherSha), "anchor rejection must not echo shas")
    assertTrue(!reason.contains("Type.member"))
    assertTrue(!reason.contains("@@") && !reason.contains("diff --git"))
  }

  @Test
  fun `a round that edits one finding and records no_edit_required for the other is accepted`() {
    val edited = GoalSubtaskReviewCompactFinding("blocker", "Type", "unsafe mutation at the seam")
    val leftover = GoalSubtaskReviewCompactFinding("major", "Policy", "stale comment is already gone")
    val receipt = receiptFor(
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = edited.severity,
        label = edited.label,
        text = edited.text,
        outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
        constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Type.member")),
        intent = "close the finding at Type.member",
      ),
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = leftover.severity,
        label = leftover.label,
        text = leftover.text,
        outcome = FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
        constructs = emptyList(),
        intent = "no tree change required",
        noEditReason = "construct already matched the finding",
      ),
    )
    assertNull(featureTaskRuntimeRepairReceiptCoverageRejection(receipt, listOf(edited, leftover)))
  }

  @Test
  fun `a receipt that omits a carried finding is rejected`() {
    val edited = GoalSubtaskReviewCompactFinding("blocker", "Type", "unsafe mutation at the seam")
    val leftover = GoalSubtaskReviewCompactFinding("major", "Policy", "stale comment is already gone")
    val receipt = receiptFor(
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = edited.severity,
        label = edited.label,
        text = edited.text,
        outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
        constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Type.member")),
        intent = "close the finding at Type.member",
      ),
    )
    val reason = assertNotNull(featureTaskRuntimeRepairReceiptCoverageRejection(receipt, listOf(edited, leftover)))
    assertTrue(reason.contains("no_edit_required"))
    assertTrue(!reason.contains(leftover.text))
    assertTrue(!reason.contains(leftover.label))
  }

  @Test
  fun `parse of an absent receipt key is a no-op`() {
    assertNull(featureTaskRuntimeParseRepairReceiptOrNull(emptyMap()))
  }

  @Test
  fun `parse of a malformed receipt object throws a payload-free typed error`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      featureTaskRuntimeParseRepairReceiptOrNull(mapOf("repair_receipt" to "not-an-object"))
    }
    assertEquals("repair_receipt must be an object.", error.payloadFreeReason)
  }

  private fun receiptFor(vararg entries: FeatureTaskRuntimeRepairReceiptEntry) = FeatureTaskRuntimeRepairReceipt(
    roundNumber = 1,
    preFixCheckpointSha = sha,
    entries = entries.toList(),
  )
}
