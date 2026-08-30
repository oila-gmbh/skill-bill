package skillbill.application.featuretask

import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.review.model.DiffResolutionException
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.StackDetectionException
import skillbill.application.review.model.UsageValidationException
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.error.UnreadableSpecIntentProjectionError
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.prepareRuntimeOwnedReview(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
): RuntimeOwnedReviewPrep {
  val input = run.goalReviewInput
    ?: return RuntimeOwnedReviewBlocked(
      PhaseOutcome.blocked("Runtime-owned review is missing the child-owned review input."),
    )
  val iteration = state.nextIteration(run.phaseId)
  val passNumber = reviewPassNumber(run, state) ?: 1
  val pinnedMode = run.request.runInvariants.codeReviewMode
  val resolution = FeatureTaskRuntimeReviewPassSequence.resolveForPass(pinnedMode, passNumber)
  val reviewRunId = resolveReviewRunId(state.recordFor(run.phaseId), passNumber)
  persistPhase(
    PersistPhaseArgs(
      write = PhaseStateWriteArgs(
        run = run,
        iteration = iteration,
        status = STATUS_RUNNING,
        finished = false,
        outputArtifact = null,
      ),
      reviewRunId = reviewRunId,
    ),
  )
  val checkpoint = gitOperations.repositoryFingerprint(run.request.repoRoot).value
    .takeIf(String::isNotBlank)
    ?: return RuntimeOwnedReviewBlocked(
      PhaseOutcome.blocked(
        "Runtime-owned review could not resolve a repository checkpoint fingerprint.",
      ),
    )
  return RuntimeOwnedReviewReady(
    run = run,
    launch = RuntimeOwnedReviewLaunch(
      iteration = iteration,
      passNumber = passNumber,
      resolvedTier = RuntimeOwnedReviewMode.execute(resolution.resolvedTier),
      reviewRunId = reviewRunId,
      checkpoint = checkpoint,
    ),
    driverRequest = runtimeOwnedReviewDriverRequest(run, input, passNumber, pinnedMode, reviewRunId),
  )
}

private fun FeatureTaskRuntimeRunLoop.resolveReviewRunId(
  durableRecord: FeatureTaskRuntimePhaseRecord?,
  passNumber: Int,
): String = durableRecord
  ?.takeIf { (it.reviewPassNumber ?: 1) == passNumber }
  ?.reviewRunId
  ?.takeIf(String::isNotBlank)
  ?: FeatureTaskRuntimeReviewEnvelope.mintReviewRunId()

private fun FeatureTaskRuntimeRunLoop.runtimeOwnedReviewDriverRequest(
  run: PhaseRun,
  input: GoalSubtaskReviewInput,
  passNumber: Int,
  pinnedMode: CodeReviewExecutionMode,
  reviewRunId: String,
) = FeatureTaskRuntimeReviewDriverMapper.request(
  input = input,
  runInvariants = run.request.runInvariants,
  agents = FeatureTaskRuntimeReviewDriverAgents(
    agent1Id = run.resolvedAgent.resolvedAgentId,
  ),
  pass = FeatureTaskRuntimeReviewDriverPass(
    passNumber = passNumber,
    pinnedMode = pinnedMode,
    reviewRunId = reviewRunId,
  ),
  workspace = FeatureTaskRuntimeReviewDriverWorkspace(
    repoRoot = run.request.repoRoot,
    timeout = run.request.timeout,
    agentAddonSelection = run.request.agentAddonSelection,
    baselineUntrackedPaths = reviewBaselineUntrackedPaths(run),
  ),
)

internal fun FeatureTaskRuntimeRunLoop.executePreparedReviewDriver(
  prepared: RuntimeOwnedReviewReady,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome {
  val run = prepared.run
  observability.started(
    run.phaseId,
    run.resolvedAgent.resolvedAgentId,
    prepared.launch.iteration,
    run.modelDirective,
    FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
  )
  val before = gitOperations.worktreeStatus(run.request.repoRoot)
  if (!before.ok) {
    return blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = prepared.launch.iteration,
        reason = "Feature-task-runtime phase 'review' could not capture its before-file manifest: ${before.error}",
        observability = observability,
      ),
    )
  }
  return when (val attempt = invokeReviewDriver(prepared.driverRequest)) {
    is ReviewDriverFailed -> blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = prepared.launch.iteration,
        reason = attempt.reason,
        observability = observability,
        failureDisposition = attempt.disposition,
      ),
    )
    is ReviewDriverReady -> {
      val after = gitOperations.worktreeStatus(run.request.repoRoot)
      if (!after.ok) {
        return blockInPhase(
          PhaseBlockRequest(
            run = run,
            attemptCount = prepared.launch.iteration,
            reason = "Feature-task-runtime phase 'review' could not capture its after-file manifest: ${after.error}",
            observability = observability,
          ),
        )
      }
      capturePhaseContentIdentities(run.phaseId)
      settleReviewDriverResult(
        prepared,
        attempt.result,
        observability,
        FeatureTaskRuntimePhaseFileManifest(
          before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.value.orEmpty()),
          after = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value.orEmpty()),
        ),
      )
    }
  }
}

