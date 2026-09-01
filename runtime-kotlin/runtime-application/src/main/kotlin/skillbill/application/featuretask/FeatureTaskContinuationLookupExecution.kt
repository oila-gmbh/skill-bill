package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupQuery
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.workflow.goalContinuationFor
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.db.UnitOfWork
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.featuretask.model.FeatureTaskWorkflowCandidate
import skillbill.workflow.decomposition.DecompositionManifestValidator

fun executeFeatureTaskContinuationLookup(
  query: FeatureTaskContinuationLookupQuery,
  unitOfWork: UnitOfWork,
  decompositionManifestValidator: DecompositionManifestValidator,
  project: (
    FeatureTaskWorkflowCandidate,
    FeatureTaskRuntimeWorkerOwnership?,
    FeatureTaskRouteScope,
  ) -> FeatureTaskContinuationCandidate,
  classify: (List<FeatureTaskContinuationCandidate>) -> FeatureTaskContinuationLookupResult,
): FeatureTaskContinuationLookupResult {
  val normalizedIssueKey = FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
    query.issueKey,
    query.repositoryIdentity,
  )
  val candidates = when (query.routeScope) {
    FeatureTaskRouteScope.STANDALONE -> unitOfWork.workflowStates.findStandaloneFeatureTaskCandidates(
      normalizedIssueKey,
      query.repositoryIdentity,
    )
    FeatureTaskRouteScope.GOAL_CHILD -> unitOfWork.workflowStates.findGoalChildFeatureTaskCandidates(
      normalizedIssueKey,
      query.repositoryIdentity,
    )
  }
  val selected = query.workflowId?.let { selector ->
    listOf(
      candidates.singleOrNull { it.workflow.workflowId == selector }
        ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(
          "lookup request",
          "workflow selector '$selector' does not match this issue and repository",
        ),
    )
  } ?: candidates
  val identityLess = selected.firstOrNull { it.identity == null }
  if (identityLess != null) {
    return FeatureTaskContinuationLookupResult.NeedsIdentityRepair(
      workflowId = identityLess.workflow.workflowId,
      summary = "Workflow '${identityLess.workflow.workflowId}' has no immutable execution identity; " +
        "run `skill-bill feature-task repair-identity` for that workflow id before continuing.",
    )
  }
  val validated = selected.map {
    project(
      it,
      unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(it.workflow.workflowId),
      query.routeScope,
    )
  }
  val classified = classify(validated)
  if (classified != FeatureTaskContinuationLookupResult.NoMatch ||
    query.workflowId != null ||
    query.routeScope != FeatureTaskRouteScope.STANDALONE
  ) {
    return classified
  }
  return unitOfWork.workflowStates.goalContinuationFor(
    normalizedIssueKey,
    query.repositoryIdentity,
    decompositionManifestValidator,
  )?.let(FeatureTaskContinuationLookupResult::GoalContinuation) ?: classified
}
