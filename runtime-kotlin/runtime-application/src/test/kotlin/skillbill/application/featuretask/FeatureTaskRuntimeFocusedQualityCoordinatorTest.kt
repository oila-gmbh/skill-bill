package skillbill.application.featuretask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.ports.taskruntime.FeatureTaskRuntimeAdaptiveDecisionStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeFocusedQualityExecutor
import skillbill.ports.taskruntime.FeatureTaskRuntimeFocusedQualitySelection
import skillbill.ports.taskruntime.FeatureTaskRuntimeFocusedQualitySelector
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAdaptiveDecisionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAdaptiveReviewPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeComplexitySignals
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCategory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCheck
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSizingPolicyResolver

class FeatureTaskRuntimeFocusedQualityCoordinatorTest {
  private val signals = FeatureTaskRuntimeComplexitySignals(2, 1, 2, 2, false, false, false, false, 1, 8)
  private val sizing = FeatureTaskRuntimeSizingPolicyResolver.resolve(signals)
  private val decision = FeatureTaskRuntimeAdaptiveDecisionRecord(
    "decision-1",
    sizing,
    null,
    FeatureTaskRuntimeAdaptiveReviewPolicy.resolve(sizing, signals, CodeReviewExecutionMode.AUTO),
    null,
  )
  private val check = FeatureTaskRuntimeFocusedQualityCheck(
    "kotlin:compilation",
    FeatureTaskRuntimeFocusedQualityCategory.COMPILATION,
    listOf("runtime-kotlin/runtime-domain"),
    "bill-kotlin-code-check",
  )

  @Test
  fun `requires audit clearance before executing`() {
    val harness = Harness()
    assertFailsWith<IllegalArgumentException> {
      harness.coordinator().runAfterAudit(decision, check.ownedPaths, false, 1)
    }
    assertEquals(0, harness.executions)
  }

  @Test
  fun `failure persists repair transition without a review pass`() {
    val harness = Harness(
      failures = listOf(
        FeatureTaskRuntimeQualityRepairItem(
          "failure-1",
          check.checkId,
          check.category,
          "bounded compilation failure",
        ),
      ),
    )
    val outcome = harness.coordinator().runAfterAudit(decision, check.ownedPaths, true, 1)
    assertEquals(FeatureTaskRuntimeFocusedQualityDisposition.REPAIR_REQUIRED, outcome.disposition)
    assertEquals("implement", harness.destination)
  }

  @Test
  fun `unchanged checkpoint is reused without execution`() {
    val harness = Harness()
    val first = harness.coordinator().runAfterAudit(decision, check.ownedPaths, true, 1)
    val second = harness.coordinator().runAfterAudit(decision, check.ownedPaths, true, 1)
    assertEquals(FeatureTaskRuntimeFocusedQualityDisposition.PASSED, first.disposition)
    assertEquals(FeatureTaskRuntimeFocusedQualityDisposition.REUSED, second.disposition)
    assertEquals(1, harness.executions)
  }

  private inner class Harness(
    private val failures: List<FeatureTaskRuntimeQualityRepairItem> = emptyList(),
  ) : FeatureTaskRuntimeAdaptiveDecisionStore {
    private var stored: FeatureTaskRuntimeAdaptiveDecisionRecord? = null
    var destination: String? = null
    var executions = 0

    fun coordinator() = FeatureTaskRuntimeFocusedQualityCoordinator(
      selector = FeatureTaskRuntimeFocusedQualitySelector {
        FeatureTaskRuntimeFocusedQualitySelection("semantic-1", listOf(check))
      },
      executor = FeatureTaskRuntimeFocusedQualityExecutor {
        executions += 1
        failures
      },
      store = this,
    )

    override fun read(decisionId: String) = stored?.takeIf { it.decisionId == decisionId }

    override fun persistAndAdvance(record: FeatureTaskRuntimeAdaptiveDecisionRecord, destinationPhaseId: String) {
      stored = record
      destination = destinationPhaseId
    }
  }
}