internal fun FeatureTaskRuntimeRunLoop.invokeReviewDriver(request: ParallelCodeReviewRequest): ReviewDriverAttempt =
  try {
    ReviewDriverReady(phaseGates.reviewDriver.run(request))
  } catch (error: DiffResolutionException) {
    ReviewDriverFailed(
      "Runtime-owned review could not resolve the child-owned diff: ${error.message.orEmpty()}",
    )
  } catch (error: UsageValidationException) {
    ReviewDriverFailed(
      "Runtime-owned review failed: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.RETRYABLE,
    )
  } catch (error: StackDetectionException) {
    ReviewDriverFailed(
      "Runtime-owned review failed: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.RETRYABLE,
    )
  } catch (error: ReviewContextBudgetExceededException) {
    ReviewDriverFailed(
      "Runtime-owned review exceeded a review-context budget: ${error.message.orEmpty()}",
    )
  } catch (error: UnreadableSpecIntentProjectionError) {
    ReviewDriverFailed(
      "Runtime-owned review could not read the spec intent projection: ${error.message.orEmpty()}",
    )
  } catch (error: InvalidReviewContextSchemaError) {
    ReviewDriverFailed(
      "Runtime-owned review produced an invalid review-context envelope: ${error.message.orEmpty()}",
    )
  } catch (error: RuntimeOwnedFactUnavailable) {
    ReviewDriverFailed(
      "Runtime-owned review could not establish a required persistence fact: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
    )
  } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
    ReviewDriverFailed(
      "Runtime-owned review failed: ${error::class.simpleName}: ${error.message.orEmpty()}",
      FeatureTaskRuntimeFailureDisposition.RETRYABLE,
    )
  }

internal fun FeatureTaskRuntimeRunLoop.settleReviewDriverResult(
  prepared: RuntimeOwnedReviewReady,
  result: ParallelCodeReviewResult,
  observability: FeatureTaskRuntimeRunObservability,
  fileManifest: FeatureTaskRuntimePhaseFileManifest,
): PhaseOutcome {
  val run = prepared.run
  failedReviewLaneReason(result)?.let { reason ->
    return blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = prepared.launch.iteration,
        reason = reason,
        observability = observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      ),
    )
  }
  val cycle = FeatureTaskRuntimeReviewDriverCycle.assemble(
    result = result,
    request = prepared.driverRequest,
    cycle = FeatureTaskRuntimeReviewCycleContext(
      passNumber = prepared.launch.passNumber,
      resolvedTier = prepared.launch.resolvedTier,
      repositoryFingerprint = prepared.launch.checkpoint,
      blockerDispositions = reviewBlockerDispositions(
        run,
        prepared.launch.passNumber,
        result,
        prepared.launch.reviewRunId,
        prepared.launch.resolvedTier,
      ),
    ),
  )
  return settleRuntimeOwnedReview(run, prepared.launch.iteration, cycle.outputText, observability, fileManifest)
}

internal fun FeatureTaskRuntimeRunLoop.reviewBaselineUntrackedPaths(run: PhaseRun): List<String> =
  recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
    ?.baselineUntrackedPaths
    ?.takeIf { it.isNotEmpty() }
    ?: goalContinuationRecorder.reviewState(run.request.workflowId, run.request.dbPathOverride)
      ?.baselineUntrackedPaths
      .orEmpty()

internal fun FeatureTaskRuntimeRunLoop.failedReviewLaneReason(result: ParallelCodeReviewResult): String? {
  val parent = result.lane1
  if (parent.agentId.isBlank() || parent.success) return null
  val detail = parent.failureReason?.takeIf(String::isNotBlank) ?: "lane failed"
  return "Feature-task-runtime phase 'review' $detail"
}

internal fun FeatureTaskRuntimeRunLoop.reviewBlockerDispositions(
  run: PhaseRun,
  passNumber: Int,
  result: ParallelCodeReviewResult,
  reviewRunId: String,
  resolvedTier: CodeReviewExecutionMode,
): List<GoalSubtaskBlockerDisposition> {
  if (passNumber < 2) return emptyList()
  val prior = recorder.fetchUnaddressedLedger(run.request.workflowId, run.request.dbPathOverride)
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
  val verdicts = recorder.recordedFindingVerdicts(envelope, run.request.dbPathOverride)
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

internal fun FeatureTaskRuntimeRunLoop.settleRuntimeOwnedReview(
  run: PhaseRun,
  iteration: Int,
  outputText: String,
  observability: FeatureTaskRuntimeRunObservability,
  fileManifest: FeatureTaskRuntimePhaseFileManifest,
): PhaseOutcome {
  val acceptedOutput = runCatching {
    outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
  }.getOrElse { error ->
    return blockAndPersistInPhase(
      phaseBlockArgs(
        run,
        iteration,
        "Runtime-owned review settlement did not validate: ${error.message.orEmpty()}",
        observability,
      ),
    )
  }
  retainRuntimeOwnedReviewEvidence(run, state, iteration, outputText)
  persistReviewCompletionOutcome(
    PhaseReviewCompletionOutcomeArgs(
      persistence = PhaseReviewPersistenceArgs(run, iteration, observability, fileManifest),
      normalizedOutput = acceptedOutput.normalizedOutput,
      acceptedOutput = acceptedOutput,
      outputText = outputText,
    ),
  )?.let { return it }
  return completeRuntimeOwnedReviewPhase(
    run,
    iteration,
    observability,
    acceptedOutput.normalizedOutput,
    acceptedOutput,
  )
}
