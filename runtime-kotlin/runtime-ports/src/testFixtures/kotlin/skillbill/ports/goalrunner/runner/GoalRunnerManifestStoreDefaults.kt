package skillbill.ports.goalrunner.runner

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
  override fun readByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    loadByIssueKey(issueKey, dbPathOverride, repoRoot)

  override fun readByIssueKeyIfPresent(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = readByIssueKey(issueKey, dbPathOverride, repoRoot)

  override fun loadDurableByIssueKey(issueKey: String, dbPathOverride: String?): GoalRunnerManifestState? =
    loadByIssueKey(issueKey, dbPathOverride, null)

  override fun requestPause(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState? = null

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
    dbPathOverride: String?,
  ): GoalRunnerControlState? = null

  override fun requestPauseByIssueKey(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerPausePersistenceResult? = null

  override fun resume(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerManifestState? = null

  override fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState = state

  override fun executionLease(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerExecutionLease? = null

  override fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState =
    GoalRunnerControlState()

  override fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
    dbPathOverride: String?,
  ): GoalRunnerControlState {
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity is required." }
    return controlState(parentWorkflowId, dbPathOverride)
  }

  override fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerControlState = GoalRunnerControlState(stopAfterSubtaskId = subtaskId)

  override fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerLaunchAuthorization {
    require(subtaskId > 0) { "subtaskId must be positive." }
    val controls = controlState(state.parentWorkflowId, dbPathOverride)
    return GoalRunnerLaunchAuthorization(
      authorized = !controls.requiresPauseBoundary(state.manifest),
      controlState = controls,
    )
  }

  override fun authorizePlanningLaunch(
    parentWorkflowId: String,
    dbPathOverride: String?,
  ): AgentRunSpawnAuthorization? = null

  override fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
    dbPathOverride: String?,
  ): GoalRunnerControlState = state

  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
    dbPathOverride: String?,
  ): GoalPlanningStatusSnapshot? = null

  override fun saveRuntimeState(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState =
    save(state, dbPathOverride)

  override fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerCompletionPersistenceResult = GoalRunnerCompletionPersistenceResult(
    state = saveRuntimeState(state, dbPathOverride),
    paused = false,
  )

  override fun saveHardReset(
    state: GoalRunnerManifestState,
    dbPathOverride: String?,
    preservePlanning: Boolean,
  ): GoalRunnerManifestState = error("Goal runner manifest store must atomically persist hard reset state.")

  override fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
    dbPathOverride: String?,
  ): GoalRunnerManifestState =
    error("Goal runner manifest store must atomically delete a selected incompatible child workflow.")

  override fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult =
    error("Goal runner manifest store must atomically persist a scoped subtask replan.")

  override fun sharedPreplanPayloadSha256(parentWorkflowId: String, dbPathOverride: String?): String? = null

  override fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    dbPathOverride: String?,
  ): GoalRunnerManifestState = error("Goal runner manifest store must atomically persist new child workflow state.")

  override fun reviewMode(parentWorkflowId: String, dbPathOverride: String?): CodeReviewExecutionMode? = null

  override fun persistReviewMode(
    parentWorkflowId: String,
    mode: CodeReviewExecutionMode,
    dbPathOverride: String?,
  ): CodeReviewExecutionMode = mode

  override fun reviewPolicy(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerReviewPolicy? =
    reviewMode(parentWorkflowId, dbPathOverride)?.let(::GoalRunnerReviewPolicy)

  override fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
    dbPathOverride: String?,
  ): GoalRunnerReviewPolicy = GoalRunnerReviewPolicy(
    codeReviewMode = persistReviewMode(parentWorkflowId, policy.codeReviewMode, dbPathOverride),
    agentAddonSelection = policy.agentAddonSelection,
  )

  override fun outOfBandAcceptances(
    parentWorkflowId: String,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerOutOfBandAcceptance> = emptyMap()

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
    dbPathOverride: String?,
  ): GoalRunnerOutOfBandAcceptance =
    error("Goal runner manifest store must durably persist out-of-band subtask acceptance.")
}
