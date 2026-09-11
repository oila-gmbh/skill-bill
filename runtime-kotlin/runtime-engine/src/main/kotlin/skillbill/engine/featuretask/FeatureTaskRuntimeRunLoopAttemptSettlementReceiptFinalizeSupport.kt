package skillbill.engine.featuretask

import skillbill.engine.featuretask.model.FeatureTaskRuntimeCommitPushHandoffInvalid
import skillbill.engine.featuretask.model.FeatureTaskRuntimeCommitPushHandoffValid
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskFinaliseRequest
import skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskFinalised
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import kotlin.coroutines.cancellation.CancellationException

private data class FinalisationPreparation(
  val handoff: FeatureTaskRuntimeCommitPushHandoffValid,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val ledger: SubtaskCommitLedgerState,
  val branch: String,
  val ownedPaths: List<String>,
  val boundaryHistory: DeclaredBoundaryHistoryProjection,
)

private sealed interface FinalisationPreparationOutcome

private data class FinalisationReady(val value: FinalisationPreparation) : FinalisationPreparationOutcome

private data class FinalisationBlocked(val reason: String) : FinalisationPreparationOutcome

internal fun finaliseSubtaskCommitForRuntime(
  runLoop: FeatureTaskRuntimeRunLoop,
  run: PhaseRun,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
): CommitPushFinalisation {
  return if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH ||
    normalizedOutput.envelope["status"] != STATUS_COMPLETED
  ) {
    CommitPushNotApplicable
  } else {
    finaliseApplicableSubtaskCommit(runLoop, run, normalizedOutput)
  }
}

private fun finaliseApplicableSubtaskCommit(
  runLoop: FeatureTaskRuntimeRunLoop,
  run: PhaseRun,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
): CommitPushFinalisation {
  val subtaskCommit = runLoop.collaborators.subtaskCommit
  val branch = subtaskCommit.finalisationBranch(runLoop)
    ?: return subtaskCommit.unownedWorktreeCommitSha(runLoop, run, normalizedOutput)
  val identityFailure = subtaskCommit.reviewIdentityFailure(runLoop)
  if (identityFailure != null) return CommitPushBlocked(identityFailure)
  return when (val preparation = prepareFinalisation(runLoop, normalizedOutput, branch)) {
    is FinalisationBlocked -> CommitPushBlocked(preparation.reason)
    is FinalisationReady -> executeFinalisation(runLoop, run, normalizedOutput, preparation.value)
  }
}

private fun prepareFinalisation(
  runLoop: FeatureTaskRuntimeRunLoop,
  output: NormalizedFeatureTaskRuntimePhaseOutput,
  branch: String,
): FinalisationPreparationOutcome {
  val handoff = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(output.envelope)
  if (handoff is FeatureTaskRuntimeCommitPushHandoffInvalid) return FinalisationBlocked(handoff.reason)
  val validHandoff = handoff as FeatureTaskRuntimeCommitPushHandoffValid
  val identity = runLoop.collaborators.checkpointContinued4.subtaskCommitIdentity(runLoop)
  val ledger = try {
    runLoop.collaborators.checkpointContinued4.subtaskCommitLedgerState(runLoop, identity)
  } catch (error: FeatureTaskRuntimeSubtaskCommitReconciliationError) {
    return FinalisationBlocked("needs_human: ${error.message.orEmpty()}")
  }
  return prepareFinalisationWithLedger(runLoop, branch, validHandoff, identity, ledger)
}

private fun prepareFinalisationWithLedger(
  runLoop: FeatureTaskRuntimeRunLoop,
  branch: String,
  validHandoff: FeatureTaskRuntimeCommitPushHandoffValid,
  identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ledger: SubtaskCommitLedgerState,
): FinalisationPreparationOutcome {
  val resolvedBranch = loadFinalisationBranch(runLoop)
  if (resolvedBranch is BranchLoadBlocked) return FinalisationBlocked(resolvedBranch.reason)
  val resolved = (resolvedBranch as BranchLoadReady).value
  val phaseRecords = loadFinalisationPhaseRecords(runLoop)
  if (phaseRecords is PhaseRecordsBlocked) return FinalisationBlocked(phaseRecords.reason)
  val records = (phaseRecords as PhaseRecordsReady).value
  val boundaryHistory = resolved?.boundaryHistoryProjection()
    ?.takeUnless { it.paths.isEmpty() && it.roots.isEmpty() }
    ?: declaredBoundaryHistoryProjection(
      records?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY),
      resolved?.boundaryHistoryRoots.orEmpty(),
    )
  return FinalisationReady(
    FinalisationPreparation(
      validHandoff,
      identity,
      ledger,
      branch,
      finalisationOwnedPaths(resolved, records),
      boundaryHistory,
    ),
  )
}

private sealed interface BranchLoad

private data class BranchLoadReady(val value: FeatureTaskRuntimeResolvedBranch?) : BranchLoad

