package skillbill.application.featuretask.model

internal data class RejectedOutputDiagnosticDegradeRequest(
  val workflowId: String,
  val operation: String,
  val conflictingKey: String,
  val phaseId: String,
  val attempt: Int,
  val repairTurn: Int?,
  val generation: Int,
  val dbOverride: String?,
)

internal data class RejectedOutputDiagnosticPersistRequest(
  val workflowId: String,
  val operation: String,
  val conflictingKey: String,
  val phaseId: String,
  val attempt: Int,
  val repairTurn: Int?,
  val generation: Int,
  val dbOverride: String?,
  val failureClass: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticFailureClass,
)
