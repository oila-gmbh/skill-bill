package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.application.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

internal data class FeatureTaskRuntimeCurrentPhaseExecutionContext(
  val currentPhaseId: String?,
  val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  val phases: List<FeatureTaskRuntimePhaseStatus>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val auditGapIterationCount: Int,
  val gateRunCount: Int?,
)

internal class FeatureTaskRuntimeCurrentPhaseExecutionDeriver {
  /**
   * Derives one current-phase execution value from the same durable inputs that selected the
   * current phase. Never reads a completed neighbour's historical counter as current.
   */
  fun derive(context: FeatureTaskRuntimeCurrentPhaseExecutionContext): IdeStatusCurrentPhaseExecution? {
    val phaseId = context.currentPhaseId?.takeIf(String::isNotBlank) ?: return null
    val phaseStatus = context.phases.firstOrNull { it.phaseId == phaseId } ?: return null
    val record = context.records[phaseId]
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
        auditExecution(phaseId, phaseStatus, record, context.auditGapIterationCount)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        reviewExecution(phaseId, phaseStatus, record, context.ledger)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        attemptExecution(phaseId, phaseStatus.attemptCount)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
        validationExecution(phaseId, phaseStatus, context.gateRunCount)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ->
        buildExecution(phaseId, phaseStatus, context.gateRunCount)
      else ->
        edgeExecution(phaseId, record, context.ledger) ?: attemptExecution(phaseId, phaseStatus.attemptCount)
    }
  }

  private fun auditExecution(
    phaseId: String,
    phaseStatus: FeatureTaskRuntimePhaseStatus,
    record: FeatureTaskRuntimePhaseRecord?,
    auditGapIterationCount: Int,
  ): IdeStatusCurrentPhaseExecution? = when {
    auditGapIterationCount >= 1 -> IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP,
      count = auditGapIterationCount,
    )
    phaseStatus.attemptCount >= 1 || record != null -> IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = IdeStatusCurrentPhaseExecutionKind.PASS,
      count = 1,
    )
    else -> null
  }

  private fun reviewExecution(
    phaseId: String,
    phaseStatus: FeatureTaskRuntimePhaseStatus,
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): IdeStatusCurrentPhaseExecution? = activeReviewPassNumber(record, ledger)?.let { pass ->
    IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = IdeStatusCurrentPhaseExecutionKind.PASS,
      count = pass,
    )
  } ?: attemptExecution(phaseId, phaseStatus.attemptCount)

  private fun validationExecution(
    phaseId: String,
    phaseStatus: FeatureTaskRuntimePhaseStatus,
    gateRunCount: Int?,
  ): IdeStatusCurrentPhaseExecution? = gateRunExecution(phaseId, phaseStatus, gateRunCount)

  private fun buildExecution(
    phaseId: String,
    phaseStatus: FeatureTaskRuntimePhaseStatus,
    gateRunCount: Int?,
  ): IdeStatusCurrentPhaseExecution? = gateRunExecution(phaseId, phaseStatus, gateRunCount)

  private fun gateRunExecution(
    phaseId: String,
    phaseStatus: FeatureTaskRuntimePhaseStatus,
    gateRunCount: Int?,
  ): IdeStatusCurrentPhaseExecution? = gateRunCount?.takeIf { it >= 1 }?.let { count ->
    IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = IdeStatusCurrentPhaseExecutionKind.GATE_RUN,
      count = count,
    )
  } ?: attemptExecution(phaseId, phaseStatus.attemptCount)

  private fun edgeExecution(
    phaseId: String,
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): IdeStatusCurrentPhaseExecution? {
    val (loopId, edgeIteration) = activeEdgeContext(phaseId, record, ledger) ?: return null
    val edge = FeatureTaskRuntimePhaseWorkflowQueries.backwardEdgeForLoop(loopId) ?: return null
    return IdeStatusCurrentPhaseExecution(
      phaseId = phaseId,
      kind = if (edge.perEdgeCap == null) {
        IdeStatusCurrentPhaseExecutionKind.SEMANTIC_LOOP
      } else {
        IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE
      },
      count = edgeIteration,
      total = edge.perEdgeCap,
    )
  }

  /**
   * Active review pass for the current review phase. A completed prior review record is omitted
   * when a newer review_fix LOOP_EDGE has reopened review.
   */
  private fun activeReviewPassNumber(
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): Int? {
    val pass = record?.reviewPassNumber ?: return null
    if (record.status != PHASE_STATUS_COMPLETED) return pass
    val latestReviewFixEdge = ledger
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID &&
          it.edgeIteration != null
      }
      .maxByOrNull { it.sequenceNumber }
    val ledgerEdge = latestReviewFixEdge?.edgeIteration
    val reenteredReview = record.takeIf {
      it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    }?.edgeIteration
    return ledgerEdge?.let { edge ->
      reenteredReview?.takeIf { it >= edge }
    }?.let { pass }
  }

  /**
   * Prefer the latest LOOP_EDGE targeting the current phase, falling back to the phase-record
   * watermark when no targeting edge exists.
   */
  private fun activeEdgeContext(
    phaseId: String,
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): Pair<String, Int>? {
    val edgeEntry = ledger
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.phaseId == phaseId &&
          it.loopId != null &&
          it.edgeIteration != null
      }
      .maxByOrNull { it.sequenceNumber }
    if (edgeEntry != null) return edgeEntry.loopId!! to edgeEntry.edgeIteration!!
    record?.loopId?.let { loopId ->
      record.edgeIteration?.let { return loopId to it }
    }
    return null
  }

  private fun attemptExecution(phaseId: String, attemptCount: Int): IdeStatusCurrentPhaseExecution? =
    attemptCount.takeIf { it >= 1 }?.let { count ->
      IdeStatusCurrentPhaseExecution(
        phaseId = phaseId,
        kind = IdeStatusCurrentPhaseExecutionKind.ATTEMPT,
        count = count,
      )
    }
}
