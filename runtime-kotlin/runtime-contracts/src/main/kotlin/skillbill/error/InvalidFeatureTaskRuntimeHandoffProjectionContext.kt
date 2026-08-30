package skillbill.error

data class InvalidFeatureTaskRuntimeHandoffProjectionContext(
  val workflowId: String?,
  val consumerPhaseId: String,
  val projectionName: String,
  val projectionContractId: String,
  val projectionContractVersion: String,
  val failureKind: FeatureTaskRuntimeHandoffProjectionFailureKind,
  val reason: String,
)
