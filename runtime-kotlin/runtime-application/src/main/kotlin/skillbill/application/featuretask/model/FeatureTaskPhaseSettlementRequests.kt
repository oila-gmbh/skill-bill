package skillbill.application.featuretask.model
import skillbill.workflow.engine.model.WorkflowId

data class FeatureTaskPhaseSettlementCompleteRequest(
  val workflowId: WorkflowId,
  val phaseId: String,
  val attempt: Int,
  val value: String,
  val prompt: String? = null,
  val summary: String? = null,
)

data class FeatureTaskPhaseSettlementBlockRequest(
  val workflowId: WorkflowId,
  val phaseId: String,
  val attempt: Int,
  val reason: String,
  val failureDisposition: String = "needs_user_action",
)

data class FeatureTaskPhaseSettlementAuditRequest(
  val workflowId: WorkflowId,
  val phaseId: String,
  val attempt: Int,
  val verdict: String,
  val value: String,
  val summary: String? = null,
)
