package skillbill.ports.goalrunner.runner

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerSummary
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOrphanChildReplacementWrite
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
  ): GoalRunnerManifestState? = loadByIssueKey(issueKey, dbPathOverride, repoRoot)

  fun readByIssueKeyIfPresent(
    issueKey: String,
    dbPathOverride: String? = null,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState? = readByIssueKey(issueKey, dbPathOverride, repoRoot)

  fun loadDurableByIssueKey(issueKey: String, dbPathOverride: String? = null): GoalRunnerManifestState? =
    loadByIssueKey(issueKey, dbPathOverride, null)
}

interface GoalRunnerManifestPauseOps {
  fun requestPause(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerControlState? = null

  fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean = false,
    dbPathOverride: String? = null,
  ): GoalRunnerControlState? = null

  fun requestPauseByIssueKey(
    issueKey: String,
    dbPathOverride: String? = null,
    repoRoot: Path? = null,
  ): GoalRunnerPausePersistenceResult? = null

  fun resume(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerManifestState? = null

  fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState = state
}

interface GoalRunnerManifestExecutionLease {
  fun executionLease(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerExecutionLease? = null

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
  fun controlState(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerControlState =
    GoalRunnerControlState()

  fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
    dbPathOverride: String? = null,
  ): GoalRunnerControlState {
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity is required." }
    return controlState(parentWorkflowId, dbPathOverride)
  }

  fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerControlState = GoalRunnerControlState(stopAfterSubtaskId = subtaskId)

  fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerLaunchAuthorization {
    require(subtaskId > 0) { "subtaskId must be positive." }
    val controls = controlState(state.parentWorkflowId, dbPathOverride)
    return GoalRunnerLaunchAuthorization(
      authorized = !controls.requiresPauseBoundary(state.manifest),
      controlState = controls,
    )
  }

  fun authorizePlanningLaunch(parentWorkflowId: String, dbPathOverride: String? = null): AgentRunSpawnAuthorization? =
    null

  fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
    dbPathOverride: String? = null,
  ): GoalRunnerControlState = state
}

interface GoalRunnerManifestPersistenceCommands {
  fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
    dbPathOverride: String? = null,
  ): GoalPlanningStatusSnapshot? = null

  fun save(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState

  fun saveRuntimeState(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState =
    save(state, dbPathOverride)

  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerCompletionPersistenceResult = GoalRunnerCompletionPersistenceResult(
    state = saveRuntimeState(state, dbPathOverride),
    paused = false,
  )

  fun saveHardReset(
    state: GoalRunnerManifestState,
    dbPathOverride: String? = null,
    preservePlanning: Boolean = false,
  ): GoalRunnerManifestState = error("Goal runner manifest store must atomically persist hard reset state.")

  fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
    dbPathOverride: String? = null,
  ): GoalRunnerManifestState =
    error("Goal runner manifest store must atomically delete a selected incompatible child workflow.")

  fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String? = null,
    options: GoalRunnerScopedReplanOptions = GoalRunnerScopedReplanOptions(),
  ): GoalRunnerScopedReplanWriteResult =
    error("Goal runner manifest store must atomically persist a scoped subtask replan.")

  fun sharedPreplanPayloadSha256(parentWorkflowId: String, dbPathOverride: String? = null): String? = null

  fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    dbPathOverride: String? = null,
  ): GoalRunnerManifestState = error("Goal runner manifest store must atomically persist new child workflow state.")

  fun replaceOrphanChildWorkflow(write: GoalRunnerOrphanChildReplacementWrite): GoalRunnerManifestState =
    error("Goal runner manifest store must atomically replace an orphan child workflow.")
}

interface GoalRunnerManifestReviewCommands {
  fun reviewMode(parentWorkflowId: String, dbPathOverride: String? = null): CodeReviewExecutionMode? = null

  fun persistReviewMode(
    parentWorkflowId: String,
    mode: CodeReviewExecutionMode,
    dbPathOverride: String? = null,
  ): CodeReviewExecutionMode = mode

  fun reviewPolicy(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerReviewPolicy? =
    reviewMode(parentWorkflowId, dbPathOverride)?.let(::GoalRunnerReviewPolicy)

  fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
    dbPathOverride: String? = null,
  ): GoalRunnerReviewPolicy = GoalRunnerReviewPolicy(
    codeReviewMode = persistReviewMode(parentWorkflowId, policy.codeReviewMode, dbPathOverride),
    agentAddonSelection = policy.agentAddonSelection,
  )

  fun outOfBandAcceptances(
    parentWorkflowId: String,
    dbPathOverride: String? = null,
  ): Map<Int, GoalRunnerOutOfBandAcceptance> = emptyMap()

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
    dbPathOverride: String? = null,
  ): GoalRunnerOutOfBandAcceptance =
    error("Goal runner manifest store must durably persist out-of-band subtask acceptance.")
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

object NoopGoalRunnerAttemptLedgerStore : GoalRunnerAttemptLedgerStore {
  override fun readAttemptLedgerSummary(issueKey: String, dbPathOverride: String?): GoalRunnerAttemptLedgerSummary {
    RecordingNullObjectDiagnostics.recordSwallow(
      "NoopGoalRunnerAttemptLedgerStore",
      "readAttemptLedgerSummary(issueKey=$issueKey)",
    )
    return GoalRunnerAttemptLedgerSummary()
  }
}

fun interface GoalRunnerSubtaskLauncher {
  fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome
}

fun interface GoalPullRequestPort {
  fun open(request: GoalPullRequestRequest): GoalPullRequestResult
}
