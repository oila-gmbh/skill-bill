package skillbill.application.featuretask

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.application.subtaskreview.UnaddressedFindingLedgerScope
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoopReview.reviewBaselineUntrackedPaths(
  runLoop: FeatureTaskRuntimeRunLoop,
  run: PhaseRun,
): List<String> = runLoop.recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
  ?.baselineUntrackedPaths
  ?.takeIf { it.isNotEmpty() }
  ?: runLoop.goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
    ?.baselineUntrackedPaths
    .orEmpty()

fun FeatureTaskRuntimeRunLoopReview.failedReviewLaneReason(result: ParallelCodeReviewResult): String? {
  val parent = result.lane1
  if (parent.agentId.isBlank() || parent.success) return null
  val detail = parent.failureReason?.takeIf(String::isNotBlank) ?: "lane failed"
  return "Feature-task-runtime phase 'review' $detail"
}

internal fun FeatureTaskRuntimeRunLoopReview.reviewBlockerDispositions(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: ReviewBlockerDispositionsArgs,
): List<GoalSubtaskBlockerDisposition> {
  val run = args.run
  val passNumber = args.passNumber
  val result = args.result
  val reviewRunId = args.reviewRunId
  val resolvedTier = args.resolvedTier
  if (passNumber < 2) return emptyList()
  val prior = runLoop.recorder.fetchUnaddressedLedger(run.request.workflowId, run.request.dbPathOverride)
  if (prior.isEmpty()) return emptyList()
  val continuation = run.request.goalContinuation
  val envelope = FeatureTaskRuntimeReviewEnvelope.envelopeMap(
    FeatureTaskRuntimeReviewEnvelope.assemble(
      result = result,
      reviewRunId = reviewRunId,
      cycle = FeatureTaskRuntimeReviewCycleContext(
        passNumber = passNumber,
        resolvedTier = resolvedTier,
        repositoryFingerprint = "disposition-preview",
      ),
    ),
  )
  val verdicts = runLoop.recorder.recordedFindingVerdicts(envelope, run.request.dbPathOverride)
  val current = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
    output = envelope,
    scope = UnaddressedFindingLedgerScope(
      issueKey = continuation?.parentIssueKey ?: run.request.issueKey,
      subtaskId = continuation?.subtaskId ?: 0,
      workflowId = run.request.workflowId,
      reviewPassNumber = passNumber,
    ),
    recordedVerdicts = verdicts,
  )
  return GoalSubtaskReviewSummaryReducer.refutedBlockerSupersedes(prior, current, verdicts)
}

internal fun FeatureTaskRuntimeRunLoopReview.settleRuntimeOwnedReview(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: SettleRuntimeOwnedReviewArgs,
): PhaseOutcome {
  val run = args.run
  val iteration = args.iteration
  val outputText = args.outputText
  val observability = args.observability
  val fileManifest = args.fileManifest
  val acceptedOutput = runCatching {
    runLoop.outputValidator.validatePhaseOutput(
      outputText,
      sourceLabel = run.phaseId,
    ).requireAcceptedOutput(run.phaseId)
  }.getOrElse { error ->
    return runLoop.collaborators.phaseAttemptsContinued2.blockAndPersistInPhase(
      runLoop,
      phaseBlockArgs(
        run,
        iteration,
        "Runtime-owned review settlement did not validate: ${error.message.orEmpty()}",
        observability,
      ),
    )
  }
  with(FeatureTaskRuntimeRunLoopReviewDriverSettlement) {
    retainRuntimeOwnedReviewEvidence(runLoop, run, runLoop.state, iteration, outputText)
    persistReviewCompletionOutcome(
      runLoop,
      PhaseReviewCompletionOutcomeArgs(
        persistence = PhaseReviewPersistenceArgs(run, iteration, observability, fileManifest),
        normalizedOutput = acceptedOutput.normalizedOutput,
        acceptedOutput = acceptedOutput,
        outputText = outputText,
      ),
    )?.let { return it }
    return completeRuntimeOwnedReviewPhase(
      runLoop,
      CompleteRuntimeOwnedReviewPhaseArgs(
        run = run,
        iteration = iteration,
        observability = observability,
        normalizedOutput = acceptedOutput.normalizedOutput,
        acceptedOutput = acceptedOutput,
      ),
    )
  }
}

object FeatureTaskRuntimeRunLoopReviewDriverSettlement {
  internal fun FeatureTaskRuntimeRunLoopReview.retainRuntimeOwnedReviewEvidence(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    outputText: String,
  ) {
    val outputBytes = outputText.encodeToByteArray()
    runLoop.recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = runLoop.request.workflowId,
        phaseId = run.phaseId,
        attempt = iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = run.modelDirective?.model ?: "unspecified",
        recordedAt = runLoop.clock.instant(),
        byteSize = outputBytes.size.toLong(),
        sha256 = RejectedOutputDiagnosticService.sha256(outputBytes),
        payload = outputBytes,
        generation = runLoop.state.evidenceGeneration(run.phaseId),
      ),
      run.request.dbPathOverride,
    )
  }

  internal fun FeatureTaskRuntimeRunLoopReview.persistReviewCompletionOutcome(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PhaseReviewCompletionOutcomeArgs,
  ): PhaseOutcome? {
    return if (runLoop.collaborators.outputPersistence.isGoalReviewRun(args.persistence.run)) {
      runLoop.collaborators.outputPersistence.persistGoalReviewCompletion(
        runLoop,
        args.persistence,
        args.normalizedOutput,
        args.acceptedOutput.repairEvidence,
      )
    } else {
      runLoop.collaborators.outputPersistence.persistStandaloneReviewCompletion(
        runLoop,
        args.persistence,
        args.outputText,
        args.acceptedOutput,
      )
    }
  }

  internal fun FeatureTaskRuntimeRunLoopReview.completeRuntimeOwnedReviewPhase(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: CompleteRuntimeOwnedReviewPhaseArgs,
  ): PhaseOutcome {
    val run = args.run
    val iteration = args.iteration
    val normalizedOutput = args.normalizedOutput
    val acceptedOutput = args.acceptedOutput
    runLoop.observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        acceptedOutput.repairEvidence,
      ),
    )
  }
}
