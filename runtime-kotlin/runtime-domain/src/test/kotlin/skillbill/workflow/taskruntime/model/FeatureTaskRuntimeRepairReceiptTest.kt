package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeRepairReceiptTest {
  private val sha = "a".repeat(40)

  @Test
  fun `a symbol and a file-plus-symbol construct are accepted while a repository path is rejected`() {
    FeatureTaskRuntimeRepairConstruct(symbol = "Type")
    FeatureTaskRuntimeRepairConstruct(symbol = "Type.member", file = "Type.kt")
    val pathOnly = assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      FeatureTaskRuntimeRepairConstruct(symbol = "runtime-kotlin/src/Type.kt")
    }
    assertTrue(pathOnly.payloadFreeReason.contains("never a repository path"))
    assertTrue(pathOnly.message.orEmpty().none { it == '/' }, "rejection must not echo the path")
  }

  @Test
  fun `construct identity normalizes case to one stable key`() {
    val first = FeatureTaskRuntimeRepairConstruct(symbol = "Type.member", file = "Type.kt")
    val second = FeatureTaskRuntimeRepairConstruct(symbol = "TYPE.MEMBER", file = "TYPE.kt")
    assertEquals(first.identity, second.identity)
    assertNotEquals(
      first.identity,
      FeatureTaskRuntimeRepairConstruct(symbol = "Type.other").identity,
    )
  }

  @Test
  fun `an entry set over its named collection budget throws and never returns a shortened receipt`() {
    val over = List(REPAIR_RECEIPT_MAX_ENTRIES + 1) { index ->
      addressedEntry(label = "Type$index")
    }
    val error = assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      FeatureTaskRuntimeRepairReceipt(
        roundNumber = 1,
        preFixCheckpointSha = sha,
        entries = over,
      )
    }
    assertEquals("entries", error.fieldPath)
    assertTrue(error.payloadFreeReason.contains("$REPAIR_RECEIPT_MAX_ENTRIES"))
    assertTrue(!error.payloadFreeReason.contains("Type0"))
  }

  @Test
  fun `an intent over its named UTF-8 byte budget throws and never returns a shortened receipt`() {
    val oversized = "x".repeat(REPAIR_RECEIPT_MAX_INTENT_UTF8_BYTES + 1)
    val error = assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      addressedEntry(intent = oversized)
    }
    assertEquals("intent", error.fieldPath)
    assertTrue(error.payloadFreeReason.contains("$REPAIR_RECEIPT_MAX_INTENT_UTF8_BYTES"))
    assertTrue(!error.reason.contains(oversized))
  }

  @Test
  fun `intent label or text carrying a line number or diff hunk marker is rejected`() {
    assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      addressedEntry(intent = "fixed Type.kt:12")
    }.also { error ->
      assertTrue(error.payloadFreeReason.contains("line number"))
      assertTrue(!error.payloadFreeReason.contains("Type.kt:12"))
    }
    assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      addressedEntry(label = "Type.kt:12")
    }.also { error ->
      assertTrue(error.payloadFreeReason.contains("line number"))
      assertTrue(!error.payloadFreeReason.contains("Type.kt:12"))
    }
    assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      addressedEntry(text = "@@ -1,4 +1,6 @@ fun leaked()")
    }.also { error ->
      assertTrue(error.payloadFreeReason.contains("diff hunk"))
    }
    assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      addressedEntry(intent = "@@ -1,4 +1,6 @@ fun leaked()")
    }.also { error ->
      assertTrue(error.payloadFreeReason.contains("diff hunk"))
    }
  }

  @Test
  fun `receipt contract version is the pinned 0_1 constant`() {
    assertEquals("0.1", FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION)
    assertEquals(
      FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION,
      validReceipt().contractVersion,
    )
  }

  @Test
  fun `remediation round number is the completed pass count at implement_fix entry`() {
    assertEquals(2, featureTaskRuntimeRemediationRoundNumber(2))
    assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      featureTaskRuntimeRemediationRoundNumber(0)
    }
  }

  @Test
  fun `coverage keys on finding_id so a briefing-faithful receipt is not rejected for reducer label text`() {
    val carried = listOf(
      GoalSubtaskReviewCompactFinding(
        severity = "blocker",
        label = "ReducerLabel",
        text = "sanitized compact finding text",
        findingId = "F-001",
      ),
      GoalSubtaskReviewCompactFinding(
        severity = "major",
        label = "OtherReducerLabel",
        text = "other sanitized compact finding text",
        findingId = "F-002",
      ),
    )
    val briefingFaithful = FeatureTaskRuntimeRepairReceipt(
      roundNumber = 1,
      preFixCheckpointSha = sha,
      entries = listOf(
        addressedEntry(
          label = "TypeKt",
          text = "resolve the finding at the reported location",
          findingId = "F-001",
        ),
        FeatureTaskRuntimeRepairReceiptEntry(
          severity = "major",
          label = "OtherKt",
          text = "already present on the tree",
          outcome = FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
          constructs = emptyList(),
          intent = "no tree change required",
          findingId = "F-002",
          noEditReason = "construct already matched the finding",
        ),
      ),
    )
    assertTrue(briefingFaithful.coversCarriedFindings(carried))
    val omittedSecond = briefingFaithful.copy(entries = briefingFaithful.entries.take(1))
    assertTrue(!omittedSecond.coversCarriedFindings(carried))
  }

  private fun validReceipt() = FeatureTaskRuntimeRepairReceipt(
    roundNumber = 1,
    preFixCheckpointSha = sha,
    entries = listOf(addressedEntry()),
  )

  private fun addressedEntry(
    label: String = "Type",
    intent: String = "close the finding at Type.member",
    text: String = "unsafe mutation at the seam",
    findingId: String? = null,
  ) = FeatureTaskRuntimeRepairReceiptEntry(
    severity = "blocker",
    label = label,
    text = text,
    outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
    constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Type.member")),
    intent = intent,
    findingId = findingId,
  )
}
