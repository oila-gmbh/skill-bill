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
   *
   * `dispositionedGapIds` must be what the output actually said about the carried gaps — re-reported gaps
   * plus explicitly dispositioned ones. Silence about a carried gap is not a disposition: passing the
   * carried set back in would make the undispositioned check a set difference against itself.
   */
  fun followUpAuditBlockReason(
    history: FeatureTaskRuntimeAuditGenerationHistory,
    dispositionedGapIds: Collection<String>,
    blastRadiusInspection: FeatureTaskRuntimeBlastRadiusInspection?,
    reportsGaps: Boolean,
  ): String? {
    val carriedGapIds = history.latestGapStates().filterValues { it.open }.keys
    if (carriedGapIds.isEmpty()) return null
    val dispositioned = dispositionedGapIds.toSet()
    val undispositioned = carriedGapIds.filterNot(dispositioned::contains).sorted()
    if (undispositioned.isNotEmpty()) {
      return "Follow-up audit must account for every gap the active repair batch was opened against; " +
        "${undispositioned.size} carried gap(s) have no disposition, the first being " +
        "'${undispositioned.first()}'. Re-report a gap still present in produced_outputs.gaps under its " +
        "existing gap_id, or disposition it in produced_outputs.carried_gap_dispositions with status " +
        "resolved and resolution_verified evidence."
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

  /**
   * Every way an agent-authored carried-gap disposition can be unrepresentable or self-contradictory, as the
   * first failing rule.
   *
   * The producer gate and the durable write share this one function. A defect the write path would throw on
   * has to be named to the agent here first: an exception raised inside the recording transaction ends the
   * run with no durable block and no repair prompt, while a gate reason re-enters the bounded audit fix loop.
   */
  @Suppress("ReturnCount")
  fun carriedGapDispositionDefect(
    producedOutputs: Map<String, Any?>,
    carriedGapIds: Set<String>,
    reportedGapIds: Set<String>,
  ): String? {
    FEATURE_TASK_RUNTIME_AUDIT_GAP_DISPOSITION_KEYS.forEach { key ->
      (producedOutputs[key] as? List<*>).orEmpty().forEachIndexed { index, entry ->
        val source = "produced_outputs.$key[$index]"
        val decoded = runCatching { priorGapDispositionFromWire(entry, source) }
        decoded.exceptionOrNull()?.let { failure ->
          return "Carried gap disposition $source is not contract-safe: " +
            (failure.message?.takeIf(String::isNotBlank) ?: failure::class.simpleName.orEmpty())
        }
        dispositionDefect(decoded.getOrThrow(), source, carriedGapIds, reportedGapIds)?.let { return it }
      }
    }
    return null
  }

  @Suppress("ReturnCount")
  private fun dispositionDefect(
    disposition: FeatureTaskRuntimePriorGapDisposition,
    source: String,
    carriedGapIds: Set<String>,
    reportedGapIds: Set<String>,
  ): String? {
    val gapId = disposition.gapId
    if (gapId !in carriedGapIds) {
      return "Carried gap disposition $source names gap '$gapId', which the durable unresolved-gap ledger " +
        "does not carry; disposition only ${carriedGapIds.sorted().joinToString()}."
    }
    val resolved = disposition.status == FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED
    if (resolved && gapId in reportedGapIds) {
      return "Carried gap '$gapId' is dispositioned resolved in $source while the same audit re-reports it " +
        "as a gap; a gap is either verified resolved or still present, never both."
    }
    if (!resolved && gapId !in reportedGapIds) {
      return "Carried gap '$gapId' is dispositioned recurring in $source but is not re-reported in " +
        "produced_outputs.gaps; a recurring gap keeps its identity and must appear as a reported gap."
    }
    return null
  }
}
