package skillbill.application.featuretask.model

import skillbill.ports.workflow.model.FeatureTaskRouteScope

data class FeatureTaskContinuationLookupQuery(
  val issueKey: String,
  val repositoryIdentity: String,
  val workflowId: String?,
  val routeScope: FeatureTaskRouteScope,
  val readIfPresent: Boolean = false,
)
