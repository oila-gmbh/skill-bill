package skillbill.application.featuretask.model

data class FeatureTaskPhaseSettlementCompleteRequest(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val value: String,
  val prompt: String? = null,
  val summary: String? = null,
  val dbPathOverride: String? = null,
)

data class FeatureTaskPhaseSettlementBlockRequest(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val reason: String,
  val failureDisposition: String = "needs_user_action",
  val dbPathOverride: String? = null,
)

data class FeatureTaskPhaseSettlementAuditRequest(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val verdict: String,
  val value: String,
  val summary: String? = null,
  val dbPathOverride: String? = null,
)
