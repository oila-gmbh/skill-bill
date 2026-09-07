package skillbill.infrastructure.sqlite.goalrunner

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

internal interface GoalRunnerManifestLookup {
  fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState?

  fun readByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState?

  fun readByIssueKeyIfPresent(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState?

  fun loadDurableByIssueKey(issueKey: String, dbPathOverride: String?): GoalRunnerManifestState?
}

internal interface GoalRunnerManifestPauseOps {
  fun requestPause(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState?

  fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
    dbPathOverride: String?,
  ): GoalRunnerControlState?

  fun requestPauseByIssueKey(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerPausePersistenceResult?

  fun resume(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerManifestState?

  fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState
}

internal interface GoalRunnerManifestExecutionLease {
  fun executionLease(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerExecutionLease?

  fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean

  fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean

  fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean
}

internal interface GoalRunnerManifestControlCommands {
  fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState

  fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
    dbPathOverride: String?,
  ): GoalRunnerControlState

  fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerControlState

  fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerLaunchAuthorization

  fun authorizePlanningLaunch(parentWorkflowId: String, dbPathOverride: String?): AgentRunSpawnAuthorization?

  fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
    dbPathOverride: String?,
  ): GoalRunnerControlState
}

internal interface GoalRunnerManifestPersistenceCommands {
  fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
    dbPathOverride: String?,
  ): GoalPlanningStatusSnapshot?

  fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState

  fun saveRuntimeState(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState

  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerCompletionPersistenceResult

  fun saveHardReset(
    state: GoalRunnerManifestState,
    dbPathOverride: String?,
    preservePlanning: Boolean,
  ): GoalRunnerManifestState

  fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
    dbPathOverride: String?,
  ): GoalRunnerManifestState

  fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult

  fun sharedPreplanPayloadSha256(parentWorkflowId: String, dbPathOverride: String?): String?

  fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    dbPathOverride: String?,
  ): GoalRunnerManifestState
}

internal interface GoalRunnerManifestReviewCommands {
  fun reviewMode(parentWorkflowId: String, dbPathOverride: String?): CodeReviewExecutionMode?

  fun persistReviewMode(
    parentWorkflowId: String,
    mode: CodeReviewExecutionMode,
    dbPathOverride: String?,
  ): CodeReviewExecutionMode

  fun reviewPolicy(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerReviewPolicy?

  fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
    dbPathOverride: String?,
  ): GoalRunnerReviewPolicy

  fun outOfBandAcceptances(parentWorkflowId: String, dbPathOverride: String?): Map<Int, GoalRunnerOutOfBandAcceptance>

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
    dbPathOverride: String?,
  ): GoalRunnerOutOfBandAcceptance
}
