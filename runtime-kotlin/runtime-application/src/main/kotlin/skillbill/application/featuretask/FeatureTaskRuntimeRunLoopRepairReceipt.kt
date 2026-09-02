package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.upsertRepairReceipt

@Inject
class FeatureTaskRuntimeRunLoopRepairReceipt {
  fun persistImplementFixRepairReceipt(
    runLoop: FeatureTaskRuntimeRunLoop,
    receipt: FeatureTaskRuntimeRepairReceipt,
  ): String? = runCatching {
    runLoop.goalContinuationRecorder.updateReviewState(
      runLoop.request.workflowId,
      runLoop.request.dbPathOverride,
    ) { state ->
      state.upsertRepairReceipt(receipt)
    }
  }.fold(
    onSuccess = { recorded ->
      if (recorded != null) null else "the review runLoop.state could not be updated with the repair receipt."
    },
    onFailure = { error ->
      recordRepairReceiptWriteFailure(runLoop, error)
      "the review runLoop.state could not be updated with the repair receipt."
    },
  )

  fun recordRepairReceiptWriteFailure(runLoop: FeatureTaskRuntimeRunLoop, error: Throwable) {
    runLoop.diagnostics.warning(
      "Feature-task-runtime could not persist the implement_fix repair receipt for issue " +
        "${runLoop.request.issueKey}, workflow ${runLoop.request.workflowId}.",
      error,
    )
  }

  internal fun settleAndPersistImplementFixRepairReceipt(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ImplementFixRepairReceiptArgs,
  ): AttemptResult? {
    val run = args.run
    val outputMap = args.outputMap
    val reject = args.reject
    val iteration = args.iteration
    val observability = args.observability
    val fileManifest = args.fileManifest
    val settlement = implementFixRepairReceiptSettlement(runLoop, run, outputMap)
    settlement.rejectionDetail?.let { detail -> return reject("repair-receipt", detail) }
    val writeFailure = settlement.writeFailureReason ?: return null
    return AttemptResult.settled(
      runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = writeFailure,
          observability = runLoop.observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      ),
    )
  }

  internal fun implementFixRepairReceiptSettlement(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): RepairReceiptSettlement {
    val produced = runLoop.collaborators.checkpointContinued1.completedImplementFixProducedOutputs(
      run,
      outputMap,
    ) ?: return RepairReceiptSettlement.None
    val reviewState = runLoop.collaborators.planningBranch.goalReviewStateOrNull(runLoop)
      ?: return repairReceiptShapeSettlement(produced)
    val anchor = repairReceiptAnchor(runLoop, reviewState) ?: return repairReceiptShapeSettlement(produced)
    return when (
      val parsed = featureTaskRuntimeParseRepairReceipt(
        produced,
        anchor.baseSha,
        anchor.roundNumber,
        recordTruncation = { record -> runCatching { runLoop.diagnostics.warning(record) } },
      )
    ) {
      FeatureTaskRuntimeRepairReceiptMissing -> RepairReceiptSettlement.None
      is FeatureTaskRuntimeRepairReceiptRejected -> RepairReceiptSettlement.rejected(parsed.rejectionDetail)
      is FeatureTaskRuntimeRepairReceiptValid -> settledRepairReceipt(runLoop, parsed.receipt, reviewState)
    }
  }

  internal fun settledRepairReceipt(
    runLoop: FeatureTaskRuntimeRunLoop,
    receipt: FeatureTaskRuntimeRepairReceipt,
    reviewState: GoalSubtaskReviewState,
  ): RepairReceiptSettlement = featureTaskRuntimeRepairReceiptSettleRejection(
    receipt,
    reviewState,
    refutedCarriedFindingIds(runLoop, reviewState),
  )
    ?.let { detail -> RepairReceiptSettlement.rejected(detail) }
    ?: persistImplementFixRepairReceipt(runLoop, receipt)?.let { reason -> RepairReceiptSettlement.writeFailed(reason) }
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
  fun refutedCarriedFindingIds(runLoop: FeatureTaskRuntimeRunLoop, reviewState: GoalSubtaskReviewState): Set<String> {
    val passNumber = reviewState.passResults.lastOrNull()?.passNumber ?: return emptySet()
    return runCatching {
      runLoop.recorder.fetchUnaddressedLedger(runLoop.request.workflowId, runLoop.request.dbPathOverride)
        .asSequence()
        .filter { finding -> finding.reviewPassNumber == passNumber }
        .filter { finding -> finding.verificationDisposition == UNADDRESSED_FINDING_REJECTED_DISPOSITION }
        .mapNotNull { finding -> finding.findingId?.takeIf(String::isNotBlank) }
        .toSet()
    }.getOrElse { error ->
      runLoop.diagnostics.warning(
        "Feature-task-runtime could not read the unaddressed-findings ledger for issue " +
          "${runLoop.request.issueKey}, workflow ${runLoop.request.workflowId}; repair-receipt coverage waives no " +
          "refuted finding for this round.",
        error,
      )
      emptySet()
    }
  }

  internal fun repairReceiptShapeSettlement(produced: Map<String, Any?>): RepairReceiptSettlement =
    featureTaskRuntimeRepairReceiptShapeRejection(produced)
      ?.let { detail -> RepairReceiptSettlement.rejected(detail) }
      ?: RepairReceiptSettlement.None
}
