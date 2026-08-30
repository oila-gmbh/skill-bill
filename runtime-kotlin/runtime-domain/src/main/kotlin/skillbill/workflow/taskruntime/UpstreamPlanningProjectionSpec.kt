package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDelivery

internal data class UpstreamPlanningProjectionSpec(
  val consumerPhaseId: String,
  val sourceRef: FeatureTaskRuntimeHandoffSourceRef,
  val projectionName: String,
  val projectionContractId: String,
  val declaredFieldNames: List<String>,
  val delivery: PhaseHandoffProjectionDelivery,
  val projectionContractVersion: String = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VERSION,
)
