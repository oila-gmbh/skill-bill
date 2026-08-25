package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeRepairReceiptTest {
  private val sha = "a".repeat(40)

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
    assertTrue(briefingFaithful.omittedCarriedFindings(carried).isEmpty())
    val omittedSecond = briefingFaithful.copy(entries = briefingFaithful.entries.take(1))
    assertTrue(omittedSecond.omittedCarriedFindings(carried).isNotEmpty())
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
    assertTrue(receipt.omittedCarriedFindings(carried).isEmpty())
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
    assertTrue(receipt.omittedCarriedFindings(carried).isEmpty())
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
