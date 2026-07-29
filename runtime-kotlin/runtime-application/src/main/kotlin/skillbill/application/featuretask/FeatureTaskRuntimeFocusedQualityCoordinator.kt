package skillbill.application.featuretask

import skillbill.ports.taskruntime.FeatureTaskRuntimeAdaptiveDecisionStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeFocusedQualityRunner
import skillbill.ports.taskruntime.FeatureTaskRuntimeFocusedQualitySelector
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAdaptiveDecisionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityRepairBatch

class FeatureTaskRuntimeFocusedQualityCoordinator(
  private val selector: FeatureTaskRuntimeFocusedQualitySelector,
  private val runner: FeatureTaskRuntimeFocusedQualityRunner,
  private val store: FeatureTaskRuntimeAdaptiveDecisionStore,
) {
  fun runAfterAudit(
    decision: FeatureTaskRuntimeAdaptiveDecisionRecord,
    ownedPaths: List<String>,
    auditCleared: Boolean,
    repairAttempt: Int,
    repositoryCheckpointFingerprint: String = decision.decisionId,
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

    val failures = runner.execute(selection.checks)
    val outcome = if (failures.isEmpty()) {
      FeatureTaskRuntimeFocusedQualityOutcome(
        disposition = FeatureTaskRuntimeFocusedQualityDisposition.PASSED,
        checkpoint = FeatureTaskRuntimeFocusedQualityCheckpoint(
          checkpointFingerprint = "$repositoryCheckpointFingerprint:${selection.semanticFingerprint}",
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
    val destination = if (failures.isEmpty()) {
      skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    } else {
      skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
    }
    store.persistAndAdvance(decision.withFocusedQuality(outcome), destination)
    return outcome
  }
}
