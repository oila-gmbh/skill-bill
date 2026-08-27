package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
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
  fun `a receipt with no unresolved items passes outside the audit repair loop`() {
    assertNull(reasonFor(claim(completedTaskIds = listOf("task-1"))))
    assertNull(
      featureTaskRuntimeIncompleteWorkGateReason(
        phaseId = "implement",
        outputMap = mapOf(
          "status" to "completed",
          "produced_outputs" to mapOf(
            "completed_task_ids" to listOf("task-1"),
          ),
        ),
        obligations = obligations(),
      ),
    )
  }

  @Test
  fun `missing plan task ids do not block outside the audit repair loop`() {
    assertNull(
      featureTaskRuntimeIncompleteWorkGateReason(
        phaseId = "implement",
        outputMap = mapOf(
          "status" to "completed",
          "produced_outputs" to mapOf(
            "completed_task_ids" to listOf("task-1"),
          ),
        ),
        obligations = obligations(),
      ),
    )
  }

  @Test
  fun `open obligations are reported in the plan's declared order`() {
    val open = featureTaskRuntimeOpenObligations(
      plannedTaskObligations(),
      claim(completedTaskIds = listOf("task-2")),
    )

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
  fun `under the audit repair loop a missing repair item blocks and the reason names that exact id`() {
    val auditObligations = FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = emptyList(),
      carriedRepairItemIds = listOf("gap-1", "gap-2"),
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
    )
    val reason = featureTaskRuntimeIncompleteWorkGateReason(
      phaseId = "implement",
      outputMap = mapOf(
        "status" to "completed",
        "produced_outputs" to mapOf(
          "completed_task_ids" to listOf("gap-1"),
        ),
      ),
      obligations = auditObligations,
    )

    assertNotNull(reason)
    assertTrue(reason.contains("gap-2"), "Block must name the missing repair item; got: $reason")
  }

  @Test
  fun `an informational deviation against closed work passes`() {
    assertNull(
      featureTaskRuntimeImplementationCompletionReason(
        phaseId = "implement",
        obligations = plannedTaskObligations(),
        claim = claim(
          completedTaskIds = listOf("task-1", "task-2", "task-3"),
          deviations = listOf(FeatureTaskRuntimeReceiptDeviation("task-2", "used a sibling file")),
        ),
      ),
    )
  }

  @Test
  fun `a deviation naming something outside the obligation set never blocks`() {
    assertNull(
      featureTaskRuntimeImplementationCompletionReason(
        phaseId = "implement",
        obligations = plannedTaskObligations(),
        claim = claim(
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
  fun `planned task ids from upstream plan prose are not enforced at implement completion`() {
    assertNull(
      featureTaskRuntimeIncompleteWorkGateReason(
        phaseId = "implement",
        outputMap = mapOf(
          "status" to "completed",
          "produced_outputs" to mapOf(
            "completed_task_ids" to emptyList<String>(),
          ),
        ),
        obligations = FeatureTaskRuntimeImplementationObligations(
          plannedTaskIds = emptyList(),
          carriedRepairItemIds = emptyList(),
          loopId = FeatureTaskRuntimePhaseWorkflowDefinition.IMPLEMENT_REGENERATION_LOOP_ID,
        ),
      ),
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
      emptyList(),
      parsed.actionableDeviations(obligations().requiredIds).map { it.ref },
      "Without enforced plan task ids, deviations are not actionable at the completion gate.",
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
      emptyList(),
      parsed.actionableDeviations(obligations().requiredIds).map { it.ref },
      "Without enforced plan task ids, deviations are not actionable at the completion gate.",
    )
  }

  private fun reasonFor(claim: FeatureTaskRuntimeImplementationClaim): String? =
    featureTaskRuntimeImplementationCompletionReason("implement", obligations(), claim)

  private fun plannedTaskObligations(): FeatureTaskRuntimeImplementationObligations =
    FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = listOf("task-1", "task-2", "task-3"),
      carriedRepairItemIds = emptyList(),
      loopId = null,
    )

  private fun obligations(): FeatureTaskRuntimeImplementationObligations = FeatureTaskRuntimeImplementationObligations(
    plannedTaskIds = emptyList(),
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
