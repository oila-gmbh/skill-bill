package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.ports.persistence.FeatureTaskRuntimeAuditGenerationRepository
import skillbill.ports.persistence.FeatureTaskRuntimeAuditGenerationRow
import skillbill.workflow.taskruntime.model.AUDIT_GENERATION_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGeneration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGenerationHistory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBlastRadiusInspection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCriterionInspection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCriterionInspectionVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGenerationGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairBatch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint

/**
 * What one settlement contributes to the append-only audit authority. An audit settlement carries a plan
 * and the dispositions it reached; a repair settlement carries the terminal results for the batch it was
 * given. Both are reconciled against the durable history, never against in-memory state.
 */
internal data class AuditGenerationAppend(
  val workflowId: String,
  val repositoryFingerprint: String,
  val auditScopeCriterionRefs: List<String>,
  /** True when the settling phase is the audit itself, which contributes a generation even with no gap. */
  val auditSettlement: Boolean = false,
  val latestPlan: FeatureTaskRuntimeAuditRepairPlan? = null,
  val dispositions: List<FeatureTaskRuntimePriorGapDisposition> = emptyList(),
  val repairResults: List<FeatureTaskRuntimeRepairItemResult> = emptyList(),
  val supersededRepairItems: Map<String, skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGovernanceEvidence> =
    emptyMap(),
  val blastRadiusInspection: FeatureTaskRuntimeBlastRadiusInspection? = null,
)

/**
 * Builds and appends the next audit generation.
 *
 * Every state transition and recurrence increment is computed against the durable generations, so a claim
 * that a gap was fixed cannot erase the identity it was opened under: a defect reported again lands on the
 * same gap_id with an incremented recurrence count, and the earlier generation stays byte-identical.
 */
internal object FeatureTaskRuntimeAuditGenerationRecorder {
  fun loadHistory(
    generations: FeatureTaskRuntimeAuditGenerationRepository,
    workflowId: String,
  ): FeatureTaskRuntimeAuditGenerationHistory = FeatureTaskRuntimeAuditGenerationHistory(
    generations.listOrdered(workflowId).map { row ->
      auditGenerationFromWire(
        JsonSupport.jsonStringToMap(row.generationJson),
        "audit_generation:${row.workflowId}#${row.generationOrdinal}",
      )
    },
  )

  fun append(
    generations: FeatureTaskRuntimeAuditGenerationRepository,
    request: AuditGenerationAppend,
  ): FeatureTaskRuntimeAuditGeneration {
    val history = loadHistory(generations, request.workflowId)
    val generation = build(history, request)
    // The history invariants run before the insert so an incoherent generation is rejected instead of
    // becoming durable history no later read can repair.
    FeatureTaskRuntimeAuditGenerationHistory(history.generations + generation)
    generations.append(
      FeatureTaskRuntimeAuditGenerationRow(
        workflowId = request.workflowId,
        generationOrdinal = generation.generationOrdinal,
        repositoryCheckpoint = generation.repositoryCheckpoint.fingerprint,
        contractVersion = generation.contractVersion,
        generationJson = JsonSupport.mapToJsonString(auditGenerationToWire(generation)),
      ),
    )
    return generation
  }

  fun build(
    history: FeatureTaskRuntimeAuditGenerationHistory,
    request: AuditGenerationAppend,
  ): FeatureTaskRuntimeAuditGeneration {
    val ordinal = (history.latestGeneration?.generationOrdinal ?: 0) + 1
    return if (request.auditSettlement || request.latestPlan != null || request.dispositions.isNotEmpty()) {
      buildAuditGeneration(history, request, ordinal)
    } else {
      buildRepairGeneration(history, request, ordinal)
    }
  }

