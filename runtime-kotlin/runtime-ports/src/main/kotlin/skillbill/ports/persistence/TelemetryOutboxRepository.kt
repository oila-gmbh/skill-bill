package skillbill.ports.persistence

import skillbill.ports.persistence.model.TelemetryOutboxRecord

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
