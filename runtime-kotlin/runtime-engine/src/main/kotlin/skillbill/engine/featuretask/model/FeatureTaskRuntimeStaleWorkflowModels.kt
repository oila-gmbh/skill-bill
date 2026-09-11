package skillbill.engine.featuretask.model

data class FeatureTaskRuntimeStaleWorkflow(
  val workflowId: String,
  val issueKey: String?,
  val validationFailure: String,
)

data class FeatureTaskRuntimeStaleWorkflowPruneRequest(
  val workflowIds: List<String> = emptyList(),
  val confirm: Boolean = false,
  val dbPathOverride: String? = null,
)

data class FeatureTaskRuntimeStaleWorkflowPruneResult(
  val dbPath: String,
  val confirmed: Boolean,
  val requestedWorkflowIds: List<String>,
  val staleWorkflows: List<FeatureTaskRuntimeStaleWorkflow>,
  val deletedWorkflowIds: List<String>,
  val retainedWorkflowIds: List<String>,
)
