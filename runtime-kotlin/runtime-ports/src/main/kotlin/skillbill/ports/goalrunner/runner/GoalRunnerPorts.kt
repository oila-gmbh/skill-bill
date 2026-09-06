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
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.goal.model.GoalSubtaskReviewPassResult
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import java.nio.file.Path

interface GoalRunnerManifestQueries {
  fun loadByIssueKey(issueKey: IssueKey, repoRoot: Path? = null): GoalRunnerManifestState?

  fun readByIssueKey(issueKey: IssueKey, repoRoot: Path? = null): GoalRunnerManifestState?

  fun readByIssueKeyIfPresent(issueKey: IssueKey, repoRoot: Path? = null): GoalRunnerManifestState?

  fun loadDurableByIssueKey(issueKey: IssueKey): GoalRunnerManifestState?

  fun controlState(parentWorkflowId: WorkflowId): GoalRunnerControlState

  fun executionLease(parentWorkflowId: WorkflowId): GoalRunnerExecutionLease?

  fun reviewMode(parentWorkflowId: WorkflowId): CodeReviewExecutionMode?

  fun reviewPolicy(parentWorkflowId: WorkflowId): GoalRunnerReviewPolicy?

  fun outOfBandAcceptances(parentWorkflowId: WorkflowId): Map<Int, GoalRunnerOutOfBandAcceptance>

  fun sharedPreplanPayloadSha256(parentWorkflowId: WorkflowId): String?
}

interface GoalRunnerManifestExecutionCommands {
  fun requestPause(parentWorkflowId: WorkflowId): GoalRunnerControlState?

  fun pauseNow(
    parentWorkflowId: WorkflowId,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean = false,
  ): GoalRunnerControlState?

  fun requestPauseByIssueKey(issueKey: IssueKey, repoRoot: Path? = null): GoalRunnerPausePersistenceResult?

  fun resume(parentWorkflowId: WorkflowId): GoalRunnerManifestState?

  fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState

  fun acquireExecutionLease(
    parentWorkflowId: WorkflowId,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String? = null,
  ): Boolean

  fun heartbeatExecutionLease(parentWorkflowId: WorkflowId, lease: GoalRunnerExecutionLease): Boolean

  fun releaseExecutionLease(parentWorkflowId: WorkflowId, ownerToken: String, generation: Long): Boolean
}

interface GoalRunnerManifestControlWrites {
  fun bindRepositoryIdentity(parentWorkflowId: WorkflowId, repositoryIdentity: String): GoalRunnerControlState

  fun persistStopAfterSubtask(parentWorkflowId: WorkflowId, subtaskId: SubtaskId): GoalRunnerControlState

  fun authorizeSubtaskLaunch(state: GoalRunnerManifestState, subtaskId: SubtaskId): GoalRunnerLaunchAuthorization

  fun authorizePlanningLaunch(parentWorkflowId: WorkflowId): AgentRunSpawnAuthorization?

  fun persistControlState(parentWorkflowId: WorkflowId, state: GoalRunnerControlState): GoalRunnerControlState

  fun persistReviewMode(parentWorkflowId: WorkflowId, mode: CodeReviewExecutionMode): CodeReviewExecutionMode

  fun persistReviewPolicy(parentWorkflowId: WorkflowId, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy

  fun persistOutOfBandAcceptance(
    parentWorkflowId: WorkflowId,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance
}

interface GoalRunnerManifestStateWrites {
  fun planningStatus(
    parentWorkflowId: WorkflowId,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
  ): GoalPlanningStatusSnapshot?

  fun save(state: GoalRunnerManifestState): GoalRunnerManifestState

  fun saveRuntimeState(state: GoalRunnerManifestState): GoalRunnerManifestState

  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
  ): GoalRunnerCompletionPersistenceResult

  fun saveHardReset(state: GoalRunnerManifestState, preservePlanning: Boolean = false): GoalRunnerManifestState

  fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
    workflowId: WorkflowId,
  ): GoalRunnerManifestState

  fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: SubtaskId,
    options: GoalRunnerScopedReplanOptions = GoalRunnerScopedReplanOptions(),
  ): GoalRunnerScopedReplanWriteResult

  fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalRunnerManifestState
}

interface GoalRunnerManifestStore :
  GoalRunnerManifestQueries,
  GoalRunnerManifestExecutionCommands,
  GoalRunnerManifestControlWrites,
  GoalRunnerManifestStateWrites

interface GoalRunnerTerminalOutcomeStore {
  fun terminalOutcome(workflowId: WorkflowId, issueKey: IssueKey, subtaskId: SubtaskId): GoalRunnerStoredOutcome?

  fun recoverAndPersistTerminalOutcome(
    workflowId: WorkflowId,
    issueKey: IssueKey,
    subtaskId: SubtaskId,
    repoRoot: Path,
  ): GoalRunnerStoredOutcome?

  @OpenBoundaryMap("Recovered missing RESULT-prefix terminal child-output map at the goal-runner workflow seam")
  fun recoverMissingResultPrefixOutput(
    workflowId: WorkflowId,
    issueKey: IssueKey,
    subtaskId: SubtaskId,
    output: Map<String, Any?>,
  ): GoalRunnerStoredOutcome?
}

interface GoalRunnerReviewOutcomeStore {
  fun goalSubtaskReviewState(workflowId: WorkflowId): GoalSubtaskReviewState?

  fun unemittedGoalReviewPasses(workflowId: WorkflowId): List<GoalSubtaskReviewPassResult>

  fun acknowledgeGoalReviewPass(workflowId: WorkflowId, passNumber: Int): Boolean
}

interface GoalRunnerWorkflowOutcomeStore :
  GoalRunnerTerminalOutcomeStore,
  GoalRunnerReviewOutcomeStore,
  GoalRunnerWorkflowProgressStore,
  GoalRunnerWorkflowLedgerWriteStore,
  GoalRunnerWorkflowOutcomeMutationStore

interface GoalRunnerAttemptLedgerStore {
  fun readAttemptLedgerSummary(issueKey: IssueKey): GoalRunnerAttemptLedgerSummary
}

fun interface GoalRunnerSubtaskLauncher {
  fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome
}

fun interface GoalPullRequestPort {
  fun open(request: GoalPullRequestRequest): GoalPullRequestResult
}
