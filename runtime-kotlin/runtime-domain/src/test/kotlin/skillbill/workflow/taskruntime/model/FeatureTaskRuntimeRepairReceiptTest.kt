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
  fun `intent label or text carrying a line number, source body, or diff hunk marker is rejected`() {
    listOf("fixed Type.kt:12", "fixed at line: 12", "fixed at #12").forEach { value ->
      assertSanitizedTextRejected(value, "line number") { addressedEntry(intent = it) }
    }
    assertSanitizedTextRejected("Type.kt:12", "line number") { addressedEntry(label = it) }
    val diffHunk = "@@ -1,4 +1,6 @@ fun leaked()"
    assertSanitizedTextRejected(diffHunk, "diff hunk") { addressedEntry(text = it) }
    assertSanitizedTextRejected(diffHunk, "diff hunk") { addressedEntry(intent = it) }
    listOf(
      "return foo()",
      "return value",
      "return value + 1",
      "return this.value",
      " return value",
      "return 42",
      "return (value)",
      "return@scope value",
      "val result = value",
    ).forEach { value ->
      assertSanitizedTextRejected(value, "source body") { addressedEntry(intent = it) }
    }
    assertSanitizedTextRejected("return foo()", "source body") { addressedEntry(text = it) }
    addressedEntry(intent = "close Type.member() at the sanitizer")
    addressedEntry(intent = "already present; no tree change required")
    addressedEntry(text = "SOURCE_BODY misses statements such as return value and val result = value")
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

  private fun assertSanitizedTextRejected(
    value: String,
    expectedReason: String,
    buildEntry: (String) -> FeatureTaskRuntimeRepairReceiptEntry,
  ) {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> { buildEntry(value) }
    assertTrue(error.payloadFreeReason.contains(expectedReason))
    assertTrue(!error.payloadFreeReason.contains(value))
  }

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
