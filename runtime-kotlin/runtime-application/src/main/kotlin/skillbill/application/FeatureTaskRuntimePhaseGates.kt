package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.WorkflowGitOperations

@Inject
class FeatureTaskRuntimePhaseGates(
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  val planningStopper: FeatureTaskRuntimePlanningStopper,
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  val gitOperations: WorkflowGitOperations,
  val specStatusProjector: FeatureTaskRuntimeSpecStatusProjector,
)
