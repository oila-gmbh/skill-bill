package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.REPAIR_RECEIPT_MAX_TEXT_UTF8_BYTES
import skillbill.workflow.taskruntime.model.omittedCarriedFindings
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
                "finding_id" to "F-001",
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
                "finding_id" to "F-001",
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
    val edited = GoalSubtaskReviewCompactFinding("blocker", "Type", "unsafe mutation at the seam", "F-001")
    val leftover = GoalSubtaskReviewCompactFinding("major", "Policy", "stale comment is already gone", "F-002")
    val receipt = receiptFor(
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = edited.severity,
        label = edited.label,
        text = edited.text,
        outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
        constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Type.member")),
        intent = "close the finding at Type.member",
        findingId = edited.findingId!!,
      ),
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = leftover.severity,
        label = leftover.label,
        text = leftover.text,
        outcome = FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
        constructs = emptyList(),
        intent = "no tree change required",
        findingId = leftover.findingId!!,
        noEditReason = "construct already matched the finding",
      ),
    )
    assertTrue(receipt.omittedCarriedFindings(listOf(edited, leftover)).isEmpty())
  }

  @Test
  fun `a receipt that omits a carried finding names it for the next attempt without echoing it`() {
    val edited = GoalSubtaskReviewCompactFinding("blocker", "Type", "unsafe mutation at the seam", "F-001")
    val leftover = GoalSubtaskReviewCompactFinding("major", "Policy", "stale comment is already gone", "F-002")
    val receipt = receiptFor(
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = edited.severity,
        label = edited.label,
        text = edited.text,
        outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
        constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Type.member")),
        intent = "close the finding at Type.member",
        findingId = edited.findingId!!,
      ),
    )
    val omitted = receipt.omittedCarriedFindings(listOf(edited, leftover))

    assertEquals(listOf(leftover), omitted)
    val reason = featureTaskRuntimeOmittedFindingsRetryReason(omitted)
    assertTrue(reason.contains("attempted_unresolved"))
    assertTrue(!reason.contains(leftover.text))
  }

  @Test
  fun `an attempted_unresolved entry carries the finding ref and the producer's own account`() {
    val carried = GoalSubtaskReviewCompactFinding("major", "Policy", "the gate still admits an empty set", "F-002")
    val receipt = receiptFor(
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = carried.severity,
        label = carried.label,
        text = carried.text,
        outcome = FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED,
        constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Policy.gate")),
        intent = "reject an empty disposition set at the gate",
        findingId = requireNotNull(carried.findingId),
        unresolvedReason = "the gate has no access to the review pass ids it would have to compare",
      ),
    )

    assertTrue(receipt.omittedCarriedFindings(listOf(carried)).isEmpty())
    val unresolved = assertNotNull(featureTaskRuntimeUnresolvedFindings(receipt))
    assertEquals(setOf("F-002"), unresolved.refs)
    assertTrue(unresolved.detail.contains("the gate has no access to the review pass ids"))
    assertTrue(unresolved.retryReason.contains("one more attempt"))
  }

  @Test
  fun `a receipt with no attempted_unresolved entry owes nothing`() {
    val receipt = receiptFor(
      FeatureTaskRuntimeRepairReceiptEntry(
        severity = "major",
        label = "Policy",
        text = "the gate still admits an empty set",
        outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
        constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Policy.gate")),
        intent = "reject an empty disposition set at the gate",
        findingId = "F-002",
      ),
    )

    assertNull(featureTaskRuntimeUnresolvedFindings(receipt))
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
    assertTrue(receipt.omittedCarriedFindings(listOf(locationBearing)).isEmpty())
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
