package skillbill.application.featuretask

import skillbill.ports.taskruntime.FeatureTaskRuntimeAdaptiveDecisionStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeFocusedQualityExecutor
import skillbill.ports.taskruntime.FeatureTaskRuntimeFocusedQualitySelector
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAdaptiveDecisionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityRepairBatch

class FeatureTaskRuntimeFocusedQualityCoordinator(
  private val selector: FeatureTaskRuntimeFocusedQualitySelector,
  private val executor: FeatureTaskRuntimeFocusedQualityExecutor,
  private val store: FeatureTaskRuntimeAdaptiveDecisionStore,
) {
  fun runAfterAudit(
    decision: FeatureTaskRuntimeAdaptiveDecisionRecord,
    ownedPaths: List<String>,
    auditCleared: Boolean,
    repairAttempt: Int,
  ): FeatureTaskRuntimeFocusedQualityOutcome {
    require(auditCleared) { "Focused quality may run only after audit clearance." }
    val selection = selector.select(ownedPaths.distinct().sorted())
    val prior = store.read(decision.decisionId)?.focusedQualityOutcome?.checkpoint
    if (prior?.reusableFor(selection.semanticFingerprint) == true) {
      return FeatureTaskRuntimeFocusedQualityOutcome(
        disposition = FeatureTaskRuntimeFocusedQualityDisposition.REUSED,
        checkpoint = prior,
        repairBatch = null,
      )
    }

    val failures = executor.execute(selection.checks)
    val outcome = if (failures.isEmpty()) {
      FeatureTaskRuntimeFocusedQualityOutcome(
        disposition = FeatureTaskRuntimeFocusedQualityDisposition.PASSED,
        checkpoint = FeatureTaskRuntimeFocusedQualityCheckpoint(
          checkpointFingerprint = "${decision.decisionId}:${selection.semanticFingerprint}",
          semanticFingerprint = selection.semanticFingerprint,
          checks = selection.checks,
          passed = true,
        ),
        repairBatch = null,
      )
    } else {
      FeatureTaskRuntimeFocusedQualityOutcome(
        disposition = FeatureTaskRuntimeFocusedQualityDisposition.REPAIR_REQUIRED,
        checkpoint = null,
        repairBatch = FeatureTaskRuntimeQualityRepairBatch(
          batchId = "${decision.decisionId}:${selection.semanticFingerprint}:$repairAttempt",
          checkpointFingerprint = selection.semanticFingerprint,
          attempt = repairAttempt,
          items = failures,
        ),
      )
    }
    val destination = if (failures.isEmpty()) "review" else "implement"
    store.persistAndAdvance(decision.withFocusedQuality(outcome), destination)
    return outcome
  }
}
