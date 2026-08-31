package skillbill.application.featuretask

import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

internal fun FeatureTaskRuntimeRunLoop.rejectValidatedOutput(
  capture: ValidatedOutputCapture,
  outputMap: Map<String, Any?>,
  rule: String,
  detail: String,
): AttemptResult {
  val diagnosticRule = rule
  val path = rejectionPath(detail)
  val reason = payloadFreeRejectionReason(rule, path)
  // Only scrubbed semantic templates reach the retry reason. Response-derived dumps stay in the
  // private diagnostic and the authorized repair body.
  val retryFacingConstraint = payloadFreeSemanticGateConstraint(rule, detail, outputMap)
  val retryReason = retryRejectionReason(reason, retryFacingConstraint)
  val diagnosticWrite = recordRejectedOutput(
    RecordRejectedOutputArgs(
      run = capture.run,
      iteration = capture.iteration,
      rule = diagnosticRule,
      reason = detail,
      captured = capture.captured,
      targeting = rejectedOutputTargeting(
        defaultRejectedOutputTargetingArgs(capture.run, RejectedOutputTargetingOverrides(path = path)),
      ),
    ),
  )
  // Semantic/schema rejection after a successful parse: rebuild the repair context from the same
  // capture that was just recorded, using only payload-free constraint text so value-bearing detail
  // stays out of the typed context and out of the next prompt outside the repair section.
  return schemaInvalidAttempt(
    reason,
    capture.fileManifest,
    retryReason = retryReason,
    correctiveRepairContext = correctiveRepairContextForRejection(
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

internal fun FeatureTaskRuntimeRunLoop.retainSettledProducerOutput(capture: ValidatedOutputCapture) {
  val run = capture.run
  recorder.retainProducerOutput(
    ProducerOutputEvidence(
      workflowId = request.workflowId,
      phaseId = run.phaseId,
      attempt = capture.iteration,
      agentId = run.resolvedAgent.resolvedAgentId,
      model = run.modelDirective?.model ?: "unspecified",
      recordedAt = clock.instant(),
      byteSize = capture.outputByteSize,
      sha256 = capture.outputSha256,
      payload = capture.outputBytes.takeUnless { capture.outputTruncated },
      generation = state.evidenceGeneration(run.phaseId),
      repairTurn = run.validationGateRepairTurn,
    ),
    run.request.dbPathOverride,
  )
}

internal fun FeatureTaskRuntimeRunLoop.finaliseSubtaskCommit(
  run: PhaseRun,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
): CommitPushFinalisation {
  if (
    run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH ||
    normalizedOutput.envelope["status"] != STATUS_COMPLETED
  ) {
    return CommitPushNotApplicable
  }
  val branch = finalisationBranch() ?: return unownedWorktreeCommitSha(run, normalizedOutput)
  val handoff = when (val read = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(normalizedOutput.envelope)) {
    is FeatureTaskRuntimeCommitPushHandoffInvalid -> return CommitPushBlocked(read.reason)
    is FeatureTaskRuntimeCommitPushHandoffValid -> read.handoff
  }
  val identity = subtaskCommitIdentity()
  val ledger = subtaskCommitLedgerState(identity)
  val outcome = FeatureTaskRuntimeSubtaskFinalisation(
    gitOperations = phaseGates.gitOperations,
    repoRoot = request.repoRoot,
    record = { record -> runCatching { diagnostics.warning(record) } },
    recordCommit = { commitSha, stagedPaths ->
      recordFinalisedCheckpointIdentity(run.phaseId, branch, ledger, commitSha, stagedPaths)
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
        generation = checkpointGeneration(null),
        branch = branch,
        intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
      ),
      manifestCommitSha = goalContinuationManifestCommitSha,
    ),
  )
  return when (outcome) {
    is FeatureTaskRuntimeSubtaskFinalisationBlocked -> CommitPushBlocked(outcome.reason)
    is FeatureTaskRuntimeSubtaskFinalised -> CommitPushSettled(
      revalidated(
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
