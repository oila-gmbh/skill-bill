package skillbill.ports.persistence.model

import skillbill.contracts.workflow.FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION

data class FeatureTaskExecutionIdentity(
  val workflowId: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val governedSpecPath: String,
  val mode: FeatureTaskWorkflowMode,
  val routeScope: FeatureTaskRouteScope = FeatureTaskRouteScope.STANDALONE,
  val contractVersion: String = FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION,
)

enum class FeatureTaskRouteScope(val wireValue: String) {
  STANDALONE("standalone"),
  GOAL_CHILD("goal_child"),
}

data class FeatureTaskWorkflowCandidate(
  val identity: FeatureTaskExecutionIdentity?,
  val workflow: WorkflowStateRecord,
)
