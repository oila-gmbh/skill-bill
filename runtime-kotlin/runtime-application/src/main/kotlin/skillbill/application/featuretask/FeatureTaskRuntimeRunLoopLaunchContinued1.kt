package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.time.Duration.Companion.minutes

@Inject
class FeatureTaskRuntimeRunLoopLaunchContinued1 {
  internal fun captureLaunchBeforeState(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeResult {
    val before = runLoop.gitOperations.worktreeStatus(run.request.repoRoot)
    if (!before.ok) {
      return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeResult.Failed("before-file manifest: ${before.error}")
    }
    val beforeCommit = runLoop.gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!beforeCommit.ok) {
      return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeResult.Failed("before commit")
    }
    return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeResult.Ready(
      FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeState(
        beforeManifest = before.value.orEmpty(),
        beforeCommit = beforeCommit.value.orEmpty(),
      ),
    )
  }

  internal fun launchCaptureInfraFailure(phaseId: String, detail: String, childNeverLaunched: Boolean): LaunchResult =
    LaunchResult.infraFailure(
      "Feature-task-runtime phase '$phaseId' could not capture its $detail",
      childNeverLaunched = childNeverLaunched,
    )

  internal fun executeSubtaskLaunch(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    prepared: PreparedLaunch,
    isReviewPhase: Boolean,
    isVerifyFindingsPhase: Boolean,
  ): AgentRunLaunchOutcome {
    val launched = runLoop.collaborators.outputPersistence.launchedModelDirective(run)
    return runLoop.subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = run.resolvedAgent.invokedAgentId,
        configuredAgentOverrideId = run.resolvedAgent.configuredAgentOverrideId,
        skillRunRequest = SkillRunRequest(
          issueKey = run.request.issueKey,
          repoRoot = run.request.repoRoot,
          dbPathOverride = run.request.dbPathOverride,
          timeout = run.request.timeout,
          modelOverride = launched.modelOverride,
          effortOverride = launched.effortOverride,
          compaction = run.compaction,
          promptOverride = prepared.prompt,
          readOnlyPhase = isReviewPhase || isVerifyFindingsPhase,
          progressIdleTimeout = READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes
            .takeIf { isReviewPhase || isVerifyFindingsPhase },
          activityStampSink = runLoop.activityStampWriter.sink(
            workflowId = run.request.workflowId,
            parentWorkflowId = run.request.goalContinuation?.parentWorkflowId,
            dbOverride = run.request.dbPathOverride,
          ),
        ),
      ),
    )
  }

  internal fun buildLaunchFileManifest(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    before: FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeState,
  ): FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult {
    val after = runLoop.gitOperations.worktreeStatus(run.request.repoRoot)
    if (!after.ok) return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Failed("after-file manifest")
    val afterCommit = runLoop.gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!afterCommit.ok) return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Failed("after commit")
    val committedPaths = runLoop.gitOperations.runtimePhaseChangedPathsBetweenCommits(
      run.request.repoRoot,
      before.beforeCommit,
      afterCommit.value.orEmpty(),
    )
    if (!committedPaths.ok) {
      return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Failed("committed file changes")
    }
    return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Ready(
      FeatureTaskRuntimePhaseFileManifest(
        before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.beforeManifest),
        after = (
          FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value) +
            FeatureTaskRuntimePhaseSafetyPolicy.lineSeparatedPaths(committedPaths.value.orEmpty())
          ).distinct().sorted(),
      ),
    )
  }

  internal fun recordLaunchTokenUsage(
    run: PhaseRun,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    outcome: AgentRunLaunchOutcome,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
  ) {
    if (outcome is AgentRunLaunchFacts && phaseTokenAccumulator != null) {
      val inputTokens = estimateTokens(briefing.briefingText)
      val outputTokens = estimateTokens(outcome.stdout)
      phaseTokenAccumulator[run.phaseId] = Pair(inputTokens, outputTokens)
    }
  }

  fun isReadOnlyLaunchPhase(phaseId: String): Pair<Boolean, Boolean> {
    val isReviewPhase = phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    val isVerifyFindingsPhase = phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
    return isReviewPhase to isVerifyFindingsPhase
  }
}
