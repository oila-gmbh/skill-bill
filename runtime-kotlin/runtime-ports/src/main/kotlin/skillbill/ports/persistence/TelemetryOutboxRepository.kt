package skillbill.ports.persistence

data class TelemetryOutboxRecord(
  val id: Long,
  val eventName: String,
  val payloadJson: String,
  val createdAt: String,
  val syncedAt: String?,
  val lastError: String,
)

interface TelemetryOutboxRepository {
  fun enqueue(eventName: String, payloadJson: String): Long

  fun listPending(limit: Int? = null): List<TelemetryOutboxRecord>

  fun pendingCount(): Int

  fun latestError(): String?

  fun markSynced(id: Long, syncedAt: String)

  fun markSynced(eventIds: List<Long>)

  fun markFailed(id: Long, lastError: String)

  fun markFailed(eventIds: List<Long>, lastError: String)

  fun clear(): Int
}
