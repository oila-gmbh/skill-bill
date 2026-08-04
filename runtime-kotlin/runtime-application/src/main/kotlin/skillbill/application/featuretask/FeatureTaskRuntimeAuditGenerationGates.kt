package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGenerationHistory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBlastRadiusInspection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairBatch

/**
 * The two producer-side evidence gates that make audit repair converge.
 *
 * Both return a payload-free block reason or null. Neither iterates or caps: the audit-gap loop stays
 * unbounded, and convergence comes from refusing to accept an output whose own evidence does not close what
 * it was given.
 */
internal object FeatureTaskRuntimeAuditGenerationGates {
  /**
   * A repair attempt under the audit-gap loop settles `completed` only once every carried repair item has a
   * terminal disposition. Partial repair is not a failure — it is a retryable continuation, and naming the
   * exact unclosed item is what makes the next attempt resumable instead of speculative.
   */
  fun repairClosureBlockReason(
    activeBatch: FeatureTaskRuntimeRepairBatch?,
    reportedRepairItemIds: Collection<String>,
  ): String? {
    if (activeBatch == null) return null
    val reported = reportedRepairItemIds.toSet()
    val undeclared = reported.filterNot(activeBatch.repairItemIds::contains).sorted()
    if (undeclared.isNotEmpty()) {
      return "Repair receipt dispositions repair items the active batch '${activeBatch.batchId}' does not " +
        "carry: ${undeclared.joinToString()}. Report exactly the carried items."
    }
    val closed = activeBatch.repairItemDispositions.mapTo(linkedSetOf()) { it.repairItemId } + reported
    val unclosed = activeBatch.repairItemIds.filterNot(closed::contains)
    if (unclosed.isEmpty()) return null
    return "Repair batch '${activeBatch.batchId}' still carries ${unclosed.size} repair item(s) with no " +
      "terminal fixed, already_satisfied, or governed superseded disposition; the first unclosed item is " +
      "'${unclosed.first()}'. Partial repair is resumable: report the remaining items and settle again."
  }

  /**
   * A follow-up audit reaches a satisfied verdict only after it dispositions every gap the active batch was
   * opened against and records what the batch's changed production paths look like now. Without the blast
   * radius, a repair that closed its own gaps while breaking a neighbouring boundary reads as convergence.
   */
  fun followUpAuditBlockReason(
    history: FeatureTaskRuntimeAuditGenerationHistory,
    dispositions: List<FeatureTaskRuntimePriorGapDisposition>,
    blastRadiusInspection: FeatureTaskRuntimeBlastRadiusInspection?,
    reportsGaps: Boolean,
  ): String? {
    val carriedGapIds = history.latestGapStates().filterValues { it.open }.keys
    if (carriedGapIds.isEmpty()) return null
    val dispositioned = dispositions.mapTo(linkedSetOf()) { it.gapId }
    val undispositioned = carriedGapIds.filterNot(dispositioned::contains).sorted()
    if (undispositioned.isNotEmpty()) {
      return "Follow-up audit must disposition every gap the active repair batch was opened against; " +
        "${undispositioned.size} carried gap(s) have no disposition, the first being " +
        "'${undispositioned.first()}'."
    }
    // A gap-reporting audit is not claiming convergence, so it owes no blast-radius record yet; only the
    // verdict that ends the loop does.
    if (reportsGaps) return null
    if (blastRadiusInspection == null) {
      return "Follow-up audit cannot emit a satisfied verdict without a blast-radius inspection over the " +
        "repair batch's changed production paths; the inspection record is absent."
    }
    return null
  }
}
