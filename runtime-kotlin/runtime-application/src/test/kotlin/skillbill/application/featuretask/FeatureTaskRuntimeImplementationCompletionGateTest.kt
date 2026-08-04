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
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.IMPLEMENT_REGENERATION_LOOP_ID,
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
        "repair_item_results" to listOf(mapOf("repair_item_id" to "gap-1")),
        "deferred_repair_item_ids" to listOf("gap-2"),
      ),
    )

    assertEquals(setOf("gap-1", "gap-2"), closed.toSet())
  }

  @Test
  fun `the parser drops every claim value the attempt schema would reject`() {
    val parsed = featureTaskRuntimeImplementationClaimFrom(
      mapOf(
        "produced_outputs" to mapOf(
          "completed_task_ids" to listOf("task-1", "Task_1", "1-task"),
          "changed_paths" to listOf("runtime-kotlin/Sample.kt", "/home/user/repo/Foo.kt", "a\\b.kt", "../x.kt"),
          "unresolved_items" to listOf("open item", "  "),
          "deviations" to listOf(
            mapOf("ref" to "task-1", "note" to "fine"),
            mapOf("ref" to "task-1", "note" to "has\nnewline"),
            mapOf("ref" to "task-1", "note" to "has`backtick"),
          ),
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

  private fun reasonFor(claim: FeatureTaskRuntimeImplementationClaim): String? =
    featureTaskRuntimeImplementationCompletionReason("implement", obligations(), claim)

  private fun obligations(): FeatureTaskRuntimeImplementationObligations =
    FeatureTaskRuntimeImplementationObligations(
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
}
