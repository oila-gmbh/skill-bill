package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.application.subtaskreview.UnaddressedFindingLedgerScope
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

@Inject
class FeatureTaskRuntimeRunLoopOutputPersistence {
  internal fun persistRejectedVerificationFindings(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    verifyOutput: Map<String, Any?>,
  ) {
    if (!isGoalContinuationRun(run.request)) return
    val continuation = run.request.goalContinuation ?: return
    val reviewOutput = runLoop.state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      ?.normalizedOutput?.envelope
      ?: return
    val reviewState = runLoop.goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
    val passNumber = reviewState?.completedPassCount?.takeIf { it > 0 } ?: 1
    val recordedVerdicts = runLoop.recorder.recordedFindingVerdicts(reviewOutput, run.request.dbPathOverride)
    val truncationRecords = mutableListOf<String>()
    val rejected = GoalSubtaskReviewSummaryReducer.rejectedVerificationFindings(
      verifyOutput = verifyOutput,
      reviewOutput = reviewOutput,
      scope = UnaddressedFindingLedgerScope(
        issueKey = continuation.parentIssueKey,
        subtaskId = continuation.subtaskId,
        workflowId = run.request.workflowId,
        reviewPassNumber = passNumber,
      ),
      recordedVerdicts = recordedVerdicts,
      truncationRecords = truncationRecords,
    )
    truncationRecords.forEach { record ->
      runCatching { runLoop.diagnostics.warning(record) }
    }
    if (rejected.isEmpty()) return
    runLoop.recorder.appendRejectedVerificationFindings(
      workflowId = run.request.workflowId,
      passNumber = passNumber,
      rejected = rejected,
      dbOverride = run.request.dbPathOverride,
    )
  }

  internal fun persistStandaloneReviewCompletion(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PhaseReviewPersistenceArgs,
    outputText: String,
    acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
  ): PhaseOutcome? {
    val run = args.run
    val iteration = args.iteration
    val observability = args.observability
    val fileManifest = args.fileManifest
    val persisted = try {
      runLoop.recorder.recordCompletedPhase(
        phaseStateRequest(
          runLoop,
          PhaseStateRequestArgs(
            write = PhaseStateWriteArgs(
              run = run,
              iteration = iteration,
              status = STATUS_COMPLETED,
              finished = true,
              outputArtifact = outputText,
            ),
            extras = PhaseStateRequestExtras(
              fileManifest = fileManifest,
              normalizedOutput = acceptedOutput.normalizedOutput,
              repairEvidence = acceptedOutput.repairEvidence,
              reviewRunId = runLoop.state.recordFor(run.phaseId)?.reviewRunId,
            ),
          ),
        ),
        run.request.dbPathOverride,
      )
    } catch (error: RuntimeOwnedFactUnavailable) {
      return runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned review settlement could not establish its persistence fact: " +
            error.message.orEmpty(),
          observability = runLoop.observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
    return if (persisted) {
      null
    } else {
      runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned review settlement could not be persisted.",
          observability = runLoop.observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
  }

  internal fun persistGoalReviewCompletion(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PhaseReviewPersistenceArgs,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): PhaseOutcome? {
    val run = args.run
    val iteration = args.iteration
    val observability = args.observability
    val fileManifest = args.fileManifest
    val completion = goalReviewPhaseCompletionRequest(runLoop, args, normalizedOutput, repairEvidence)
    val completed = runCatching {
      runLoop.recorder.completeGoalReviewPhase(
        completion = completion,
        dbOverride = run.request.dbPathOverride,
      )
    }.getOrElse { error ->
      return runLoop.collaborators.phaseAttemptsContinued2.blockAndPersistInPhase(
        runLoop,
        phaseBlockArgs(
          run,
          iteration,
          "Goal-subtask review could not atomically persist its pass and completed phase: " +
            error.message.orEmpty(),
          runLoop.observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
        ),
      )
    }
    return if (completed) {
      null
    } else {
      runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Goal-subtask review could not atomically persist its reserved pass and completed phase.",
          observability = runLoop.observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
        ),
      )
    }
  }

  internal fun isGoalReviewRun(run: PhaseRun): Boolean =
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(run.request)

  // A goal-subtask review reserves its pass once in prepareGoalReviewRun, outside runPhaseAttempts, so a
  // bounded in-loop re-attempt reuses that same reserved pass instead of allocating another. Schema-invalid
  // output therefore earns the same fix-loop retries as every other phase: the reserved pass has no completed
  // output, which is the runLoop.state a resume is already contracted to re-enter rather than treat as terminal.
  internal fun schemaInvalidAttempt(
    operatorReason: String,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    malformedOutput: Boolean = false,
    retryReason: String = operatorReason,
    correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
  ): AttemptResult = AttemptResult.schemaInvalid(
    SchemaInvalidArgs(
      operatorReason = operatorReason,
      fileManifest = fileManifest,
      rejectedOutput = null,
      malformedOutput = malformedOutput,
      retryReason = retryReason,
      correctiveRepairContext = correctiveRepairContext,
    ),
  )
}
