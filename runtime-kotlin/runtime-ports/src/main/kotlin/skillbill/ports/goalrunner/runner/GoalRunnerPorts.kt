package skillbill.ports.goalrunner.runner

import skillbill.boundary.OpenBoundaryMap
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GoalSubtaskReviewPassResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import java.nio.file.Path

interface GoalRunnerManifestLookup {
  fun loadByIssueKey(
    issueKey: String,
    dbPathOverride: String? = null,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  fun readByIssueKey(
    issueKey: String,
    dbPathOverride: String? = null,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  fun readByIssueKeyIfPresent(
    issueKey: String,
    dbPathOverride: String? = null,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  fun loadDurableByIssueKey(issueKey: String, dbPathOverride: String? = null): GoalRunnerManifestState?
}

interface GoalRunnerManifestPauseOps {
  fun requestPause(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerControlState?

  fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean = false,
    dbPathOverride: String? = null,
  ): GoalRunnerControlState?

  fun requestPauseByIssueKey(
    issueKey: String,
    dbPathOverride: String? = null,
    repoRoot: Path? = null,
  ): GoalRunnerPausePersistenceResult?

  fun resume(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerManifestState?

  fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState
}

interface GoalRunnerManifestExecutionLease {
  fun executionLease(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerExecutionLease?

  fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String? = null,
    dbPathOverride: String? = null,
  ): Boolean

  fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String? = null,
  ): Boolean

  fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String? = null,
  ): Boolean
}

interface GoalRunnerManifestControlCommands {
  fun controlState(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerControlState

  fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
    dbPathOverride: String? = null,
  ): GoalRunnerControlState

  fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerControlState

  fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerLaunchAuthorization

  fun authorizePlanningLaunch(parentWorkflowId: String, dbPathOverride: String? = null): AgentRunSpawnAuthorization?

  fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
    dbPathOverride: String? = null,
  ): GoalRunnerControlState
}

interface GoalRunnerManifestPersistenceCommands {
  fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
    dbPathOverride: String? = null,
  ): GoalPlanningStatusSnapshot?

  fun save(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState

  fun saveRuntimeState(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState

  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerCompletionPersistenceResult

  fun saveHardReset(
    state: GoalRunnerManifestState,
    dbPathOverride: String? = null,
    preservePlanning: Boolean = false,
  ): GoalRunnerManifestState

  fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
    dbPathOverride: String? = null,
  ): GoalRunnerManifestState

  fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String? = null,
    options: GoalRunnerScopedReplanOptions = GoalRunnerScopedReplanOptions(),
  ): GoalRunnerScopedReplanWriteResult

  fun sharedPreplanPayloadSha256(parentWorkflowId: String, dbPathOverride: String? = null): String?

  fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    dbPathOverride: String? = null,
  ): GoalRunnerManifestState
}

interface GoalRunnerManifestReviewCommands {
  fun reviewMode(parentWorkflowId: String, dbPathOverride: String? = null): CodeReviewExecutionMode?

  fun persistReviewMode(
    parentWorkflowId: String,
    mode: CodeReviewExecutionMode,
    dbPathOverride: String? = null,
  ): CodeReviewExecutionMode

  fun reviewPolicy(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerReviewPolicy?

  fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
    dbPathOverride: String? = null,
  ): GoalRunnerReviewPolicy

  fun outOfBandAcceptances(
    parentWorkflowId: String,
    dbPathOverride: String? = null,
  ): Map<Int, GoalRunnerOutOfBandAcceptance>

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
    dbPathOverride: String? = null,
  ): GoalRunnerOutOfBandAcceptance
}

interface GoalRunnerManifestStore :
  GoalRunnerManifestLookup,
  GoalRunnerManifestPauseOps,
  GoalRunnerManifestExecutionLease,
  GoalRunnerManifestControlCommands,
  GoalRunnerManifestPersistenceCommands,
  GoalRunnerManifestReviewCommands

interface GoalRunnerTerminalOutcomeStore {
  fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerStoredOutcome?

  fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
    dbPathOverride: String? = null,
  ): GoalRunnerStoredOutcome?

  @OpenBoundaryMap("Recovered missing RESULT-prefix terminal child-output map at the goal-runner workflow seam")
  fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: Map<String, Any?>,
    dbPathOverride: String? = null,
  ): GoalRunnerStoredOutcome?
}

interface GoalRunnerReviewOutcomeStore {
  fun goalSubtaskReviewState(workflowId: String, dbPathOverride: String? = null): GoalSubtaskReviewState?

  fun unemittedGoalReviewPasses(workflowId: String, dbPathOverride: String? = null): List<GoalSubtaskReviewPassResult>

  fun acknowledgeGoalReviewPass(workflowId: String, passNumber: Int, dbPathOverride: String? = null): Boolean
}

interface GoalRunnerWorkflowOutcomeStore :
  GoalRunnerTerminalOutcomeStore,
  GoalRunnerReviewOutcomeStore,
  GoalRunnerWorkflowProgressStore,
  GoalRunnerWorkflowLedgerWriteStore,
  GoalRunnerWorkflowOutcomeMutationStore

interface GoalRunnerAttemptLedgerStore {
  fun readAttemptLedgerSummary(issueKey: String, dbPathOverride: String? = null): GoalRunnerAttemptLedgerSummary
}

fun interface GoalRunnerSubtaskLauncher {
  fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome
}

fun interface GoalPullRequestPort {
  fun open(request: GoalPullRequestRequest): GoalPullRequestResult
}
