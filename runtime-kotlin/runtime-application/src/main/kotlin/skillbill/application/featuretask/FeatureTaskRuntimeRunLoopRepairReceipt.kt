package skillbill.application.featuretask

import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.upsertRepairReceipt

object FeatureTaskRuntimeRunLoopRepairReceipt {
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
      FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
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
    val produced = FeatureTaskRuntimeRunLoopCheckpointRemediation.completedImplementFixProducedOutputs(
      run,
      outputMap,
    ) ?: return RepairReceiptSettlement.None
    val reviewState = FeatureTaskRuntimeRunLoopPlanningBranch.goalReviewStateOrNull(runLoop)
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

  internal fun repairReceiptAnchor(
    runLoop: FeatureTaskRuntimeRunLoop,
    reviewState: GoalSubtaskReviewState,
  ): RepairReceiptAnchor? {
    val baseSha = reviewState.remediationBaseSha
    val roundNumber = featureTaskRuntimeRemediationRoundNumberOrNull(reviewState)
    if (baseSha == null || roundNumber == null) {
      recordRepairReceiptDegradation(
        runLoop,
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

  fun recordRepairReceiptDegradation(runLoop: FeatureTaskRuntimeRunLoop, reason: String) {
    runCatching {
      runLoop.diagnostics.warning(
        "Feature-task-runtime did not record the implement_fix repair receipt for issue " +
          "${runLoop.request.issueKey}, workflow ${runLoop.request.workflowId}: $reason. The remediation repair " +
          "ledger loses this round.",
      )
    }
  }

  internal fun settleCompletedImplementationOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: CompletedImplementationOutputArgs,
  ): AttemptResult? = settleAndPersistImplementFixRepairReceipt(
    runLoop,
    ImplementFixRepairReceiptArgs(
      run = args.run,
      outputMap = args.outputMap,
      reject = args.reject,
      iteration = args.iteration,
      observability = args.observability,
      fileManifest = args.fileManifest,
    ),
  )

  fun blockRemediationBaseSha(runLoop: FeatureTaskRuntimeRunLoop, precedingPhaseId: String, error: String): Boolean {
    FeatureTaskRuntimeRunLoopPlanningBranch.blockAt(
      runLoop,
      precedingPhaseId,
      "Feature-task-runtime could not record the pre-fix remediation base sha before re-entering " +
        "implement_fix" + (if (error.isBlank()) "." else " ($error).") +
        " Without it the reserved remediation pass would silently review the full base-to-current " +
        "delta instead of the remediation delta.",
    )
    return false
  }

  private fun blockCheckpointAfterIndexMutation(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: CommitCheckpointArgs,
    error: String,
    indexSnapshot: String,
  ): Boolean = FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
    runLoop,
    args.precedingPhaseId,
    args.branch,
    FeatureTaskRuntimeRunLoopCheckpoint.withIndexRestoreOutcome(
      runLoop,
      error,
      args.ownedPaths,
      indexSnapshot,
    ),
    args.blockedReason,
  )

  internal fun commitCheckpoint(runLoop: FeatureTaskRuntimeRunLoop, args: CommitCheckpointArgs): Boolean {
    val precedingPhaseId = args.precedingPhaseId
    val branch = args.branch
    val loopId = args.loopId
    val intent = args.intent
    val ownedPaths = args.ownedPaths
    val blockedReason = args.blockedReason
    val snapshot = runLoop.phaseGates.gitOperations.captureIndexState(runLoop.request.repoRoot, ownedPaths)
    if (!snapshot.ok) {
      return FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
        runLoop,
        precedingPhaseId,
        branch,
        snapshot.error,
        blockedReason,
      )
    }
    val parentSha = runLoop.phaseGates.gitOperations.headCommitSha(runLoop.request.repoRoot)
      .takeIf { it.ok }?.value?.trim()?.takeIf(String::isNotBlank)
    val staged = runLoop.phaseGates.gitOperations.stagePaths(runLoop.request.repoRoot, ownedPaths)
    if (!staged.ok) {
      return blockCheckpointAfterIndexMutation(runLoop, args, staged.error, snapshot.value.orEmpty())
    }
    val subtaskIdentity = FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitIdentity(runLoop)
    val message = FeatureTaskRuntimeRunLoopCheckpoint.checkpointCommitMessage(
      runLoop,
      CheckpointCommitMessageArgs(
        branch = branch,
        phaseId = precedingPhaseId,
        loopId = loopId,
        identity = subtaskIdentity,
        intent = intent,
      ),
    )
    val commit = FeatureTaskRuntimeRunLoopCheckpoint.writeSubtaskCommit(runLoop, branch, message, subtaskIdentity)
    if (!commit.ok) {
      return blockCheckpointAfterIndexMutation(runLoop, args, commit.error, snapshot.value.orEmpty())
    }
    return FeatureTaskRuntimeRunLoopCheckpoint.recordCheckpointIdentity(
      runLoop,
      RecordCheckpointIdentityArgs(
        precedingPhaseId = precedingPhaseId,
        branch = branch,
        loopId = loopId,
        ownedPaths = ownedPaths,
        parentSha = parentSha,
        commitSha = commit.value.orEmpty().trim(),
        blockedReason = blockedReason,
      ),
    )
  }
}
