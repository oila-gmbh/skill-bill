package skillbill.ports.persistence.model

data class RejectedOutputPayloadRead(
  val metadata: RejectedOutputDiagnostic,
  val byteCount: Long,
)
