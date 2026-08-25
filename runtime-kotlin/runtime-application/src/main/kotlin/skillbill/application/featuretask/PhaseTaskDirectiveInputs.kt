package skillbill.application.featuretask

import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory

internal data class PhaseTaskDirectiveInputs(
  val carriedFindingIds: Set<String> = emptySet(),
  val agentRunValidateFallback: Boolean = false,
  val packCollectAllCommand: String? = null,
  val packBuildCommand: String? = null,
  val priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
  val validationGateFindings: ValidationFindingSetProjection? = null,
) {
  val validationGateRepair: Boolean get() = validationGateFindings != null
}
