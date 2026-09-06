package skillbill.application.featuretask.model
import skillbill.ports.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.model.WorkflowId

data class FeatureTaskContinuationLookupQuery(
  val issueKey: IssueKey,
  val repositoryIdentity: String,
  val workflowId: WorkflowId?,
  val routeScope: FeatureTaskRouteScope,
  val readIfPresent: Boolean = false,
)
