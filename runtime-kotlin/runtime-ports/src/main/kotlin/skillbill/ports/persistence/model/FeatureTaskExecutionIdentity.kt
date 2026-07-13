package skillbill.ports.persistence.model

data class FeatureTaskExecutionIdentity(
  val workflowId: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val governedSpecPath: String,
  val mode: FeatureTaskWorkflowMode,
  val routeScope: FeatureTaskRouteScope = FeatureTaskRouteScope.STANDALONE,
  val contractVersion: String = "0.1",
)

enum class FeatureTaskRouteScope(val wireValue: String) {
  STANDALONE("standalone"),
  GOAL_CHILD("goal_child"),
}

data class FeatureTaskWorkflowCandidate(
  val identity: FeatureTaskExecutionIdentity?,
  val workflow: WorkflowStateRecord,
)
