package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator

@Inject
class FeatureTaskRuntimePhaseGates(
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  val planningStopper: FeatureTaskRuntimePlanningStopper,
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  val gitOperations: WorkflowGitOperations,
  val specGate: FeatureTaskRuntimeSpecGate,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
)
