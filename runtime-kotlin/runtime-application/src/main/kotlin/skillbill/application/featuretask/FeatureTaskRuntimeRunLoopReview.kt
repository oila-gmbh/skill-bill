package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.StackDetectionException
import skillbill.application.review.model.UsageValidationException
import skillbill.application.reviewevidence.model.DiffResolutionException
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.error.UnreadableSpecIntentProjectionError
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import kotlin.coroutines.cancellation.CancellationException

@Inject
class FeatureTaskRuntimeRunLoopReview {
  internal fun prepareRuntimeOwnedReview(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
  ): RuntimeOwnedReviewPrep {
    val input = run.goalReviewInput
      ?: return RuntimeOwnedReviewBlocked(
        PhaseOutcome.blocked("Runtime-owned review is missing the child-owned review input."),
      )
    val iteration = state.nextIteration(run.phaseId)
    val passNumber = runLoop.collaborators.outputPersistence.reviewPassNumber(runLoop, run, state) ?: 1
    val pinnedMode = run.request.runInvariants.codeReviewMode
    val resolution = FeatureTaskRuntimeReviewPassSequence.resolveForPass(pinnedMode, passNumber)
    val reviewRunId = resolveReviewRunId(runLoop, state.recordFor(run.phaseId), passNumber)
    runLoop.collaborators.outputPersistence.persistPhase(
      runLoop,
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
    val checkpoint = runLoop.gitOperations.repositoryFingerprint(run.request.repoRoot).value
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
      driverRequest = runtimeOwnedReviewDriverRequest(
        runLoop,
        RuntimeOwnedReviewDriverRequestArgs(run, input, passNumber, pinnedMode, reviewRunId),
      ),
    )
  }

  private fun FeatureTaskRuntimeRunLoopReview.resolveReviewRunId(
    runLoop: FeatureTaskRuntimeRunLoop,
    durableRecord: FeatureTaskRuntimePhaseRecord?,
    passNumber: Int,
  ): String = durableRecord
    ?.takeIf { (it.reviewPassNumber ?: 1) == passNumber }
    ?.reviewRunId
    ?.takeIf(String::isNotBlank)
    ?: FeatureTaskRuntimeReviewEnvelope.mintReviewRunId(runLoop.clock)

  private fun FeatureTaskRuntimeRunLoopReview.runtimeOwnedReviewDriverRequest(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: RuntimeOwnedReviewDriverRequestArgs,
  ) = FeatureTaskRuntimeReviewDriverMapper.request(
    input = args.input,
    runInvariants = args.run.request.runInvariants,
    agents = FeatureTaskRuntimeReviewDriverAgents(
      agent1Id = args.run.resolvedAgent.resolvedAgentId,
    ),
    pass = FeatureTaskRuntimeReviewDriverPass(
      passNumber = args.passNumber,
      pinnedMode = args.pinnedMode,
      reviewRunId = args.reviewRunId,
    ),
    workspace = FeatureTaskRuntimeReviewDriverWorkspace(
      repoRoot = args.run.request.repoRoot,
      timeout = args.run.request.timeout,
      agentAddonSelection = args.run.request.agentAddonSelection,
      baselineUntrackedPaths = reviewBaselineUntrackedPaths(runLoop, args.run),
    ),
  ).copy(
    activityWorkflowId = args.run.request.workflowId,
    activityParentWorkflowId = args.run.request.goalContinuation?.parentWorkflowId,
  )

  internal fun executePreparedReviewDriver(
    runLoop: FeatureTaskRuntimeRunLoop,
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
    val before = runLoop.gitOperations.worktreeStatus(run.request.repoRoot)
    if (!before.ok) {
      return runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = prepared.launch.iteration,
          reason = "Feature-task-runtime phase 'review' could not capture its before-file manifest: ${before.error}",
          observability = observability,
        ),
      )
    }
    return when (val attempt = invokeReviewDriver(runLoop, prepared.driverRequest)) {
      is ReviewDriverFailed -> runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = prepared.launch.iteration,
          reason = attempt.reason,
          observability = observability,
          failureDisposition = attempt.disposition,
        ),
      )
      is ReviewDriverReady -> {
        val after = runLoop.gitOperations.worktreeStatus(run.request.repoRoot)
        if (!after.ok) {
          return runLoop.collaborators.phaseAttempts.blockInPhase(
            runLoop,
            PhaseBlockRequest(
              run = run,
              attemptCount = prepared.launch.iteration,
              reason = "Feature-task-runtime phase 'review' could not capture its after-file manifest: ${after.error}",
              observability = observability,
            ),
          )
        }
        runLoop.collaborators.launch.capturePhaseContentIdentities(runLoop, run.phaseId)
        settleReviewDriverResult(
          runLoop,
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

  internal fun invokeReviewDriver(
    runLoop: FeatureTaskRuntimeRunLoop,
    request: ParallelCodeReviewRequest,
  ): ReviewDriverAttempt {
    val outcome = runCatching { runLoop.phaseGates.reviewDriver.run(request) }
    val error = outcome.exceptionOrNull()
    if (error == null) {
      return ReviewDriverReady(outcome.getOrThrow())
    }
    val mapped: ReviewDriverAttempt? = when (error) {
      is CancellationException -> null
      is DiffResolutionException -> ReviewDriverFailed(
        "Runtime-owned review could not resolve the child-owned diff: ${error.message.orEmpty()}",
      )
      is UsageValidationException -> ReviewDriverFailed(
        "Runtime-owned review failed: ${error.message.orEmpty()}",
        FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      )
      is StackDetectionException -> ReviewDriverFailed(
        "Runtime-owned review failed: ${error.message.orEmpty()}",
        FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      )
      is ReviewContextBudgetExceededException -> ReviewDriverFailed(
        "Runtime-owned review exceeded a review-context budget: ${error.message.orEmpty()}",
      )
      is UnreadableSpecIntentProjectionError -> ReviewDriverFailed(
        "Runtime-owned review could not read the spec intent projection: ${error.message.orEmpty()}",
      )
      is InvalidReviewContextSchemaError -> ReviewDriverFailed(
        "Runtime-owned review produced an invalid review-context envelope: ${error.message.orEmpty()}",
      )
      is RuntimeOwnedFactUnavailable -> ReviewDriverFailed(
        "Runtime-owned review could not establish a required persistence fact: ${error.message.orEmpty()}",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
      is Exception -> ReviewDriverFailed(
        "Runtime-owned review failed: ${error::class.simpleName}: ${error.message.orEmpty()}",
        FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      )
      else -> null
    }
    return mapped ?: throw error
  }

  internal fun settleReviewDriverResult(
    runLoop: FeatureTaskRuntimeRunLoop,
    prepared: RuntimeOwnedReviewReady,
    result: ParallelCodeReviewResult,
    observability: FeatureTaskRuntimeRunObservability,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): PhaseOutcome {
    val run = prepared.run
    failedReviewLaneReason(result)?.let { reason ->
      return runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
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
          runLoop,
          ReviewBlockerDispositionsArgs(
            run = run,
            passNumber = prepared.launch.passNumber,
            result = result,
            reviewRunId = prepared.launch.reviewRunId,
            resolvedTier = prepared.launch.resolvedTier,
          ),
        ),
      ),
    )
    return settleRuntimeOwnedReview(
      runLoop,
      SettleRuntimeOwnedReviewArgs(run, prepared.launch.iteration, cycle.outputText, observability, fileManifest),
    )
  }
}
