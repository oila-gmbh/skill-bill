package skillbill.ports.persistence.model

data class TelemetryReconciliationResult(
  val featureImplementSessions: Int,
  val featureTaskRuntimeSessions: Int,
  val featureVerifySessions: Int,
  val qualityCheckSessions: Int,
  val goalIssueAbandonedSessions: Int,
  val emittedTerminalEvents: Int,
  val processedCandidates: Int = emittedTerminalEvents,
  val skippedByCadence: Boolean = false,
) {
  companion object {
    val Empty = TelemetryReconciliationResult(
      featureImplementSessions = 0,
      featureTaskRuntimeSessions = 0,
      featureVerifySessions = 0,
      qualityCheckSessions = 0,
      goalIssueAbandonedSessions = 0,
      emittedTerminalEvents = 0,
    )
  }
}
