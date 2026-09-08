package skillbill.workflow.taskruntime.model

enum class SettlementStatus(val wireValue: String) {
  COMPLETED("completed"),
  BLOCKED("blocked"),
  FAILED("failed"),
  ;

  companion object {
    fun fromWire(value: String): SettlementStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

data class SettlementEnvelopeRequest(
  val phaseId: String,
  val status: SettlementStatus,
  val value: String,
  val summary: String,
  val prompt: String? = null,
  val verdict: String? = null,
  val failureDisposition: String? = null,
) {
  constructor(
    phaseId: String,
    status: String,
    value: String,
    summary: String,
    prompt: String? = null,
    verdict: String? = null,
    failureDisposition: String? = null,
  ) : this(
    phaseId = phaseId,
    status = requireNotNull(SettlementStatus.fromWire(status)) { "Unknown settlement status '$status'." },
    value = value,
    summary = summary,
    prompt = prompt,
    verdict = verdict,
    failureDisposition = failureDisposition,
  )
}
