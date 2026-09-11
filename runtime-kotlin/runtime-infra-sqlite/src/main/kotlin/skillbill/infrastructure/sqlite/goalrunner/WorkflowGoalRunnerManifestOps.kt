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
  fun loadByIssueKey(issueKey: String, repoRoot: Path?): GoalRunnerManifestState?

  fun readByIssueKey(issueKey: String, repoRoot: Path?): GoalRunnerManifestState?

  fun readByIssueKeyIfPresent(issueKey: String, repoRoot: Path?): GoalRunnerManifestState?

  fun loadDurableByIssueKey(issueKey: String): GoalRunnerManifestState?
}

internal interface GoalRunnerManifestPauseOps {
  fun requestPause(parentWorkflowId: String): GoalRunnerControlState?

  fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
  ): GoalRunnerControlState?

  fun requestPauseByIssueKey(issueKey: String, repoRoot: Path?): GoalRunnerPausePersistenceResult?

  fun resume(parentWorkflowId: String): GoalRunnerManifestState?

  fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState
}

internal interface GoalRunnerManifestExecutionLease {
  fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease?

  fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean

  fun heartbeatExecutionLease(parentWorkflowId: String, lease: GoalRunnerExecutionLease): Boolean

  fun releaseExecutionLease(parentWorkflowId: String, ownerToken: String, generation: Long): Boolean
}

internal interface GoalRunnerManifestControlCommands {
  fun controlState(parentWorkflowId: String): GoalRunnerControlState

  fun bindRepositoryIdentity(parentWorkflowId: String, repositoryIdentity: String): GoalRunnerControlState

  fun persistStopAfterSubtask(parentWorkflowId: String, subtaskId: Int): GoalRunnerControlState

  fun authorizeSubtaskLaunch(state: GoalRunnerManifestState, subtaskId: Int): GoalRunnerLaunchAuthorization

  fun authorizePlanningLaunch(parentWorkflowId: String): AgentRunSpawnAuthorization?

  fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState
}

internal interface GoalRunnerManifestPersistenceCommands {
  fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
  ): GoalPlanningStatusSnapshot?

  fun save(state: GoalRunnerManifestState): GoalRunnerManifestState

  fun saveRuntimeState(state: GoalRunnerManifestState): GoalRunnerManifestState

  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerCompletionPersistenceResult

  fun saveHardReset(state: GoalRunnerManifestState, preservePlanning: Boolean): GoalRunnerManifestState

  fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
  ): GoalRunnerManifestState

  fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
  ): GoalRunnerScopedReplanWriteResult

  fun sharedPreplanPayloadSha256(parentWorkflowId: String): String?

  fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalRunnerManifestState
}

internal interface GoalRunnerManifestReviewCommands {
  fun reviewMode(parentWorkflowId: String): CodeReviewExecutionMode?

  fun persistReviewMode(parentWorkflowId: String, mode: CodeReviewExecutionMode): CodeReviewExecutionMode

  fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy?

  fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy

  fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance>

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance
}
