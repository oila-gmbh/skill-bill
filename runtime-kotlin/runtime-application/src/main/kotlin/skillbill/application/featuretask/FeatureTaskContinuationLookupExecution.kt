package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskContinuationCandidate
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupQuery
import skillbill.application.featuretask.model.FeatureTaskContinuationLookupResult
import skillbill.application.workflow.goalContinuationFor
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.continuation.FeatureTaskExecutionIdentityPolicy
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.FeatureTaskRouteScope
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.workflow.decomposition.DecompositionManifestValidator

internal data class FeatureTaskContinuationLookupCallbacks(
  val project: (
    FeatureTaskWorkflowCandidate,
    FeatureTaskRuntimeWorkerOwnership?,
    FeatureTaskRouteScope,
  ) -> FeatureTaskContinuationCandidate,
  val classify: (List<FeatureTaskContinuationCandidate>) -> FeatureTaskContinuationLookupResult,
  val validateCandidate: (FeatureTaskWorkflowCandidate) -> Unit,
  val warnOnUnrelatedSchemaFailure: (FeatureTaskWorkflowCandidate, InvalidWorkflowStateSchemaError) -> Unit,
)

internal data class FeatureTaskContinuationLookupExecutionRequest(
  val query: FeatureTaskContinuationLookupQuery,
  val unitOfWork: UnitOfWork,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val callbacks: FeatureTaskContinuationLookupCallbacks,
)

internal fun executeFeatureTaskContinuationLookup(
  request: FeatureTaskContinuationLookupExecutionRequest,
): FeatureTaskContinuationLookupResult {
  val normalizedIssueKey = FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
    request.query.issueKey,
    request.query.repositoryIdentity,
  )
  val selected = selectCandidates(request, normalizedIssueKey)
  val schemaValidated = validateCandidates(request, selected, normalizedIssueKey)
  val identityLess = schemaValidated.firstOrNull { it.identity == null }
  if (identityLess != null) {
    return FeatureTaskContinuationLookupResult.NeedsIdentityRepair(
      workflowId = identityLess.workflow.workflowId,
      summary = "Workflow '${identityLess.workflow.workflowId}' has no immutable execution identity; " +
        "run `skill-bill feature-task repair-identity` for that workflow id before continuing.",
    )
  }
  val validated = projectCandidates(request, schemaValidated, normalizedIssueKey)
  return classifyAndResolveGoalContinuation(request, validated, normalizedIssueKey)
}

private fun selectCandidates(
  request: FeatureTaskContinuationLookupExecutionRequest,
  normalizedIssueKey: String,
): List<FeatureTaskWorkflowCandidate> {
  val query = request.query
  val candidates = when (query.routeScope) {
    FeatureTaskRouteScope.STANDALONE -> request.unitOfWork.workflowStates.findStandaloneFeatureTaskCandidates(
      normalizedIssueKey,
      query.repositoryIdentity,
    )
    FeatureTaskRouteScope.GOAL_CHILD ->
      request.unitOfWork.workflowStates
        .findGoalChildFeatureTaskCandidatesForExecution(normalizedIssueKey, query.repositoryIdentity)
  }
  val selector = query.workflowId ?: return candidates
  return listOf(
    candidates.singleOrNull { it.workflow.workflowId == selector }
      ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "lookup request",
        "workflow selector '$selector' does not match this issue and repository",
      ),
  )
}

private fun validateCandidates(
  request: FeatureTaskContinuationLookupExecutionRequest,
  candidates: List<FeatureTaskWorkflowCandidate>,
  normalizedIssueKey: String,
): List<FeatureTaskWorkflowCandidate> = candidates.mapNotNull { candidate ->
  withSchemaFailureHandling(request, candidate, normalizedIssueKey) {
    request.callbacks.validateCandidate(candidate)
    candidate
  }
}

private fun projectCandidates(
  request: FeatureTaskContinuationLookupExecutionRequest,
  candidates: List<FeatureTaskWorkflowCandidate>,
  normalizedIssueKey: String,
): List<FeatureTaskContinuationCandidate> = candidates.mapNotNull { candidate ->
  withSchemaFailureHandling(request, candidate, normalizedIssueKey) {
    request.callbacks.project(
      candidate,
      request.unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(candidate.workflow.workflowId),
      request.query.routeScope,
    )
  }
}

private fun <T> withSchemaFailureHandling(
  request: FeatureTaskContinuationLookupExecutionRequest,
  candidate: FeatureTaskWorkflowCandidate,
  normalizedIssueKey: String,
  action: () -> T,
): T? = try {
  action()
} catch (error: InvalidWorkflowStateSchemaError) {
  if (request.query.workflowId != null || candidateOwnership(
      candidate,
      normalizedIssueKey,
    ) != CandidateOwnership.EXPLICIT_MISMATCH
  ) {
    throw error
  }
  request.callbacks.warnOnUnrelatedSchemaFailure(candidate, error)
  null
}

private fun classifyAndResolveGoalContinuation(
  request: FeatureTaskContinuationLookupExecutionRequest,
  candidates: List<FeatureTaskContinuationCandidate>,
  normalizedIssueKey: String,
): FeatureTaskContinuationLookupResult {
  val classified = request.callbacks.classify(candidates)
  if (classified != FeatureTaskContinuationLookupResult.NoMatch) return classified
  if (request.query.workflowId != null) return classified
  if (request.query.routeScope != FeatureTaskRouteScope.STANDALONE) return classified
  return request.unitOfWork.workflowStates.goalContinuationFor(
    normalizedIssueKey,
    request.query.repositoryIdentity,
    request.decompositionManifestValidator,
  )?.let(FeatureTaskContinuationLookupResult::GoalContinuation) ?: classified
}

private fun candidateOwnership(
  candidate: FeatureTaskWorkflowCandidate,
  normalizedIssueKey: String,
): CandidateOwnership = when {
  candidate.workflow.issueKey?.trim()?.uppercase() == normalizedIssueKey ||
    candidate.identity?.normalizedIssueKey == normalizedIssueKey -> CandidateOwnership.REQUESTED
  candidate.workflow.issueKey.isNullOrBlank() && candidate.identity == null -> CandidateOwnership.UNKNOWN
  else -> CandidateOwnership.EXPLICIT_MISMATCH
}

private enum class CandidateOwnership {
  REQUESTED,
  EXPLICIT_MISMATCH,
  UNKNOWN,
}
