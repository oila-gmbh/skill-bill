package skillbill.ports.persistence.model

data class AuditRepairItemResult(
  val itemId: String,
  val outcome: Outcome,
  val evidenceRef: String,
  val verificationRef: String,
  val dispositionGeneration: Int,
) {
  enum class Outcome { FIXED, ALREADY_SATISFIED, SUPERSEDED }
}
