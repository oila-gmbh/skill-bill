package skillbill.ports.goalrunner.runner

import skillbill.boundary.OpenBoundaryMap
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
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.runner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.workflow.goal.model.CodeReviewExecutionMode
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

interface GoalRunnerManifestLeaseOps {
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

interface GoalRunnerManifestControlOps {
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

  fun resume(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerManifestState? = null

  fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState = state
}

interface GoalRunnerManifestWriteOps {
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
}

interface GoalRunnerManifestReviewOps {
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
  GoalRunnerManifestLeaseOps,
  GoalRunnerManifestControlOps,
  GoalRunnerManifestWriteOps,
  GoalRunnerManifestReviewOps

// Terminal-outcome resolution split into a strictly read-only query and an explicit
// recover-and-persist command (CQS): the query never measures git or mutates state, so
// status/reconciliation read paths stay side-effect-free; the command is the self-heal path.
interface GoalRunnerTerminalOutcomeStore {
  // Strictly read-only terminal-outcome query: resolves the outcome from durable
  // artifacts only and never measures git or mutates state. Use this from status /
  // reconciliation read paths.
  fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerStoredOutcome?

  // Command variant of the terminal-outcome resolution: when an agent completed
  // commit_push under suppress_pr but dropped the commit SHA, this recovers it from
  // measured HEAD at [repoRoot] and durably backfills the measured completion so
  // status, reconciliation, and the subtask handoff all agree afterward. Self-heal
  // path only; pure readers must use [terminalOutcome] instead.
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
  fun goalSubtaskReviewState(workflowId: String, dbPathOverride: String? = null): GoalSubtaskReviewState? = null

  fun unemittedGoalReviewPasses(workflowId: String, dbPathOverride: String? = null): List<GoalSubtaskReviewPassResult> =
    emptyList()

  fun acknowledgeGoalReviewPass(workflowId: String, passNumber: Int, dbPathOverride: String? = null): Boolean = false
}

interface GoalRunnerWorkflowOutcomeStore :
  GoalRunnerTerminalOutcomeStore,
  GoalRunnerReviewOutcomeStore,
  GoalRunnerWorkflowProgressStore,
  GoalRunnerWorkflowLedgerWriteStore,
  GoalRunnerWorkflowOutcomeMutationStore

// SKILL-142 (AC-011): narrow read-only port for aggregated operator metrics from the attempt ledger.
// Kept separate from [GoalRunnerWorkflowOutcomeStore] to stay within the interface function-count
// budget. Default no-op so test fakes and non-FS adapters opt in only when needed.
interface GoalRunnerAttemptLedgerStore {
  fun readAttemptLedgerSummary(issueKey: String, dbPathOverride: String? = null): GoalRunnerAttemptLedgerSummary =
    GoalRunnerAttemptLedgerSummary()
}

object NoopGoalRunnerAttemptLedgerStore : GoalRunnerAttemptLedgerStore

fun interface GoalRunnerSubtaskLauncher {
  fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome
}

fun interface GoalPullRequestPort {
  fun open(request: GoalPullRequestRequest): GoalPullRequestResult
}
