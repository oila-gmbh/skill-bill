package skillbill.application.workflow

import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode

internal fun hasIncompleteFeatureTaskIdentity(
  kind: WorkflowFamilyKind,
  hasIdentityCoordinates: Boolean,
  issueKey: String?,
  repositoryIdentity: String?,
  governedSpecPath: String?,
): Boolean = kind in FEATURE_TASK_FAMILY_KINDS &&
  hasIdentityCoordinates &&
  listOf(issueKey, repositoryIdentity, governedSpecPath).any { it == null }

internal fun buildFeatureTaskExecutionIdentity(
  kind: WorkflowFamilyKind,
  hasIdentityCoordinates: Boolean,
  workflowId: String,
  issueKey: String?,
  repositoryIdentity: String?,
  governedSpecPath: String?,
  routeScope: FeatureTaskRouteScope,
): FeatureTaskExecutionIdentity? {
  if (kind !in FEATURE_TASK_FAMILY_KINDS || !hasIdentityCoordinates) return null
  val requiredRepositoryIdentity = requireNotNull(repositoryIdentity)
  val normalizedIssueKey = FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
    requireNotNull(issueKey),
    requiredRepositoryIdentity,
  )
  return FeatureTaskExecutionIdentity(
    workflowId = workflowId,
    normalizedIssueKey = normalizedIssueKey,
    repositoryIdentity = requiredRepositoryIdentity,
    governedSpecPath = requireNotNull(governedSpecPath),
    mode = FeatureTaskWorkflowMode.RUNTIME,
    routeScope = routeScope,
  ).also(FeatureTaskExecutionIdentityPolicy::validate)
}