private data class BranchLoadBlocked(val reason: String) : BranchLoad

private fun loadFinalisationBranch(runLoop: FeatureTaskRuntimeRunLoop): BranchLoad = try {
  val branch = runLoop.recorder.loadResolvedBranch(runLoop.request.workflowId, runLoop.request.dbPathOverride)
  if (isGoalContinuationRun(runLoop.request) && branch == null) {
    BranchLoadBlocked(
      finalisationReconciliationFailure(
        runLoop,
        "FeatureTaskRuntimeRunLoopAttemptSettlementReceiptFinalize.finaliseSubtaskCommit",
        "resolved branch ownership is missing",
        null,
      ),
    )
  } else {
    BranchLoadReady(branch)
  }
} catch (error: CancellationException) {
  throw error
} catch (error: IllegalStateException) {
  BranchLoadBlocked(
    finalisationReconciliationFailure(
      runLoop,
      "FeatureTaskRuntimeRunLoopAttemptSettlementReceiptFinalize.finaliseSubtaskCommit",
      "resolved branch ownership could not be read (${error.message.orEmpty()})",
      error,
    ),
  )
}

private sealed interface PhaseRecordsLoad

private data class PhaseRecordsReady(val value: Map<String, FeatureTaskRuntimePhaseRecord>?) : PhaseRecordsLoad

private data class PhaseRecordsBlocked(val reason: String) : PhaseRecordsLoad

private fun loadFinalisationPhaseRecords(runLoop: FeatureTaskRuntimeRunLoop): PhaseRecordsLoad = try {
  val records = runLoop.recorder.loadPhaseRecords(runLoop.request.workflowId, runLoop.request.dbPathOverride)
  if (isGoalContinuationRun(runLoop.request) && records == null) {
    PhaseRecordsBlocked(
      finalisationReconciliationFailure(
        runLoop,
        "FeatureTaskRuntimeRunLoopAttemptSettlementReceiptFinalize.finaliseSubtaskCommit",
        "phase records for boundary-history ownership are missing",
        null,
      ),
    )
  } else {
    PhaseRecordsReady(records)
  }
} catch (error: CancellationException) {
  throw error
} catch (error: IllegalStateException) {
  PhaseRecordsBlocked(
    finalisationReconciliationFailure(
      runLoop,
      "FeatureTaskRuntimeRunLoopAttemptSettlementReceiptFinalize.finaliseSubtaskCommit",
      "phase records for boundary-history ownership could not be read (${error.message.orEmpty()})",
      error,
    ),
  )
}

private fun executeFinalisation(
  runLoop: FeatureTaskRuntimeRunLoop,
  run: PhaseRun,
  output: NormalizedFeatureTaskRuntimePhaseOutput,
  preparation: FinalisationPreparation,
): CommitPushFinalisation {
  val outcome = FeatureTaskRuntimeSubtaskFinalisation(
    gitOperations = runLoop.phaseGates.gitOperations,
    repoRoot = runLoop.request.repoRoot,
    record = { record -> runCatching { runLoop.diagnostics.warning(record) } },
    recordCommit = { commitSha, paths ->
      runLoop.collaborators.subtaskCommit.recordFinalisedCheckpointIdentity(
        runLoop,
        RecordFinalisedCheckpointIdentityArgs(run.phaseId, preparation.branch, preparation.ledger, commitSha, paths),
      )
    },
  ).finalise(
    FeatureTaskRuntimeSubtaskFinaliseRequest(
      identity = preparation.identity,
      durableCommitSha = preparation.ledger.commitSha,
      sequenceNumber = preparation.ledger.nextSequenceNumber,
      handoff = preparation.handoff.handoff,
      metadata = FeatureTaskRuntimeCheckpointMetadata(
        phaseId = run.phaseId,
        loopId = null,
        generation = runLoop.collaborators.checkpointContinued5.checkpointGeneration(runLoop, null),
        branch = preparation.branch,
        intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
      ),
      manifestCommitSha = runLoop.goalContinuationManifestCommitSha,
      enforceReviewBoundary = isGoalContinuationRun(runLoop.request),
      ownedPaths = preparation.ownedPaths,
      boundaryHistoryPaths = preparation.boundaryHistory.paths,
      boundaryHistoryRoots = preparation.boundaryHistory.roots,
    ),
  )
  return when (outcome) {
    is FeatureTaskRuntimeSubtaskFinalisationBlocked -> settleFinalisationBlock(outcome.reason)
    is FeatureTaskRuntimeSubtaskFinalised -> CommitPushSettled(
      runLoop.collaborators.subtaskCommit.revalidated(
        runLoop,
        run.phaseId,
        FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(output.envelope, outcome.commitSha),
      ),
    )
  }
}

private fun settleFinalisationBlock(reason: String): CommitPushFinalisation = CommitPushBlocked(reason)
