package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.REPAIR_RECEIPT_MAX_TEXT_UTF8_BYTES
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
  fun `the runtime stamps the remediation base and round over whatever the producer sent`() {
    val parsed = assertNotNull(
      featureTaskRuntimeParseRepairReceiptOrNull(
        mapOf(
          "repair_receipt" to mapOf(
            "contract_version" to "0.1",
            "round_number" to 1,
            "pre_fix_checkpoint_sha" to otherSha,
            "entries" to listOf(
              mapOf(
                "severity" to "blocker",
                "label" to "Type",
                "text" to "unsafe mutation at the seam",
                "outcome" to "addressed",
                "constructs" to listOf(mapOf("symbol" to "Type.member")),
                "intent" to "close the finding at Type.member",
              ),
            ),
          ),
        ),
        remediationBaseSha = sha,
        roundNumber = 3,
      ),
    )
    assertEquals(sha, parsed.preFixCheckpointSha)
    assertEquals(3, parsed.roundNumber)
  }

  @Test
  fun `a receipt that omits the runtime-owned anchor fields is accepted`() {
    val parsed = assertNotNull(
      featureTaskRuntimeParseRepairReceiptOrNull(
        mapOf(
          "repair_receipt" to mapOf(
            "contract_version" to "0.1",
            "entries" to listOf(
              mapOf(
                "severity" to "blocker",
                "label" to "Type",
                "text" to "unsafe mutation at the seam",
                "outcome" to "addressed",
                "constructs" to listOf(mapOf("symbol" to "Type.member")),
                "intent" to "close the finding at Type.member",
              ),
            ),
          ),
        ),
        remediationBaseSha = sha,
        roundNumber = 2,
      ),
    )
    assertEquals(sha, parsed.preFixCheckpointSha)
    assertEquals(2, parsed.roundNumber)
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
  fun `a review finding that no receipt sanitizer would accept never fails the round`() {
    val locationBearing = GoalSubtaskReviewCompactFinding(
      severity = "blocker",
      label = "ReducerLabel",
      text = "the { emptyList() } scrape at Type.member -> Other.member is unenforceable, " +
        "and the compact text is over " + "x".repeat(REPAIR_RECEIPT_MAX_TEXT_UTF8_BYTES),
      findingId = "F-001",
    )
    val receipt = receiptFor(
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = "blocker",
        label = "TypeKt",
        text = "resolve the finding at the reported location",
        outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
        constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Type.member")),
        intent = "close the finding at Type.member",
        findingId = "F-001",
      ),
    )
    assertNull(featureTaskRuntimeRepairReceiptCoverageRejection(receipt, listOf(locationBearing)))
    val entry = receipt.entries.single()
    assertEquals("TypeKt", entry.label)
    assertEquals("resolve the finding at the reported location", entry.text)
  }

  @Test
  fun `parse of an absent receipt key is a no-op`() {
    assertNull(featureTaskRuntimeParseRepairReceiptOrNull(emptyMap(), sha, 1))
  }

  @Test
  fun `parse of a malformed receipt object throws a payload-free typed error`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      featureTaskRuntimeParseRepairReceiptOrNull(mapOf("repair_receipt" to "not-an-object"), sha, 1)
    }
    assertEquals("repair_receipt must be an object.", error.payloadFreeReason)
  }

  @Test
  fun `a rejection detail names the offending receipt field as a json pointer`() {
    val detail = featureTaskRuntimeRepairReceiptRejectionDetail("repair_receipt.entries[0].text", "must be one line.")
    assertEquals("[repair-receipt] /repair_receipt/entries/0/text: must be one line.", detail)
  }

  private fun receiptFor(vararg entries: FeatureTaskRuntimeRepairReceiptEntry) = FeatureTaskRuntimeRepairReceipt(
    roundNumber = 1,
    preFixCheckpointSha = sha,
    entries = entries.toList(),
  )
}
