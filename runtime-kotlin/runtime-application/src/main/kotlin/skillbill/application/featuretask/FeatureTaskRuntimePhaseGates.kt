package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.ports.review.DeclaredReviewSpecialistsPort
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator

@Suppress("LongParameterList") // single preflight seam; all parameters are mandatory gate dependencies
@Inject
class FeatureTaskRuntimePhaseGates(
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  val planningStopper: FeatureTaskRuntimePlanningStopper,
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  val gitOperations: WorkflowGitOperations,
  val specGate: FeatureTaskRuntimeSpecGate,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val nativeAgentPreflight: ReviewNativeAgentPreflightPort,
  val declaredSpecialists: DeclaredReviewSpecialistsPort,
) {
  fun reviewNativeAgentPreflight(request: FeatureTaskRuntimeRunRequest) {
    val specialists = declaredSpecialists.declaredSpecialists(request.repoRoot)
    if (specialists.isEmpty()) return
    val agentIds = buildList {
      add(request.invokedAgentId)
      request.parallelReviewAgent?.let { add(it) }
    }
    nativeAgentPreflight.verify(
      ReviewNativeAgentPreflightRequest(
        repoRoot = request.repoRoot,
        agentIds = agentIds,
        logicalNames = specialists,
      ),
    )
  }
}
