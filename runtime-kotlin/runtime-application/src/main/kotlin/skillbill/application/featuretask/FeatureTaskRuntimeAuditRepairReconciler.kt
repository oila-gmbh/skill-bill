package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditDecisionGeneration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeUnresolvedGapLedger

internal data class AuditRepairReconciliation(
  val prior: FeatureTaskRuntimeAuditRepairState?,
  val latestPlan: FeatureTaskRuntimeAuditRepairPlan?,
  val repairResults: List<FeatureTaskRuntimeRepairItemResult>,
  val dispositions: List<FeatureTaskRuntimePriorGapDisposition>?,
  val repositoryFingerprint: String?,
  val edgeIteration: Int?,
  val auditWrite: Boolean = false,
  val auditScopeCriterionRefs: List<String> = emptyList(),
)

internal object FeatureTaskRuntimeAuditRepairReconciler {
  private data class GapReconciliation(
    val dispositions: List<FeatureTaskRuntimePriorGapDisposition>,
    val recurringIds: Set<String>,
    val latestIds: Set<String>,
    val unresolvedGaps: List<FeatureTaskRuntimeUnresolvedGap>,
    val closedGenerationHighWaterMarks: Map<String, Int>,
  )

  fun reconcile(input: AuditRepairReconciliation): FeatureTaskRuntimeAuditRepairState {
    val acceptedPlans = input.prior?.acceptedPlans.orEmpty() +
      listOfNotNull(input.latestPlan).filterNot { it == input.prior?.acceptedPlans?.lastOrNull() }
    if (acceptedPlans.isEmpty()) schemaError("Audit-repair state requires an accepted plan.")
    val gaps = reconcileUnresolvedGaps(input)
    val allResults = reconcileLatestRepairResults(
      input.prior?.repairItemResults.orEmpty(),
      input.repairResults,
    )
    val progress = reconcileProgress(input, gaps)
    return runCatching {
      FeatureTaskRuntimeAuditRepairState(
        acceptedPlans = acceptedPlans,
        repairItemResults = allResults,
        priorGapDispositions = gaps.dispositions,
        unresolvedGapLedger = FeatureTaskRuntimeUnresolvedGapLedger(
          gaps.unresolvedGaps,
          gaps.closedGenerationHighWaterMarks,
        ),
        repositoryFingerprint = input.prior?.repositoryFingerprint ?: input.repositoryFingerprint,
        progress = progress,
        satisfiedCriterionRefs = reconcileSatisfiedCriteria(input, gaps),
        repositoryFingerprintHistory = (
          input.prior?.repositoryFingerprintHistory.orEmpty() + listOfNotNull(input.repositoryFingerprint)
          ).distinct(),
        gapDispositionHistory = input.prior?.gapDispositionHistory.orEmpty() +
          if (input.auditWrite && input.dispositions != null) input.dispositions else emptyList(),
        decisionGenerations = input.prior?.decisionGenerations.orEmpty() +
          if (input.auditWrite) {
            listOf(
              FeatureTaskRuntimeAuditDecisionGeneration(
                generation = input.prior?.decisionGenerations.orEmpty().size + 1,
                plan = input.latestPlan ?: acceptedPlans.last(),
                repositoryFingerprint = input.repositoryFingerprint,
                dispositions = input.dispositions.orEmpty(),
              ),
            )
          } else {
            emptyList()
          },
      ).also { it.requireDurableCoherence() }
    }.getOrElse { error ->
      if (error is IllegalArgumentException) {
        schemaError("Audit-repair state is incoherent and was not persisted: ${error.message.orEmpty()}")
      } else {
        throw error
      }
    }
  }

  private fun reconcileSatisfiedCriteria(input: AuditRepairReconciliation, gaps: GapReconciliation): List<String> {
    val prior = input.prior?.satisfiedCriterionRefs.orEmpty()
    if (!input.auditWrite) return prior
    val reportedCriteria = input.latestPlan?.gaps.orEmpty().mapTo(linkedSetOf()) { it.acceptanceCriterionRef }
    val stillUnresolved = gaps.unresolvedGaps.mapTo(linkedSetOf()) { it.acceptanceCriterionRef }
    val newlyClosed = input.auditScopeCriterionRefs
      .filterNot { it in reportedCriteria || it in stillUnresolved }
    return (prior + newlyClosed).distinct()
  }

  private fun reconcileProgress(
    input: AuditRepairReconciliation,
    gaps: GapReconciliation,
  ): FeatureTaskRuntimeAuditRepairProgress {
    val newlyAttemptedCount = input.repairResults.size
    val recurringGapCount = gaps.unresolvedGaps.sumOf { it.recurrence }
    val newGapCount = gaps.unresolvedGaps.count { it.recurrence == 0 }
    val auditGapIterationCount = maxOf(
      input.prior?.progress?.auditGapIterationCount ?: 0,
      input.edgeIteration ?: 0,
    )
    return FeatureTaskRuntimeAuditRepairProgress(
      firstPassConvergence = input.prior == null &&
        input.auditWrite &&
        auditGapIterationCount == 0 &&
        gaps.unresolvedGaps.isEmpty(),
      recurringGapCount = recurringGapCount,
      newGapCount = newGapCount,
      attemptedRepairItemCount = (input.prior?.progress?.attemptedRepairItemCount ?: 0) + newlyAttemptedCount,
      resolvedRepairItemCount = (input.prior?.progress?.resolvedRepairItemCount ?: 0) + newlyAttemptedCount,
      auditGapIterationCount = auditGapIterationCount,
    )
  }

