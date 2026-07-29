package skillbill.ports.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAdaptiveDecisionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCheck
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityRepairItem

data class FeatureTaskRuntimeFocusedQualitySelection(
  val semanticFingerprint: String,
  val checks: List<FeatureTaskRuntimeFocusedQualityCheck>,
)

fun interface FeatureTaskRuntimeFocusedQualitySelector {
  fun select(ownedPaths: List<String>): FeatureTaskRuntimeFocusedQualitySelection
}

fun interface FeatureTaskRuntimeFocusedQualityExecutor {
  fun execute(checks: List<FeatureTaskRuntimeFocusedQualityCheck>): List<FeatureTaskRuntimeQualityRepairItem>
}

/**
 * Owns the atomic persistence seam for an adaptive decision and its authoritative transition.
 * Implementations must deduplicate by the stable decision identity.
 */
interface FeatureTaskRuntimeAdaptiveDecisionStore {
  fun read(decisionId: String): FeatureTaskRuntimeAdaptiveDecisionRecord?

  fun persistAndAdvance(
    record: FeatureTaskRuntimeAdaptiveDecisionRecord,
    destinationPhaseId: String,
  )
}
