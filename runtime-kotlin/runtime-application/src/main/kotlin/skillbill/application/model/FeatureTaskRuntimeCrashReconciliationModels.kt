package skillbill.application.model

/**
 * Per-run tally of crash reconciliations by reason class. Counts and class labels only; never
 * carries row contents (SKILL-140 subtask 5, AC-006).
 */
data class FeatureTaskRuntimeCrashReconciliationResult(
  val reconciledCount: Int = 0,
  val reasonClassCounts: Map<String, Int> = emptyMap(),
) {
  companion object {
    val NONE = FeatureTaskRuntimeCrashReconciliationResult()
  }
}

/**
 * Reason class a reconciled orphaned runtime row was transitioned under. The class stays open for a
 * future recorded-exit-status source; lease expiry is the only evidence available today.
 */
enum class FeatureTaskRuntimeCrashReconciliationReason(val wireValue: String) {
  LEASE_EXPIRED("lease_expired"),
}