  private fun reconcileUnresolvedGaps(input: AuditRepairReconciliation): GapReconciliation {
    val priorLedger = input.prior?.unresolvedGapLedger
    val priorUnresolved = priorLedger?.unresolvedGaps.orEmpty()
    val priorIds = priorUnresolved.mapTo(linkedSetOf()) { it.gapId }
    val dispositions = input.dispositions ?: input.prior?.priorGapDispositions.orEmpty()
    if (input.dispositions != null && dispositions.mapTo(linkedSetOf()) { it.gapId } != priorIds) {
      schemaError(
        "Audit reconciliation must disposition every prior unresolved gap exactly once and cannot " +
          "disposition a gap the durable ledger never carried; ledger=${priorIds.sorted()} " +
          "dispositioned=${dispositions.map { it.gapId }.sorted()}.",
      )
    }
    val resolvedIds = dispositions
      .filter {
        it.status == FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED ||
          it.status == FeatureTaskRuntimePriorGapDisposition.Status.SUPERSEDED
      }
      .mapTo(linkedSetOf()) { it.gapId }
    val recurringIds = dispositions
      .filter { it.status == FeatureTaskRuntimePriorGapDisposition.Status.RECURRING }
      .mapTo(linkedSetOf()) { it.gapId }
      .takeIf { input.auditWrite }
      .orEmpty()
    val latestGaps = input.latestPlan?.gaps
      ?: if (input.repairResults.isNotEmpty()) input.prior?.acceptedPlans?.lastOrNull()?.gaps.orEmpty() else emptyList()
    val latestIds = latestGaps.mapTo(linkedSetOf()) { it.gapId }
    val criterionByPriorGapId = priorUnresolved.associate { it.gapId to it.acceptanceCriterionRef }
    val resolvedCriteria = resolvedIds.mapNotNullTo(linkedSetOf(), criterionByPriorGapId::get)
    val reopenedCriteria = latestGaps.map { it.acceptanceCriterionRef }.filter { it in resolvedCriteria }.sorted()
    rejectReopenedClosedCriteria(input, latestGaps)
    if (!latestIds.containsAll(recurringIds) || latestIds.any(resolvedIds::contains) ||
      reopenedCriteria.isNotEmpty()
    ) {
      schemaError(
        "Recurring gaps must retain their identities and a criterion dispositioned resolved cannot be " +
          "re-reported by the same handoff under any gap id; reopened criteria were $reopenedCriteria.",
      )
    }
    val surviving = priorUnresolved.filterNot { it.gapId in resolvedIds }
    val marks = LinkedHashMap(priorLedger?.closedGenerationHighWaterMarks.orEmpty())
    priorUnresolved.filter { it.gapId in resolvedIds }.forEach { gap ->
      marks[gap.acceptanceCriterionRef] = maxOf(marks[gap.acceptanceCriterionRef] ?: 0, gap.generation)
    }
    return GapReconciliation(
      dispositions = dispositions,
      recurringIds = recurringIds,
      latestIds = latestIds,
      unresolvedGaps = mergeUnresolvedGaps(surviving, marks, latestGaps, recurringIds),
      closedGenerationHighWaterMarks = marks,
    )
  }

  private fun rejectReopenedClosedCriteria(
    input: AuditRepairReconciliation,
    latestGaps: List<FeatureTaskRuntimeAuditGap>,
  ) {
    val durablyClosed = input.prior?.satisfiedCriterionRefs.orEmpty().toSet()
    val reopenedClosedCriteria = latestGaps.map { it.acceptanceCriterionRef }
      .filter { it in durablyClosed }
      .sorted()
    if (reopenedClosedCriteria.isNotEmpty()) {
      schemaError(
        "An audit cannot report a gap against an acceptance criterion that already reached a satisfied " +
          "verdict and is durably closed; reopened criteria were $reopenedClosedCriteria.",
      )
    }
  }

  private fun mergeUnresolvedGaps(
    surviving: List<FeatureTaskRuntimeUnresolvedGap>,
    closedGenerationHighWaterMarks: Map<String, Int>,
    latestGaps: List<FeatureTaskRuntimeAuditGap>,
    recurringIds: Set<String>,
  ): List<FeatureTaskRuntimeUnresolvedGap> {
    val merged = linkedMapOf<String, FeatureTaskRuntimeUnresolvedGap>()
    surviving.forEach { merged[it.gapId] = it }
    latestGaps.forEach { gap ->
      val ordinal = gap.gapId.substringAfterLast("-gap-").toIntOrNull()
        ?: schemaError("Gap '${gap.gapId}' has no numeric stable identity.")
      val priorGap = merged[gap.gapId]
      val nextOrdinal = FeatureTaskRuntimeUnresolvedGapLedger(
        merged.values.toList(),
        closedGenerationHighWaterMarks,
      ).nextGapOrdinal(gap.acceptanceCriterionRef)
      if (priorGap == null && ordinal != nextOrdinal) {
        schemaError(
          "New gap_id '${gap.gapId}' for '${gap.acceptanceCriterionRef}' must use the next durable " +
            "identity ordinal '$nextOrdinal'.",
        )
      }
      merged[gap.gapId] = FeatureTaskRuntimeUnresolvedGap(
        gapId = gap.gapId,
        acceptanceCriterionRef = gap.acceptanceCriterionRef,
        generation = ordinal,
        recurrence = (priorGap?.recurrence ?: 0) + if (gap.gapId in recurringIds) 1 else 0,
      )
    }
    return merged.values.toList()
  }
}
