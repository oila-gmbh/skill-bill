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
  fun `salvageCompactReceiptSymbol keeps Type when the member is a spaced display name`() {
    assertEquals(
      "AgentRunServiceRuntimeComponentTest",
      salvageCompactReceiptSymbol(
        "AgentRunServiceRuntimeComponentTest.runtime component exposes agent run service with filesystem launcher binding",
      ),
    )
    assertEquals(null, salvageCompactReceiptSymbol("Type.member"))
    assertEquals(null, salvageCompactReceiptSymbol("runtime-kotlin/src/Type.kt"))
    assertEquals(
      FeatureTaskRuntimeRepairConstruct(
        symbol = "AgentRunServiceRuntimeComponentTest",
        file = "AgentRunServiceRuntimeComponentTest.kt",
      ),
      FeatureTaskRuntimeRepairConstruct.fromArtifactMap(
        mapOf(
          "symbol" to
            "AgentRunServiceRuntimeComponentTest.runtime component exposes agent run service with filesystem launcher binding",
          "file" to "AgentRunServiceRuntimeComponentTest.kt",
        ),
        "repair_receipt.entries[0].constructs[0]",
      ),
    )
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
    assertSanitizedTextRejected("{\"finding\": \"leaked\"}", "serialized payload") { addressedEntry(intent = it) }
    addressedEntry(intent = "close Type.member() at the sanitizer")
    addressedEntry(intent = "already present; no tree change required")
    addressedEntry(text = "SOURCE_BODY misses statements such as return value and val result = value")
    addressedEntry(intent = "delete the { emptyList() } source-literal scrape")
    addressedEntry(text = "assert Ready against a malformed packs root")
    addressedEntry(intent = "route Type.member -> Other.member instead of scraping the source")
  }

  @Test
  fun `receipt contract version is the pinned 0_2 constant`() {
    assertEquals("0.2", FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION)
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

  @Test
  fun `finding_ref alias on a receipt entry satisfies coverage for that finding`() {
    val carried = listOf(
      GoalSubtaskReviewCompactFinding(
        severity = "nit",
        label = "StormLabel",
        text = "a".repeat(300),
        findingId = "F-003",
      ),
    )
    val map = mapOf(
      "severity" to "nit",
      "label" to "SomeClass",
      "text" to "short",
      "finding_ref" to "F-003",
      "outcome" to "addressed",
      "constructs" to listOf(mapOf("symbol" to "SomeClass")),
      "intent" to "close the storm finding by ref",
    )
    val entry = FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(map, "repair_receipt.entries[0]")
    assertEquals("F-003", entry.findingId)
    val receipt = FeatureTaskRuntimeRepairReceipt(
      roundNumber = 1,
      preFixCheckpointSha = sha,
      entries = listOf(entry),
    )
    assertTrue(receipt.coversCarriedFindings(carried))
  }

  @Test
  fun `coverage ignores wrong label and text when finding_id matches`() {
    val carried = listOf(
      GoalSubtaskReviewCompactFinding(
        severity = "major",
        label = "Review",
        text = "description=Merge semantics preserve across phase completion",
        findingId = "F-001",
      ),
    )
    val receipt = FeatureTaskRuntimeRepairReceipt(
      roundNumber = 1,
      preFixCheckpointSha = sha,
      entries = listOf(
        addressedEntry(
          label = "PhaseRecorder",
          text = "clear signature fields on finished writes",
          findingId = "F-001",
        ),
      ),
    )
    assertTrue(receipt.coversCarriedFindings(carried))
  }

  @Test
  fun `an attempted_unresolved entry must carry a reason and the constructs it touched`() {
    val unresolved = FeatureTaskRuntimeRepairReceiptEntry(
      severity = "major",
      label = "Policy",
      text = "the gate still admits an empty set",
      outcome = FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED,
      constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Policy.gate")),
      intent = "reject an empty disposition set at the gate",
      findingId = "F-010",
      unresolvedReason = "the gate cannot reach the review pass ids it would compare",
    )
    assertEquals(
      unresolved,
      FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(unresolved.toArtifactMap(), "repair_receipt.entries[0]"),
    )

    val reasonless = assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      unresolved.copy(unresolvedReason = null)
    }
    assertTrue(reasonless.payloadFreeReason.contains("must be present when outcome is attempted_unresolved"))

    val constructless = assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      unresolved.copy(constructs = emptyList())
    }
    assertTrue(constructless.payloadFreeReason.contains("must name the constructs it touched"))
  }

  @Test
  fun `an unresolved entry keeps the finding accounted for and separable from a closed one`() {
    val carried = listOf(
      GoalSubtaskReviewCompactFinding("blocker", "TypeKt", "closed this round", "F-001"),
      GoalSubtaskReviewCompactFinding("major", "Policy", "still open", "F-002"),
    )
    val receipt = FeatureTaskRuntimeRepairReceipt(
      roundNumber = 1,
      preFixCheckpointSha = sha,
      entries = listOf(
        addressedEntry(label = "TypeKt", text = "closed this round", findingId = "F-001"),
        FeatureTaskRuntimeRepairReceiptEntry(
          severity = "major",
          label = "Policy",
          text = "still open",
          outcome = FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED,
          constructs = listOf(FeatureTaskRuntimeRepairConstruct(symbol = "Policy.gate")),
          intent = "reject an empty disposition set at the gate",
          findingId = "F-002",
          unresolvedReason = "the gate cannot reach the review pass ids it would compare",
        ),
      ),
    )

    assertTrue(receipt.omittedCarriedFindings(carried).isEmpty())
    assertEquals(listOf("F-002"), receipt.attemptedUnresolvedEntries().map { it.findingId })
  }

  @Test
  fun `refutation drops a carried finding by normalized ref and leaves an unnamed one carried`() {
    val carried = listOf(
      GoalSubtaskReviewCompactFinding("blocker", "TypeKt", "closed this round", "F-001"),
      GoalSubtaskReviewCompactFinding("nit", "Query", "the selection is never read", "F-003"),
      GoalSubtaskReviewCompactFinding("major", "Policy", "review named no ref"),
    )

    val remaining = withoutRefutedFindings(carried, setOf(" f-003 "))

    assertEquals(listOf("F-001", null), remaining.map(GoalSubtaskReviewCompactFinding::findingId))
    assertEquals(carried, withoutRefutedFindings(carried, emptySet()))
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
    findingId: String = "F-001",
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
