package skillbill.ports.persistence.model

data class FeatureTaskPhaseSettlement(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val kind: String,
  val envelopeJson: String,
  val recordedAt: String,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId must be non-blank." }
    require(phaseId.isNotBlank()) { "phaseId must be non-blank." }
    require(attempt >= 1) { "attempt must be >= 1." }
    require(kind.isNotBlank()) { "kind must be non-blank." }
    require(envelopeJson.isNotBlank()) { "envelopeJson must be non-blank." }
    require(recordedAt.isNotBlank()) { "recordedAt must be non-blank." }
  }
}
