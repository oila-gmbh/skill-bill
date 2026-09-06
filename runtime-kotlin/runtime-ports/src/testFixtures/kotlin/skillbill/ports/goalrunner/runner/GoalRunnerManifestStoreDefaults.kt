package skillbill.ports.goalrunner.runner

import IssueKey
import SubtaskId
import WorkflowId
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.review.context.model.CodeReviewExecutionMode
import java.nio.file.Path

abstract class GoalRunnerManifestStoreDefaults : GoalRunnerManifestStore {
  override fun readByIssueKey(issueKey: IssueKey, repoRoot: Path?): GoalRunnerManifestState? =
    loadByIssueKey(issueKey, repoRoot)

  override fun readByIssueKeyIfPresent(issueKey: IssueKey, repoRoot: Path?): GoalRunnerManifestState? =
    readByIssueKey(issueKey, repoRoot)

  override fun loadDurableByIssueKey(issueKey: IssueKey): GoalRunnerManifestState? = loadByIssueKey(issueKey, null)

  override fun requestPause(parentWorkflowId: String): GoalRunnerControlState? = null

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
  ): GoalRunnerControlState? = null

  override fun requestPauseByIssueKey(issueKey: IssueKey, repoRoot: Path?): GoalRunnerPausePersistenceResult? = null

  override fun resume(parentWorkflowId: String): GoalRunnerManifestState? = null

  override fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? = null

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState = GoalRunnerControlState()

  override fun bindRepositoryIdentity(parentWorkflowId: String, repositoryIdentity: String): GoalRunnerControlState {
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity is required." }
    return controlState(parentWorkflowId)
  }

  override fun persistStopAfterSubtask(parentWorkflowId: String, subtaskId: SubtaskId): GoalRunnerControlState =
    GoalRunnerControlState(stopAfterSubtaskId = subtaskId.value)

  override fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
  ): GoalRunnerLaunchAuthorization {
    require(subtaskId.value > 0) { "subtaskId must be positive." }
    val controls = controlState(state.parentWorkflowId)
    return GoalRunnerLaunchAuthorization(
      authorized = !controls.requiresPauseBoundary(state.manifest),
      controlState = controls,
    )
  }

  override fun authorizePlanningLaunch(parentWorkflowId: String): AgentRunSpawnAuthorization? = null

  override fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState =
    state

  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot? = null

  override fun saveRuntimeState(state: GoalRunnerManifestState): GoalRunnerManifestState = save(state)

  override fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
  ): GoalRunnerCompletionPersistenceResult = GoalRunnerCompletionPersistenceResult(
    state = saveRuntimeState(state),
    paused = false,
  )

  override fun saveHardReset(state: GoalRunnerManifestState, preservePlanning: Boolean): GoalRunnerManifestState =
    error("Goal runner manifest store must atomically persist hard reset state.")

  override fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
    workflowId: WorkflowId,
  ): GoalRunnerManifestState =
    error("Goal runner manifest store must atomically delete a selected incompatible child workflow.")

  override fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult =
    error("Goal runner manifest store must atomically persist a scoped subtask replan.")

  override fun sharedPreplanPayloadSha256(parentWorkflowId: String): String? = null

  override fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalRunnerManifestState = error("Goal runner manifest store must atomically persist new child workflow state.")

  override fun reviewMode(parentWorkflowId: String): CodeReviewExecutionMode? = null

  override fun persistReviewMode(parentWorkflowId: String, mode: CodeReviewExecutionMode): CodeReviewExecutionMode =
    mode

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? =
    reviewMode(parentWorkflowId)?.let(::GoalRunnerReviewPolicy)

  override fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy =
    GoalRunnerReviewPolicy(
      codeReviewMode = persistReviewMode(parentWorkflowId, policy.codeReviewMode),
      agentAddonSelection = policy.agentAddonSelection,
    )

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> = emptyMap()

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance =
    error("Goal runner manifest store must durably persist out-of-band subtask acceptance.")
}
