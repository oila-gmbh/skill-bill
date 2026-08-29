package skillbill.workflow.taskruntime.model

data class SettlementEnvelopeRequest(
  val phaseId: String,
  val status: String,
  val value: String,
  val summary: String,
  val prompt: String? = null,
  val verdict: String? = null,
  val failureDisposition: String? = null,
)