  private fun buildAuditGeneration(
    history: FeatureTaskRuntimeAuditGenerationHistory,
    request: AuditGenerationAppend,
    ordinal: Int,
  ): FeatureTaskRuntimeAuditGeneration {
    val priorStates = history.latestGapStates()
    val priorRecurrence = history.recurrenceCounts()
    val dispositionByGapId = request.dispositions.associateBy { it.gapId }
    val reported = request.latestPlan?.gaps.orEmpty().associateBy { it.gapId }
    val carriedOpenGapIds = priorStates.filterValues { it.open }.keys
    val gapIds = (carriedOpenGapIds + reported.keys).toList()
    val gaps = gapIds.map { gapId ->
      val planGap = reported[gapId]
      val prior = history.generations.flatMap { it.gaps }.lastOrNull { it.gapId == gapId }
      val disposition = dispositionByGapId[gapId]
      val state = resolveAuditGapState(priorStates[gapId], disposition, planGap != null)
      val recurrence = (priorRecurrence[gapId] ?: 0) +
        if (state == FeatureTaskRuntimeAuditGapState.RECURRING) 1 else 0
      FeatureTaskRuntimeGenerationGap(
        gapId = gapId,
        acceptanceCriterionRef = planGap?.acceptanceCriterionRef
          ?: prior?.acceptanceCriterionRef
          ?: schemaError("Carried gap '$gapId' has no durable acceptance criterion."),
        acceptanceCriterionText = planGap?.acceptanceCriterionText
          ?: prior?.acceptanceCriterionText
          ?: schemaError("Carried gap '$gapId' has no durable acceptance criterion text."),
        state = state,
        recurrenceCount = recurrence,
        failureEvidence = planGap?.failureEvidence
          ?: disposition?.evidence
          ?: prior?.failureEvidence
          ?: schemaError("Carried gap '$gapId' has no durable failure evidence."),
        diagnosis = planGap?.diagnosis ?: prior?.diagnosis ?: schemaError("Carried gap '$gapId' has no diagnosis."),
        affectedBoundary = planGap?.affectedBoundary
          ?: prior?.affectedBoundary
          ?: schemaError("Carried gap '$gapId' has no affected boundary."),
        repairItemIds = planGap?.repairItems?.map { it.repairItemId }
          ?: prior?.repairItemIds.orEmpty(),
      )
    }
    return FeatureTaskRuntimeAuditGeneration(
      generationOrdinal = ordinal,
      repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(request.repositoryFingerprint),
      inspectedCriteria = inspectedCriteria(request.auditScopeCriterionRefs, gaps),
      satisfiedCriterionRefs = satisfiedCriteria(request.auditScopeCriterionRefs, gaps),
      gaps = gaps,
      repairBatch = FeatureTaskRuntimeRepairBatch(
        batchId = "batch-$ordinal",
        repairItemIds = gaps.filter { it.state.open }.flatMap { it.repairItemIds },
        repairItemDispositions = emptyList(),
      ),
      blastRadiusInspection = request.blastRadiusInspection,
    )
  }

  private fun resolveAuditGapState(
    priorState: FeatureTaskRuntimeAuditGapState?,
    disposition: FeatureTaskRuntimePriorGapDisposition?,
    reportedByPlan: Boolean,
  ): FeatureTaskRuntimeAuditGapState = when {
    priorState == null -> FeatureTaskRuntimeAuditGapState.NEW
    disposition?.status == FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED ->
      FeatureTaskRuntimeAuditGapState.RESOLVED
    disposition?.status == FeatureTaskRuntimePriorGapDisposition.Status.RECURRING ->
      FeatureTaskRuntimeAuditGapState.RECURRING
    // A gap the audit re-reported without dispositioning it is recurring under its existing identity, not a
    // fresh finding: this is the seam that stopped a later snapshot from laundering an unfixed defect.
    reportedByPlan -> FeatureTaskRuntimeAuditGapState.RECURRING
    else -> FeatureTaskRuntimeAuditGapState.STILL_OPEN
  }

