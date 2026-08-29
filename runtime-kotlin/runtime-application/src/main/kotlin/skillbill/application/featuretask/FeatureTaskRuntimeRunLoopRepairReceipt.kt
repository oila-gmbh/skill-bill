package skillbill.application.featuretask

import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.upsertRepairReceipt

internal fun FeatureTaskRuntimeRunLoop.persistImplementFixRepairReceipt(
  receipt: FeatureTaskRuntimeRepairReceipt,
): String? = runCatching {
  goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
    state.upsertRepairReceipt(receipt)
  }
}.fold(
  onSuccess = { recorded ->
    if (recorded != null) null else "the review state could not be updated with the repair receipt."
  },
  onFailure = { error ->
    recordRepairReceiptWriteFailure(error)
    "the review state could not be updated with the repair receipt."
  },
)

internal fun FeatureTaskRuntimeRunLoop.recordRepairReceiptWriteFailure(error: Throwable) {
  diagnostics.warning(
    "Feature-task-runtime could not persist the implement_fix repair receipt for issue " +
      "${request.issueKey}, workflow ${request.workflowId}.",
    error,
  )
}

internal fun FeatureTaskRuntimeRunLoop.settleAndPersistImplementFixRepairReceipt(
  args: ImplementFixRepairReceiptArgs,
): AttemptResult? {
  val run = args.run
  val outputMap = args.outputMap
  val reject = args.reject
  val iteration = args.iteration
  val observability = args.observability
  val fileManifest = args.fileManifest
  val settlement = implementFixRepairReceiptSettlement(run, outputMap)
  settlement.rejectionDetail?.let { detail -> return reject("repair-receipt", detail) }
  val writeFailure = settlement.writeFailureReason ?: return null
  return AttemptResult.settled(
    blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = iteration,
        reason = writeFailure,
        observability = observability,
        payload = BlockAndPersistPayload(fileManifest = fileManifest),
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      ),
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoop.implementFixRepairReceiptSettlement(
  run: PhaseRun,
  outputMap: Map<String, Any?>,
): RepairReceiptSettlement {
  val produced = completedImplementFixProducedOutputs(run, outputMap) ?: return RepairReceiptSettlement.None
  val reviewState = goalReviewStateOrNull() ?: return repairReceiptShapeSettlement(produced)
  val anchor = repairReceiptAnchor(reviewState) ?: return repairReceiptShapeSettlement(produced)
  return when (
    val parsed = featureTaskRuntimeParseRepairReceipt(
      produced,
      anchor.baseSha,
      anchor.roundNumber,
      recordTruncation = { record -> runCatching { diagnostics.warning(record) } },
    )
  ) {
    FeatureTaskRuntimeRepairReceiptMissing -> RepairReceiptSettlement.None
    is FeatureTaskRuntimeRepairReceiptRejected -> RepairReceiptSettlement.rejected(parsed.rejectionDetail)
    is FeatureTaskRuntimeRepairReceiptValid -> settledRepairReceipt(parsed.receipt, reviewState)
  }
}

internal fun FeatureTaskRuntimeRunLoop.settledRepairReceipt(
  receipt: FeatureTaskRuntimeRepairReceipt,
  reviewState: GoalSubtaskReviewState,
): RepairReceiptSettlement = featureTaskRuntimeRepairReceiptSettleRejection(
  receipt,
  reviewState,
  refutedCarriedFindingIds(reviewState),
)
  ?.let { detail -> RepairReceiptSettlement.rejected(detail) }
  ?: persistImplementFixRepairReceipt(receipt)?.let { reason -> RepairReceiptSettlement.writeFailed(reason) }
  ?: RepairReceiptSettlement.None

/**
 * The refs verification refuted in the pass this round is repairing. Read from the durable ledger
 * rather than the review output so the runtime's own recorded verdict decides, and scoped to that
 * one pass because every pass renumbers from `F-001`: an unscoped read would let a refutation from
 * an earlier pass waive whichever finding inherited its ordinal.
 *
 * A ledger that cannot be read waives nothing. Coverage then behaves exactly as it did before this
 * set existed, which is the safe direction: the round is sent back rather than advanced on a guess.
 */
internal fun FeatureTaskRuntimeRunLoop.refutedCarriedFindingIds(reviewState: GoalSubtaskReviewState): Set<String> {
  val passNumber = reviewState.passResults.lastOrNull()?.passNumber ?: return emptySet()
  return runCatching {
    recorder.fetchUnaddressedLedger(request.workflowId, request.dbPathOverride)
      .asSequence()
      .filter { finding -> finding.reviewPassNumber == passNumber }
      .filter { finding -> finding.verificationDisposition == UNADDRESSED_FINDING_REJECTED_DISPOSITION }
      .mapNotNull { finding -> finding.findingId?.takeIf(String::isNotBlank) }
      .toSet()
  }.getOrElse { error ->
    diagnostics.warning(
      "Feature-task-runtime could not read the unaddressed-findings ledger for issue " +
        "${request.issueKey}, workflow ${request.workflowId}; repair-receipt coverage waives no " +
        "refuted finding for this round.",
      error,
    )
    emptySet()
  }
}

internal fun FeatureTaskRuntimeRunLoop.repairReceiptShapeSettlement(
  produced: Map<String, Any?>,
): RepairReceiptSettlement = featureTaskRuntimeRepairReceiptShapeRejection(produced)
  ?.let { detail -> RepairReceiptSettlement.rejected(detail) }
  ?: RepairReceiptSettlement.None

internal fun FeatureTaskRuntimeRunLoop.repairReceiptAnchor(reviewState: GoalSubtaskReviewState): RepairReceiptAnchor? {
  val baseSha = reviewState.remediationBaseSha
  val roundNumber = featureTaskRuntimeRemediationRoundNumberOrNull(reviewState)
  if (baseSha == null || roundNumber == null) {
    recordRepairReceiptDegradation(
      if (baseSha == null) {
        "no durable remediation base sha was recorded for this round"
      } else {
        "the durable remediation round number is not yet established"
      },
    )
    return null
  }
  return RepairReceiptAnchor(baseSha = baseSha, roundNumber = roundNumber)
}

internal fun FeatureTaskRuntimeRunLoop.recordRepairReceiptDegradation(reason: String) {
  runCatching {
    diagnostics.warning(
      "Feature-task-runtime did not record the implement_fix repair receipt for issue " +
        "${request.issueKey}, workflow ${request.workflowId}: $reason. The remediation repair " +
        "ledger loses this round.",
    )
  }
}
