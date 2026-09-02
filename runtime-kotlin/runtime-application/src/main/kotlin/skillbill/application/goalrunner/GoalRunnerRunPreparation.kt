package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.application.goalrunner.model.GoalRunPreparation
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy

@Inject
public class GoalRunnerRunPreparation(
  private val manifestStore: GoalRunnerManifestStore,
) {
  fun prepareRun(state: GoalRunnerManifestState, request: GoalRunnerRunRequest): GoalRunPreparation {
    val persistedControl = manifestStore.bindRepositoryIdentity(
      state.parentWorkflowId,
      goalRepositoryIdentity(request.repoRoot),
      request.dbPathOverride,
    )
    stopAfterPolicyMismatch(state, request, persistedControl)?.let { return it }
    val persistedReviewPolicy = manifestStore.reviewPolicy(state.parentWorkflowId, request.dbPathOverride)
    persistedReviewPolicy?.let { policy ->
      reviewPolicyMismatch(state, request, policy)?.let { return it }
    }
    val effectiveReviewPolicy = persistEffectiveReviewPolicy(state, request, persistedReviewPolicy)
    val effectiveControl = persistEffectiveStopAfterPolicy(state, request, persistedControl)
    val preparedState = resumeForRun(state, request, effectiveControl)
    return GoalRunPreparation.Prepared(
      preparedState,
      request.copy(
        codeReviewMode = effectiveReviewPolicy.codeReviewMode,
        stopAfterSubtaskId = request.stopAfterSubtaskId ?: persistedControl.stopAfterSubtaskId,
      ),
    )
  }

  private fun stopAfterPolicyMismatch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    persistedControl: GoalRunnerControlState,
  ): GoalRunPreparation.PreparationBlocked? {
    val requested = request.stopAfterSubtaskId ?: return null
    val persisted = persistedControl.stopAfterSubtaskId ?: return null
    if (persisted == requested) return null
    return GoalRunPreparation.PreparationBlocked(
      stopped(
        StoppedReportArgs(
          issueKey = request.issueKey,
          attempted = emptyList(),
          subtaskId = state.manifest.currentSubtaskIntent.subtaskId,
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason = "Cannot change stop-after subtask policy on goal resume: parent workflow " +
            "'${state.parentWorkflowId}' is pinned to subtask $persisted.",
          workflowId = state.parentWorkflowId,
          lastResumableStep = "preplan",
        ),
      ),
    )
  }

  private fun persistEffectiveReviewPolicy(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    persistedReviewPolicy: GoalRunnerReviewPolicy?,
  ): GoalRunnerReviewPolicy {
    val requestedAgentAddonSelection = request.agentAddonSelection.persisted
    val effectiveAgentAddonSelection = requestedAgentAddonSelection
      .takeUnless { it.entries.isEmpty() }
      ?: persistedReviewPolicy?.agentAddonSelection
      ?: AgentAddonSelection()
    val effectiveReviewPolicy = effectiveGoalRunnerReviewPolicy(
      request.codeReviewMode,
      persistedReviewPolicy,
    )
    val requestedReviewPolicy = GoalRunnerReviewPolicy(
      codeReviewMode = effectiveReviewPolicy.codeReviewMode,
      agentAddonSelection = effectiveAgentAddonSelection,
    )
    return manifestStore.persistReviewPolicy(
      parentWorkflowId = state.parentWorkflowId,
      policy = requestedReviewPolicy,
      dbPathOverride = request.dbPathOverride,
    )
  }

  private fun persistEffectiveStopAfterPolicy(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    persistedControl: GoalRunnerControlState,
  ): GoalRunnerControlState = if (request.stopAfterSubtaskId != null && persistedControl.stopAfterSubtaskId == null) {
    manifestStore.persistStopAfterSubtask(
      state.parentWorkflowId,
      request.stopAfterSubtaskId,
      request.dbPathOverride,
    )
  } else {
    persistedControl
  }

  private fun resumeForRun(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    effectiveControl: GoalRunnerControlState,
  ): GoalRunnerManifestState {
    val clearsPause = effectiveControl.paused || effectiveControl.pauseRequested
    val resumedState = if (clearsPause) {
      manifestStore.resume(state.parentWorkflowId, request.dbPathOverride) ?: state
    } else {
      state
    }
    return resumedState.copy(
      controlState = if (clearsPause) {
        manifestStore.controlState(state.parentWorkflowId, request.dbPathOverride)
      } else {
        effectiveControl
      },
    )
  }

  private fun reviewPolicyMismatch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunPreparation.PreparationBlocked? {
    val requestedAgentAddonSelection = request.agentAddonSelection.persisted
    val reason = goalRunnerReviewPolicyMismatch(
      state.parentWorkflowId,
      request.codeReviewMode,
      policy,
    ) ?: if (
      requestedAgentAddonSelection.entries.isNotEmpty() &&
      policy.agentAddonSelection != requestedAgentAddonSelection
    ) {
      "Cannot change agent add-on selection on goal resume: parent workflow '${state.parentWorkflowId}' " +
        "has a different durable selection."
    } else {
      return null
    }
    return GoalRunPreparation.PreparationBlocked(
      stopped(
        StoppedReportArgs(
          issueKey = request.issueKey,
          attempted = emptyList(),
          subtaskId = 0,
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason = reason,
          workflowId = state.parentWorkflowId,
          lastResumableStep = "preplan",
        ),
      ),
    )
  }
}
