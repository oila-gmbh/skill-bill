package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.install.model.InstallAgent
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

internal fun FeatureTaskRuntimeRunLoop.persistRejectedVerificationFindings(
  run: PhaseRun,
  verifyOutput: Map<String, Any?>,
) {
  if (!isGoalContinuationRun(run.request)) return
  val continuation = run.request.goalContinuation ?: return
  val reviewOutput = state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    ?.normalizedOutput?.envelope
    ?: return
  val reviewState = goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
  val passNumber = reviewState?.completedPassCount?.takeIf { it > 0 } ?: 1
  val recordedVerdicts = recorder.recordedFindingVerdicts(reviewOutput, run.request.dbPathOverride)
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
    runCatching { diagnostics.warning(record) }
  }
  if (rejected.isEmpty()) return
  recorder.appendRejectedVerificationFindings(
    workflowId = run.request.workflowId,
    passNumber = passNumber,
    rejected = rejected,
    dbOverride = run.request.dbPathOverride,
  )
}

internal fun FeatureTaskRuntimeRunLoop.persistStandaloneReviewCompletion(
  args: PhaseReviewPersistenceArgs,
  outputText: String,
  acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
): PhaseOutcome? {
  val run = args.run
  val iteration = args.iteration
  val observability = args.observability
  val fileManifest = args.fileManifest
  val persisted = try {
    recorder.recordCompletedPhase(
      phaseStateRequest(
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
            reviewRunId = state.recordFor(run.phaseId)?.reviewRunId,
          ),
        ),
      ),
      run.request.dbPathOverride,
    )
  } catch (error: RuntimeOwnedFactUnavailable) {
    return blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = iteration,
        reason = "Runtime-owned review settlement could not establish its persistence fact: " +
          error.message.orEmpty(),
        observability = observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      ),
    )
  }
  return if (persisted) {
    null
  } else {
    blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = iteration,
        reason = "Runtime-owned review settlement could not be persisted.",
        observability = observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      ),
    )
  }
}

internal fun FeatureTaskRuntimeRunLoop.persistGoalReviewCompletion(
  args: PhaseReviewPersistenceArgs,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
): PhaseOutcome? {
  val run = args.run
  val iteration = args.iteration
  val observability = args.observability
  val fileManifest = args.fileManifest
  val completion = goalReviewPhaseCompletionRequest(args, normalizedOutput, repairEvidence)
  val completed = runCatching {
    recorder.completeGoalReviewPhase(
      completion = completion,
      dbOverride = run.request.dbPathOverride,
    )
  }.getOrElse { error ->
    return blockAndPersistInPhase(
      phaseBlockArgs(
        run,
        iteration,
        "Goal-subtask review could not atomically persist its pass and completed phase: " +
          error.message.orEmpty(),
        observability,
        payload = BlockAndPersistPayload(fileManifest = fileManifest),
      ),
    )
  }
  return if (completed) {
    null
  } else {
    blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = iteration,
        reason = "Goal-subtask review could not atomically persist its reserved pass and completed phase.",
        observability = observability,
        payload = BlockAndPersistPayload(fileManifest = fileManifest),
      ),
    )
  }
}

internal fun FeatureTaskRuntimeRunLoop.isGoalReviewRun(run: PhaseRun): Boolean =
  run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW && isGoalContinuationRun(run.request)

// A goal-subtask review reserves its pass once in prepareGoalReviewRun, outside runPhaseAttempts, so a
// bounded in-loop re-attempt reuses that same reserved pass instead of allocating another. Schema-invalid
// output therefore earns the same fix-loop retries as every other phase: the reserved pass has no completed
// output, which is the state a resume is already contracted to re-enter rather than treat as terminal.
internal fun FeatureTaskRuntimeRunLoop.schemaInvalidAttempt(
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

internal fun FeatureTaskRuntimeRunLoop.persistPhase(args: PersistPhaseArgs) {
  val write = args.write
  val phaseState =
    phaseStateRequest(
      PhaseStateRequestArgs(
        write = write,
        extras = PhaseStateRequestExtras(
          fileManifest = args.fileManifest,
          launched = args.launched,
          reviewRunId = args.reviewRunId,
        ),
      ),
    )
  state.reserveReviewPass(phaseState.reviewPassNumber)
  recorder.recordPhaseState(
    phaseState,
    write.run.request.dbPathOverride,
  )
}

internal fun FeatureTaskRuntimeRunLoop.phaseStateRequest(
  args: PhaseStateRequestArgs,
): FeatureTaskRuntimePhaseStateRequest {
  val write = args.write
  val run = write.run
  val extras = args.extras
  val fileManifest = extras.fileManifest
  return FeatureTaskRuntimePhaseStateRequest(
    workflowId = run.request.workflowId,
    phaseId = run.phaseId,
    status = write.status,
    attemptCount = write.iteration,
    resolvedAgentId = run.resolvedAgent.resolvedAgentId,
    finished = write.finished,
    outputArtifact = write.outputArtifact,
    normalizedOutput = extras.normalizedOutput,
    repairEvidence = extras.repairEvidence,
    repositoryFingerprint = extras.repositoryFingerprint,
    fileManifestBefore = fileManifest?.before.orEmpty(),
    fileManifestAfter = fileManifest?.after.orEmpty(),
    fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
    loopId = run.reentry?.loopId,
    edgeIteration = run.reentry?.edgeIteration,
    reviewPassNumber = reviewPassNumber(run, state),
    auditScopeCriterionRefs = if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      openAuditCriterionRefs()
    } else {
      emptyList()
    },
    launchedModel = extras.launched?.modelOverride,
    launchedEffort = extras.launched?.persistedEffort,
    launchOutcomeKnown = extras.launched != null,
    reviewRunId = extras.reviewRunId,
  )
}

/**
 * The model/effort the child is actually launched with. Cursor takes model and effort merged into
 * one bracketed `--model` argument, so its [persistedEffort] is null: the merged model already
 * carries the effort, and recording it twice would let the two drift apart.
 */
internal fun FeatureTaskRuntimeRunLoop.launchedModelDirective(run: PhaseRun): LaunchedModelDirective {
  val model = run.modelDirective?.model
  val effort = run.modelDirective?.effort
  if (run.resolvedAgent.resolvedAgentId == InstallAgent.CURSOR.id && model != null && effort != null) {
    return LaunchedModelDirective("$model[effort=$effort]", effort, persistedEffort = null)
  }
  return LaunchedModelDirective(model, effort, effort)
}

internal fun FeatureTaskRuntimeRunLoop.reviewPassNumber(run: PhaseRun, state: FeatureTaskRuntimeRunState): Int? {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return null
  val durable = goalReviewStateOrNull() ?: return 1
  return resolveReviewPassNumber(
    reservedPassNumber = durable.reservedPassNumber ?: state.currentReviewPassNumber(),
    completedReviewPassCount = durable.completedPassCount,
  )
}