  private fun buildRepairGeneration(
    history: FeatureTaskRuntimeAuditGenerationHistory,
    request: AuditGenerationAppend,
    ordinal: Int,
  ): FeatureTaskRuntimeAuditGeneration {
    val latest = history.latestGeneration
      ?: schemaError("A repair settlement cannot append a generation before any audit generation exists.")
    val carried = latest.gaps.filter { it.state.open }
    val gaps = carried.map { gap ->
      gap.copy(state = FeatureTaskRuntimeAuditGapState.STILL_OPEN)
    }
    val priorDispositions = latest.repairBatch.repairItemDispositions.associateBy { it.repairItemId }
    val newDispositions = request.repairResults.associate { result ->
      result.repairItemId to dispositionFor(result, request.supersededRepairItems[result.repairItemId])
    }
    val merged = latest.repairBatch.repairItemIds
      .mapNotNull { itemId -> newDispositions[itemId] ?: priorDispositions[itemId] }
    return FeatureTaskRuntimeAuditGeneration(
      generationOrdinal = ordinal,
      repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(request.repositoryFingerprint),
      inspectedCriteria = inspectedCriteria(request.auditScopeCriterionRefs, gaps, latest.satisfiedCriterionRefs),
      satisfiedCriterionRefs = latest.satisfiedCriterionRefs,
      gaps = gaps,
      repairBatch = latest.repairBatch.copy(repairItemDispositions = merged),
    )
  }

  private fun dispositionFor(
    result: FeatureTaskRuntimeRepairItemResult,
    governance: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGovernanceEvidence?,
  ): FeatureTaskRuntimeRepairItemDisposition = if (governance != null) {
    FeatureTaskRuntimeRepairItemDisposition(
      repairItemId = result.repairItemId,
      disposition = FeatureTaskRuntimeRepairDisposition.SUPERSEDED,
      resultEvidence = result.resultEvidence.copy(
        observation = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence.Observation
          .RESOLUTION_VERIFIED,
      ),
      governanceEvidence = governance,
    )
  } else {
    FeatureTaskRuntimeRepairItemDisposition(
      repairItemId = result.repairItemId,
      disposition = when (result.outcome) {
        FeatureTaskRuntimeRepairItemOutcome.FIXED -> FeatureTaskRuntimeRepairDisposition.FIXED
        FeatureTaskRuntimeRepairItemOutcome.ALREADY_SATISFIED ->
          FeatureTaskRuntimeRepairDisposition.ALREADY_SATISFIED
      },
      resultEvidence = result.resultEvidence,
    )
  }

  private fun inspectedCriteria(
    auditScopeCriterionRefs: List<String>,
    gaps: List<FeatureTaskRuntimeGenerationGap>,
    priorSatisfied: List<String> = emptyList(),
  ): List<FeatureTaskRuntimeCriterionInspection> {
    val gapCriteria = gaps.filter { it.state.open }.mapTo(linkedSetOf()) { it.acceptanceCriterionRef }
    val satisfied = satisfiedCriteria(auditScopeCriterionRefs, gaps, priorSatisfied)
    return (auditScopeCriterionRefs + gapCriteria + satisfied).distinct().map { ref ->
      FeatureTaskRuntimeCriterionInspection(
        acceptanceCriterionRef = ref,
        inspectionVerdict = if (ref in gapCriteria) {
          FeatureTaskRuntimeCriterionInspectionVerdict.GAP
        } else {
          FeatureTaskRuntimeCriterionInspectionVerdict.SATISFIED
        },
      )
    }
  }

  private fun satisfiedCriteria(
    auditScopeCriterionRefs: List<String>,
    gaps: List<FeatureTaskRuntimeGenerationGap>,
    priorSatisfied: List<String> = emptyList(),
  ): List<String> {
    val gapCriteria = gaps.filter { it.state.open }.mapTo(linkedSetOf()) { it.acceptanceCriterionRef }
    return (auditScopeCriterionRefs + priorSatisfied).distinct().filterNot(gapCriteria::contains)
  }

  const val CONTRACT_VERSION: String = AUDIT_GENERATION_CONTRACT_VERSION
}
