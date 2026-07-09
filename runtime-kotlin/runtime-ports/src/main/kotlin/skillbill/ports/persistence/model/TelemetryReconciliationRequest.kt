package skillbill.ports.persistence.model

import java.time.Instant

data class TelemetryReconciliationRequest(
  val level: String,
  val cadenceSeconds: Long = 300L,
  val maximumBatchSize: Int = 100,
  val sessionThresholdSeconds: Long = 28_800L,
  val goalIssueAbandonmentDays: Long = 14L,
  val now: Instant = Instant.now(),
) {
  init {
    require(cadenceSeconds >= 0L)
    require(maximumBatchSize > 0)
    require(sessionThresholdSeconds >= 0L)
    require(goalIssueAbandonmentDays >= 0L)
  }
}
