package skillbill.application.featuretask.model

import skillbill.ports.featuretask.model.FeatureTaskRouteScope

data class FeatureTaskContinuationLookupQuery(
  val issueKey: String,
  val repositoryIdentity: String,
  val workflowId: String?,
  val dbOverride: String?,
  val routeScope: FeatureTaskRouteScope,
  val readIfPresent: Boolean = false,
)
