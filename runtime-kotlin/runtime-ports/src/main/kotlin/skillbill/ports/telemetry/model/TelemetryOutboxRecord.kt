package skillbill.ports.telemetry.model

data class TelemetryOutboxRecord(
  val id: Long,
  val eventName: String,
  val payloadJson: String,
  val createdAt: String,
  val syncedAt: String?,
  val lastError: String,
  val skillBillVersion: String? = null,
)
