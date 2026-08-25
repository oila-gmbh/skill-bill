package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationCompletionGateTest {
  @Test
  fun `a receipt closing every plan task passes`() {
    assertNull(reasonFor(claim(completedTaskIds = listOf("task-1", "task-2", "task-3"))))
  }

  @Test
  fun `a missing task blocks and the reason names that exact task id`() {
    val reason = reasonFor(claim(completedTaskIds = listOf("task-1", "task-3")))

    assertNotNull(reason)
    assertTrue(reason.contains("task-2"), "Block must name the missing id; got: $reason")
    assertTrue(!reason.contains("task-1"), "Block must not accuse closed obligations; got: $reason")
  }

  @Test
  fun `open obligations are reported in the plan's declared order`() {
    val open = featureTaskRuntimeOpenObligations(obligations(), claim(completedTaskIds = listOf("task-2")))

    assertEquals(listOf("task-1", "task-3"), open)
  }

  @Test
  fun `a non-empty unresolved_items blocks and the reason names the field`() {
    val reason = reasonFor(
      claim(completedTaskIds = listOf("task-1", "task-2", "task-3"), unresolvedItems = listOf("tests still owed")),
    )

    assertNotNull(reason)
    assertTrue(reason.contains("unresolved_items"), "Block must name the field; got: $reason")
  }

  @Test
  fun `a deviation against an unclosed obligation blocks`() {
    val reason = reasonFor(
      claim(
        completedTaskIds = listOf("task-1", "task-3"),
        deviations = listOf(FeatureTaskRuntimeReceiptDeviation("task-2", "deferred the seam change")),
      ),
    )

    assertNotNull(reason)
    assertTrue(reason.contains("task-2"), "Block must name the deviating obligation; got: $reason")
  }

  @Test
  fun `an informational deviation against closed work passes`() {
    assertNull(
      reasonFor(
        claim(
          completedTaskIds = listOf("task-1", "task-2", "task-3"),
          deviations = listOf(FeatureTaskRuntimeReceiptDeviation("task-2", "used a sibling file")),
        ),
      ),
    )
  }

  @Test
  fun `a deviation naming something outside the obligation set never blocks`() {
    assertNull(
      reasonFor(
        claim(
          completedTaskIds = listOf("task-1", "task-2", "task-3"),
          deviations = listOf(FeatureTaskRuntimeReceiptDeviation("ac-004", "narrowed the telemetry surface")),
        ),
      ),
    )
  }

  @Test
  fun `under the audit repair loop the receipt is judged against carried repair items not plan tasks`() {
    val auditObligations = FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = listOf("task-1", "task-2", "task-3"),
      carriedRepairItemIds = listOf("gap-1", "gap-2"),
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
    )

    assertEquals(listOf("gap-1", "gap-2"), auditObligations.requiredIds)
    assertEquals("repair item", auditObligations.obligationNoun)
    assertNull(
      featureTaskRuntimeImplementationCompletionReason(
        phaseId = "implement",
        obligations = auditObligations,
        claim = claim(completedTaskIds = listOf("gap-1", "gap-2")),
      ),
    )

    val reason = featureTaskRuntimeImplementationCompletionReason(
      phaseId = "implement",
      obligations = auditObligations,
      claim = claim(completedTaskIds = listOf("gap-1")),
    )
    assertNotNull(reason)
    assertTrue(reason.contains("gap-2") && reason.contains("repair item"), "got: $reason")
  }

  @Test
  fun `under a plan regeneration the receipt is judged against the current plan generation only`() {
    val regenerated = FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = listOf("task-1", "task-2"),
      carriedRepairItemIds = emptyList(),
      loopId = "implement_regeneration",
    )

    assertNull(
      featureTaskRuntimeImplementationCompletionReason(
        phaseId = "implement",
        obligations = regenerated,
        claim = claim(completedTaskIds = listOf("task-1", "task-2")),
      ),
      "A task id from a superseded plan generation must not be charged as missing.",
    )
  }

  @Test
  fun `closed repair item ids union results and deferrals`() {
    val closed = featureTaskRuntimeClosedRepairItemIds(
      mapOf(
        "produced_outputs" to mapOf(
          "repair_item_results" to listOf(mapOf("repair_item_id" to "gap-1")),
          "deferred_repair_item_ids" to listOf("gap-2"),
        ),
      ),
    )

    assertEquals(setOf("gap-1", "gap-2"), closed.toSet())
  }

  @Test
  fun `closed repair item ids are canonicalized so an uppercase echo still closes the item`() {
    val closed = featureTaskRuntimeClosedRepairItemIds(
      mapOf(
        "produced_outputs" to mapOf(
          "repair_item_results" to listOf(mapOf("repair_item_id" to "AC-002-GAP-1-ITEM-1")),
          "deferred_repair_item_ids" to emptyList<String>(),
        ),
      ),
    )

    assertEquals(listOf("ac-002-gap-1-item-1"), closed)
  }

  @Test
  fun `the parser drops every closure value the attempt schema would reject`() {
    val parsed = featureTaskRuntimeImplementationClaimFrom(
      mapOf(
        "produced_outputs" to mapOf(
          "completed_task_ids" to listOf("task-1", "Task_1", "1-task"),
          "changed_paths" to listOf("runtime-kotlin/Sample.kt", "/home/user/repo/Foo.kt", "a\\b.kt", "../x.kt"),
          "unresolved_items" to listOf("open item", "  "),
          "deviations" to listOf(mapOf("ref" to "task-1", "note" to "fine")),
          "repository_checkpoint" to mapOf("fingerprint" to "abc", "base_ref" to "", "head_ref" to "  "),
        ),
      ),
      obligations(),
    )

    assertEquals(listOf("task-1"), parsed.completedTaskIds)
    assertEquals(listOf("runtime-kotlin/Sample.kt"), parsed.changedPaths)
    assertEquals(listOf("open item"), parsed.unresolvedItems)
    assertEquals(listOf("fine"), parsed.deviations.map { it.note })
    assertNull(parsed.repositoryCheckpoint?.baseRef)
    assertNull(parsed.repositoryCheckpoint?.headRef)
    assertEquals("abc", parsed.repositoryCheckpoint?.fingerprint)
  }

  @Test
  fun `an open-work value the attempt schema would reject is sanitized into schema shape not dropped`() {
    val parsed = featureTaskRuntimeImplementationClaimFrom(
      mapOf(
        "produced_outputs" to mapOf(
          "unresolved_items" to listOf("x".repeat(ATTEMPT_MAX_LENGTH + 500), "  "),
          "deviations" to listOf(
            mapOf("ref" to "task-2", "note" to "renamed `foo` to `bar`; migration still open"),
            mapOf("ref" to "task-3", "note" to "--- a/x.kt\n@@ -1 +1 @@ {\"k\": [1]}"),
            mapOf("ref" to "task-1", "note" to "   "),
          ),
        ),
      ),
      obligations(),
    )

    assertEquals(1, parsed.unresolvedItems.size)
    assertEquals(ATTEMPT_MAX_LENGTH, parsed.unresolvedItems.single().length)
    assertEquals(listOf("task-2", "task-3", "task-1"), parsed.deviations.map { it.ref })
    parsed.deviations.forEach { deviation ->
      assertTrue(SCHEMA_COMPACT_SUMMARY.matches(deviation.note), "not schema-valid: '${deviation.note}'")
      assertTrue(deviation.note.length <= ATTEMPT_MAX_LENGTH)
    }
    assertTrue(parsed.deviations.first().note.contains("migration still open"))
  }

  @Test
  fun `a non-string open-work entry is rendered and retained, not dropped for its JSON type`() {
    val parsed = featureTaskRuntimeImplementationClaimFrom(
      mapOf(
        "produced_outputs" to mapOf(
          "completed_task_ids" to listOf("task-1", "task-2", "task-3"),
          "unresolved_items" to listOf(mapOf("item" to "migration script still owed for task-3")),
          "deviations" to listOf(mapOf("ref" to "task-3", "note" to mapOf("text" to "deferred"))),
        ),
      ),
      obligations(),
    )

    assertEquals(1, parsed.unresolvedItems.size)
    assertTrue(
      parsed.unresolvedItems.single().contains("migration script still owed"),
      "got: ${parsed.unresolvedItems}",
    )
    assertEquals(listOf("task-3"), parsed.deviations.map { it.ref })
    parsed.deviations.forEach { deviation ->
      assertTrue(SCHEMA_COMPACT_SUMMARY.matches(deviation.note), "not schema-valid: '${deviation.note}'")
    }
  }

  @Test
  fun `a non-string unresolved item still blocks a completed receipt covering every plan task`() {
    val reason = featureTaskRuntimeIncompleteWorkGateReason(
      phaseId = "implement",
      outputMap = mapOf(
        "status" to "completed",
        "produced_outputs" to mapOf(
          "completed_task_ids" to listOf("task-1", "task-2", "task-3"),
          "unresolved_items" to listOf(mapOf("item" to "migration script still owed for task-3")),
        ),
      ),
      obligations = obligations(),
    )

    assertNotNull(reason, "A non-string unresolved item must not vanish into an advance.")
    assertTrue(reason.contains("unresolved_items"), "got: $reason")
  }

  @Test
  fun `a deviation whose note is unusable is retained under a placeholder note`() {
    val parsed = featureTaskRuntimeImplementationClaimFrom(
      mapOf(
        "produced_outputs" to mapOf(
          "completed_task_ids" to listOf("task-1", "task-2"),
          "deviations" to listOf(mapOf("ref" to "task-3", "note" to "   ")),
        ),
      ),
      obligations(),
    )

    assertEquals(
      listOf("task-3"),
      parsed.actionableDeviations(obligations().requiredIds).map { it.ref },
      "A blank note must not erase the unclosed obligation its ref names.",
    )
    assertTrue(SCHEMA_COMPACT_SUMMARY.matches(parsed.deviations.single().note))
  }

  @Test
  fun `an over-length unresolved item still blocks a completed receipt`() {
    val reason = featureTaskRuntimeIncompleteWorkGateReason(
      phaseId = "implement",
      outputMap = mapOf(
        "status" to "completed",
        "produced_outputs" to mapOf(
          "completed_task_ids" to listOf("task-1", "task-2", "task-3"),
          "unresolved_items" to listOf("y".repeat(ATTEMPT_MAX_LENGTH + 1)),
        ),
      ),
      obligations = obligations(),
    )

    assertNotNull(reason, "An over-length unresolved item must not vanish into an advance.")
    assertTrue(reason.contains("unresolved_items"), "got: $reason")
  }

  @Test
  fun `a backtick-bearing deviation survives the parser and stays actionable`() {
    val parsed = featureTaskRuntimeImplementationClaimFrom(
      mapOf(
        "produced_outputs" to mapOf(
          "completed_task_ids" to listOf("task-1", "task-2"),
          "deviations" to listOf(
            mapOf("ref" to "task-3", "note" to "renamed `foo` to `bar`; migration still open"),
          ),
        ),
      ),
      obligations(),
    )

    assertEquals(
      listOf("task-3"),
      parsed.actionableDeviations(obligations().requiredIds).map { it.ref },
      "A routine deviation note carrying a backtick must not erase the open-work signal.",
    )
  }

  @Test
  fun `prose-closed plan task ids merge into attempt claim completedTaskIds`() {
    val parsed = featureTaskRuntimeImplementationClaimFrom(
      mapOf(
        "produced_outputs" to mapOf(
          "projection_kind" to "implementation_receipt",
          "completed_task_ids" to emptyList<String>(),
        ),
      ),
      obligations(),
      returnedText = """{"summary":"Closed task-1 from prose only"}""",
    )

    assertEquals(listOf("task-1"), parsed.completedTaskIds)
  }

  @Test
  fun `prose-closed obligations on a prior attempt stay closed in continuation`() {
    val history = listOf(
      FeatureTaskRuntimeImplementationAttempt(
        sequenceNumber = 1,
        phaseId = "implement",
        attemptNumber = 1,
        agentId = "claude",
        status = FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
        recordedAt = "2026-01-01T00:00:00Z",
        completedTaskIds = listOf("task-1"),
        changedPaths = emptyList(),
        loopId = null,
        edgeIteration = null,
      ),
    )

    val continuation = featureTaskRuntimeImplementationContinuationFrom("implement", history, obligations())

    assertEquals(listOf("task-2", "task-3"), continuation?.openObligationIds)
  }

  @Test
  fun `audit repair loop closes canonical obligation when prose echoes uppercase criterion ref`() {
    val auditObligations = FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = listOf("task-1"),
      carriedRepairItemIds = listOf("ac-005"),
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
    )
    assertNull(
      featureTaskRuntimeImplementationCompletionReason(
        phaseId = "implement",
        obligations = auditObligations,
        claim = claim(completedTaskIds = emptyList()),
        returnedText = """{"summary":"Remediated AC-005"}""",
      ),
    )
  }

  @Test
  fun `audit repair loop blocks when canonical obligation is absent from prose`() {
    val auditObligations = FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = listOf("task-1"),
      carriedRepairItemIds = listOf("ac-005", "ac-007"),
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
    )
    val reason = featureTaskRuntimeImplementationCompletionReason(
      phaseId = "implement",
      obligations = auditObligations,
      claim = claim(completedTaskIds = emptyList()),
      returnedText = """{"summary":"Remediated AC-005 only"}""",
    )
    assertNotNull(reason)
    assertTrue(reason.contains("ac-007"), "got: $reason")
  }

  private fun reasonFor(claim: FeatureTaskRuntimeImplementationClaim): String? =
    featureTaskRuntimeImplementationCompletionReason("implement", obligations(), claim)

  private fun obligations(): FeatureTaskRuntimeImplementationObligations = FeatureTaskRuntimeImplementationObligations(
    plannedTaskIds = listOf("task-1", "task-2", "task-3"),
    carriedRepairItemIds = emptyList(),
    loopId = null,
  )

  private fun claim(
    completedTaskIds: List<String>,
    unresolvedItems: List<String> = emptyList(),
    deviations: List<FeatureTaskRuntimeReceiptDeviation> = emptyList(),
  ): FeatureTaskRuntimeImplementationClaim = FeatureTaskRuntimeImplementationClaim(
    completedTaskIds = completedTaskIds,
    changedPaths = listOf("runtime-kotlin/Sample.kt"),
    unresolvedItems = unresolvedItems,
    deviations = deviations,
    reconciliationEvidence = FeatureTaskRuntimeReceiptReconciliation(true, "re-read every changed path"),
    repositoryCheckpoint = FeatureTaskRuntimeReceiptCheckpoint("abc123", null, null),
  )

  private companion object {
    // Mirrors feature-task-runtime-implementation-attempt-schema.yaml's nonBlank maxLength and the
    // compactSummary allOf, so a sanitized note is asserted against the shape the validator enforces.
    const val ATTEMPT_MAX_LENGTH = 4096
    val SCHEMA_COMPACT_SUMMARY = Regex(
      "^(?!.*\\{\\s*\")(?!.*\"\\s*:\\s*[\\[{\"])(?!.*@@[^@]*@@)" +
        "(?!(?:diff --git|\\+\\+\\+ |--- ))[^\\n\\r\\t`]*\\S[^\\n\\r\\t`]*$",
    )
  }
}
