package skillbill.workflow.taskruntime.model

enum class SettlementStatus(val wireValue: String) {
  COMPLETED("completed"),
  BLOCKED("blocked"),
  FAILED("failed"),
  ;

  companion object {
    fun fromWire(value: String): SettlementStatus? = when (value) {
      "complete" -> COMPLETED
      "block" -> BLOCKED
      "fail" -> FAILED
      else -> entries.firstOrNull { it.wireValue == value }
    }
  }
}

data class SettlementEnvelopeRequest(
  val phaseId: String,
  val status: String,
  val value: String,
  val summary: String,
  val prompt: String? = null,
  val verdict: String? = null,
  val failureDisposition: String? = null,
)
