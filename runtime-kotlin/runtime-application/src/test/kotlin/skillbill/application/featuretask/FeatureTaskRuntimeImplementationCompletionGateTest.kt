package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeImplementationCompletionGateTest {
  @Test
  fun `completed implement with only value does not block at the completion gate`() {
    val claim = FeatureTaskRuntimeImplementationClaim(value = "Dense implement prose segment.")
    val obligations = obligations()

    assertNull(featureTaskRuntimeImplementationCompletionReason("implement", obligations, claim))
    assertNull(
      featureTaskRuntimeIncompleteWorkGateReason(
        phaseId = "implement",
        outputMap = mapOf(
          "status" to "completed",
          "produced_outputs" to mapOf("value" to claim.value),
        ),
        obligations = obligations,
      ),
    )
  }

  @Test
  fun `blank value also does not block at the no-op completion gate`() {
    val claim = FeatureTaskRuntimeImplementationClaim(value = "")
    val obligations = obligations()

    assertNull(featureTaskRuntimeImplementationCompletionReason("implement", obligations, claim))
    assertNull(
      featureTaskRuntimeIncompleteWorkGateReason(
        phaseId = "implement",
        outputMap = mapOf(
          "status" to "completed",
          "produced_outputs" to mapOf("value" to ""),
        ),
        obligations = obligations,
      ),
    )
  }

  @Test
  fun `open obligations are always empty under the no-op gate`() {
    assertEquals(
      emptyList(),
      featureTaskRuntimeOpenObligations(
        obligations(),
        FeatureTaskRuntimeImplementationClaim(value = "any prose"),
      ),
    )
  }

  @Test
  fun `closed repair item ids are always empty`() {
    assertEquals(
      emptyList(),
      featureTaskRuntimeClosedRepairItemIds(
        mapOf(
          "produced_outputs" to mapOf(
            "repair_item_results" to listOf(mapOf("repair_item_id" to "gap-1")),
            "deferred_repair_item_ids" to listOf("gap-2"),
          ),
        ),
      ),
    )
  }

  @Test
  fun `planned task ids from upstream plan prose are not enforced at implement completion`() {
    assertNull(
      featureTaskRuntimeIncompleteWorkGateReason(
        phaseId = "implement",
        outputMap = mapOf(
          "status" to "completed",
          "produced_outputs" to mapOf("value" to "segment without task closure claims"),
        ),
        obligations = FeatureTaskRuntimeImplementationObligations(
          plannedTaskIds = listOf("task-1", "task-2"),
          carriedRepairItemIds = emptyList(),
          loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        ),
      ),
    )
  }

  private fun obligations(): FeatureTaskRuntimeImplementationObligations =
    FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = emptyList(),
      carriedRepairItemIds = emptyList(),
      loopId = null,
    )
}
