package skillbill.ports.featuretask.model

data class FeatureTaskPhaseSettlement(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val kind: FeatureTaskPhaseSettlementKind,
  val envelopeJson: String,
  val recordedAt: String,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId must be non-blank." }
    require(phaseId.isNotBlank()) { "phaseId must be non-blank." }
    require(attempt >= 1) { "attempt must be >= 1." }
    require(envelopeJson.isNotBlank()) { "envelopeJson must be non-blank." }
    require(recordedAt.isNotBlank()) { "recordedAt must be non-blank." }
  }
}

sealed interface FeatureTaskPhaseSettlementKind {
  val wireValue: String

  data object COMPLETE : FeatureTaskPhaseSettlementKind {
    override val wireValue: String = "complete"
  }

  data object BLOCK : FeatureTaskPhaseSettlementKind {
    override val wireValue: String = "block"
  }

  data object AUDIT_SETTLE : FeatureTaskPhaseSettlementKind {
    override val wireValue: String = "audit_settle"
  }

  data class Unknown(override val wireValue: String) : FeatureTaskPhaseSettlementKind {
    init {
      require(wireValue.isNotBlank()) { "wireValue must be non-blank." }
    }
  }

  companion object {
    fun fromWire(value: String): FeatureTaskPhaseSettlementKind = when (value) {
      COMPLETE.wireValue -> COMPLETE
      BLOCK.wireValue -> BLOCK
      AUDIT_SETTLE.wireValue -> AUDIT_SETTLE
      else -> Unknown(value)
    }
  }
}
