package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.review.ReviewDiffEvidence
import skillbill.ports.review.DeclaredReviewSpecialistsPort
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
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
  /**
   * Verifies the native workers this review will actually launch, using [reviewInput] — the scope
   * the review phase already established — so the gate and the review agree on routing. A scope
   * with no attributable records routes to no pack and needs no native worker.
   */
  fun reviewNativeAgentPreflight(request: FeatureTaskRuntimeRunRequest, reviewInput: GoalSubtaskReviewInput?) {
    val changedPaths = reviewScopePaths(reviewInput)
    if (changedPaths.isEmpty()) return
    val specialists = declaredSpecialists.routedSpecialists(request.repoRoot, changedPaths)
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

  private fun reviewScopePaths(reviewInput: GoalSubtaskReviewInput?): List<String> {
    val evidence = reviewInput?.reviewText?.let(ReviewDiffEvidence::parseAttributable) ?: return emptyList()
    return evidence.files.map { it.path }.distinct()
  }
}
