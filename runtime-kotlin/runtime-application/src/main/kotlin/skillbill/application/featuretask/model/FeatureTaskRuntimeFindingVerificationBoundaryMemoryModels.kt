package skillbill.application.featuretask.model

import skillbill.ports.goalrunner.verification.model.GoalVerificationBoundaryDiscovery

data class FeatureTaskRuntimeFindingBoundaryMemoryRequest(
  val findingId: String,
  val findingPaths: List<String>,
)

data class FeatureTaskRuntimeFindingBoundaryMemorySection(
  val findingId: String,
  val discovery: GoalVerificationBoundaryDiscovery,
)
