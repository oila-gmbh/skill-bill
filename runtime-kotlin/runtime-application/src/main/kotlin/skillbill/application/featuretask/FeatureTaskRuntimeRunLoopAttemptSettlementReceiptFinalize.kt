package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoffInvalid
import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoffValid
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinaliseRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskFinalised
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

val FeatureTaskRuntimeRunLoop.goalContinuationManifestCommitSha: String?
  get() = null

@Inject
class FeatureTaskRuntimeRunLoopAttemptSettlementReceiptFinalize {
  internal fun rejectValidatedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    capture: ValidatedOutputCapture,
    outputMap: Map<String, Any?>,
    rule: String,
    detail: String,
  ): AttemptResult {
    val diagnosticRule = rule
    val path = runLoop.collaborators.recordRejection.rejectionPath(detail)
    val reason = runLoop.collaborators.recordRejection.payloadFreeRejectionReason(rule, path)
    // Only scrubbed semantic templates reach the retry reason. Response-derived dumps stay in the
    // private diagnostic and the authorized repair body.
    val retryFacingConstraint = runLoop.collaborators.recordRejection.payloadFreeSemanticGateConstraint(
      runLoop,
      rule,
      detail,
      outputMap,
    )
    val retryReason = runLoop.collaborators.recordRejection.retryRejectionReason(reason, retryFacingConstraint)
    val diagnosticWrite = runLoop.collaborators.attemptSettlement.recordRejectedOutput(
      runLoop,
      RecordRejectedOutputArgs(
        run = capture.run,
        iteration = capture.iteration,
        rule = diagnosticRule,
        reason = detail,
        captured = capture.captured,
        targeting = runLoop.collaborators.attemptSettlement.rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(capture.run, RejectedOutputTargetingOverrides(path = path)),
        ),
      ),
    )
    // Semantic/schema rejection after a successful parse: rebuild the repair context from the same
    // capture that was just recorded, using only payload-free constraint text so value-bearing detail
    // stays out of the typed context and out of the next prompt outside the repair section.
    return runLoop.collaborators.outputPersistence.schemaInvalidAttempt(
      reason,
      capture.fileManifest,
      retryReason = retryReason,
      correctiveRepairContext = runLoop.collaborators.attemptSettlement.correctiveRepairContextForRejection(
        CorrectiveRepairRejectionArgs(
          run = capture.run,
          iteration = capture.iteration,
          captured = capture.captured,
          diagnosticWrite = diagnosticWrite,
          rejection = CorrectiveRepairRejectionDetail(
            rule = diagnosticRule,
            path = path,
            payloadFreeConstraint = retryFacingConstraint ?: reason,
            acceptedAfterStructuralRepair = capture.repairEvidence != null,
            structuralRepairEvidence = capture.repairEvidence,
          ),
        ),
      ),
    )
  }

  fun retainSettledProducerOutput(runLoop: FeatureTaskRuntimeRunLoop, capture: ValidatedOutputCapture) {
    val run = capture.run
    runLoop.recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = runLoop.request.workflowId,
        phaseId = run.phaseId,
        attempt = capture.iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = run.modelDirective?.model ?: "unspecified",
        recordedAt = runLoop.clock.instant(),
        byteSize = capture.outputByteSize,
        sha256 = capture.outputSha256,
        payload = capture.outputBytes.takeUnless { capture.outputTruncated },
        generation = runLoop.state.evidenceGeneration(run.phaseId),
        repairTurn = run.validationGateRepairTurn,
      ),
      run.request.dbPathOverride,
    )
  }

  internal fun finaliseSubtaskCommit(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): CommitPushFinalisation {
    if (
      run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH ||
      normalizedOutput.envelope["status"] != STATUS_COMPLETED
    ) {
      return CommitPushNotApplicable
    }
    val subtaskCommit = runLoop.collaborators.subtaskCommit
    val branch = subtaskCommit.finalisationBranch(runLoop)
      ?: return subtaskCommit.unownedWorktreeCommitSha(runLoop, run, normalizedOutput)
    val handoff = when (val read = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(normalizedOutput.envelope)) {
      is FeatureTaskRuntimeCommitPushHandoffInvalid -> return CommitPushBlocked(read.reason)
      is FeatureTaskRuntimeCommitPushHandoffValid -> read.handoff
    }
    val identity = runLoop.collaborators.checkpointContinued4.subtaskCommitIdentity(runLoop)
    val ledger = runLoop.collaborators.checkpointContinued4.subtaskCommitLedgerState(runLoop, identity)
    val outcome = FeatureTaskRuntimeSubtaskFinalisation(
      gitOperations = runLoop.phaseGates.gitOperations,
      repoRoot = runLoop.request.repoRoot,
      record = { record -> runCatching { runLoop.diagnostics.warning(record) } },
      recordCommit = { commitSha, stagedPaths ->
        runLoop.collaborators.subtaskCommit.recordFinalisedCheckpointIdentity(
          runLoop,
          RecordFinalisedCheckpointIdentityArgs(run.phaseId, branch, ledger, commitSha, stagedPaths),
        )
      },
    ).finalise(
      FeatureTaskRuntimeSubtaskFinaliseRequest(
        identity = identity,
        durableCommitSha = ledger.commitSha,
        sequenceNumber = ledger.nextSequenceNumber,
        handoff = handoff,
        metadata = FeatureTaskRuntimeCheckpointMetadata(
          phaseId = run.phaseId,
          loopId = null,
          generation = runLoop.collaborators.checkpointContinued5.checkpointGeneration(runLoop, null),
          branch = branch,
          intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
        ),
        manifestCommitSha = runLoop.goalContinuationManifestCommitSha,
      ),
    )
    return when (outcome) {
      is FeatureTaskRuntimeSubtaskFinalisationBlocked -> CommitPushBlocked(outcome.reason)
      is FeatureTaskRuntimeSubtaskFinalised -> CommitPushSettled(
        runLoop.collaborators.subtaskCommit.revalidated(
          runLoop,
          run.phaseId,
          FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(normalizedOutput.envelope, outcome.commitSha),
        ),
      )
    }
  }

  /**
   * The runtime owns no branch here, so it committed nothing and has nothing to amend. Downstream
   * consumers still need a commit sha, so the measured HEAD is published as one and the degradation is
   * recorded: a phase record with no sha at all would fail the `pr` consumer projection and the
   * per-subtask commit invariant alike.
   */
}
