package skillbill.application.featuretask

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

internal data class LaunchCaptureBeforeState(
  val beforeManifest: String,
  val beforeCommit: String,
)

internal sealed interface LaunchCaptureBeforeResult {
  data class Ready(val state: LaunchCaptureBeforeState) : LaunchCaptureBeforeResult
  data class Failed(val detail: String) : LaunchCaptureBeforeResult
}

internal sealed interface LaunchCaptureAfterResult {
  data class Ready(val manifest: FeatureTaskRuntimePhaseFileManifest) : LaunchCaptureAfterResult
  data class Failed(val detail: String) : LaunchCaptureAfterResult
}

internal fun FeatureTaskRuntimeRunLoop.captureLaunchBeforeState(run: PhaseRun): LaunchCaptureBeforeResult {
  val before = gitOperations.worktreeStatus(run.request.repoRoot)
  if (!before.ok) {
    return LaunchCaptureBeforeResult.Failed("before-file manifest: ${before.error}")
  }
  val beforeCommit = gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
  if (!beforeCommit.ok) {
    return LaunchCaptureBeforeResult.Failed("before commit")
  }
  return LaunchCaptureBeforeResult.Ready(
    LaunchCaptureBeforeState(
      beforeManifest = before.value.orEmpty(),
      beforeCommit = beforeCommit.value.orEmpty(),
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoop.launchCaptureInfraFailure(
  phaseId: String,
  detail: String,
  childNeverLaunched: Boolean,
): LaunchResult = LaunchResult.infraFailure(
  "Feature-task-runtime phase '$phaseId' could not capture its $detail",
  childNeverLaunched = childNeverLaunched,
)

internal fun FeatureTaskRuntimeRunLoop.executeSubtaskLaunch(
  run: PhaseRun,
  prepared: PreparedLaunch,
  isReviewPhase: Boolean,
  isVerifyFindingsPhase: Boolean,
): AgentRunLaunchOutcome {
  val launched = launchedModelDirective(run)
  return subtaskLauncher.launch(
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
      ),
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoop.buildLaunchFileManifest(
  run: PhaseRun,
  before: LaunchCaptureBeforeState,
): LaunchCaptureAfterResult {
  val after = gitOperations.worktreeStatus(run.request.repoRoot)
  if (!after.ok) return LaunchCaptureAfterResult.Failed("after-file manifest")
  val afterCommit = gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
  if (!afterCommit.ok) return LaunchCaptureAfterResult.Failed("after commit")
  val committedPaths = gitOperations.runtimePhaseChangedPathsBetweenCommits(
    run.request.repoRoot,
    before.beforeCommit,
    afterCommit.value.orEmpty(),
  )
  if (!committedPaths.ok) return LaunchCaptureAfterResult.Failed("committed file changes")
  return LaunchCaptureAfterResult.Ready(
    FeatureTaskRuntimePhaseFileManifest(
      before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.beforeManifest),
      after = (
        FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value) +
          FeatureTaskRuntimePhaseSafetyPolicy.lineSeparatedPaths(committedPaths.value.orEmpty())
        ).distinct().sorted(),
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoop.recordLaunchTokenUsage(
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

internal fun FeatureTaskRuntimeRunLoop.isReadOnlyLaunchPhase(phaseId: String): Pair<Boolean, Boolean> {
  val isReviewPhase = phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
  val isVerifyFindingsPhase = phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
  return isReviewPhase to isVerifyFindingsPhase
}
