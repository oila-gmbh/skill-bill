package skillbill.ports.goalrunner

import skillbill.boundary.OpenBoundaryMap
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.ports.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerPausePersistenceResult
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.ports.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.ports.goalrunner.model.GoalRunnerSessionAccountingRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
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

  fun loadDurableByIssueKey(issueKey: String, dbPathOverride: String? = null): GoalRunnerManifestState? =
    loadByIssueKey(issueKey, dbPathOverride, null)
}

@Suppress("TooManyFunctions") // single cohesive boundary: manifest reads, saves, review policy, and acceptance
interface GoalRunnerManifestStore : GoalRunnerManifestLookup {
  fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
    dbPathOverride: String? = null,
  ): skillbill.goalrunner.model.GoalPlanningStatusSnapshot? = null

  fun save(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState

  fun saveRuntimeState(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState =
    save(state, dbPathOverride)

  fun controlState(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerControlState =
    GoalRunnerControlState()

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

  /**
   * Flip the goal to paused in exactly one durable write, with no status inspection or child
   * supervision. When [overwriteExistingReason] is false an already-paused record is returned
   * untouched, which is how the shutdown hook defers to a reason the stop verb already wrote.
   */
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

  /**
   * Atomically authorize the next child launch against the durable parent controls. The decision
   * is the launch boundary: a pause request committed before this transaction denies the launch;
   * a request committed after it is observed at the next parent boundary.
   */
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

  /** Atomically authorize a planning-agent launch against the durable parent pause boundary. */
  fun authorizePlanningLaunch(parentWorkflowId: String, dbPathOverride: String? = null): AgentRunSpawnAuthorization? =
    null

  fun resume(parentWorkflowId: String, dbPathOverride: String? = null): GoalRunnerManifestState? = null

  /** Persist terminal child completion and the parent pause boundary in one transaction. */
  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerCompletionPersistenceResult = GoalRunnerCompletionPersistenceResult(
    state = saveRuntimeState(state, dbPathOverride),
    paused = false,
  )

  fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState = state

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

  /**
   * Atomically deletes one `goal_subtask_plans` row and persists the retargeted parent manifest.
   * Does not delete child workflow rows or mutate subtask runtime fields.
   * When [options.includeSharedPreplan] is true, also digest-conditionally discards the shared
   * preplan and every sibling plan row in the same transaction (cascade-all provenance resolution).
   */
  fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String? = null,
    options: GoalRunnerScopedReplanOptions = GoalRunnerScopedReplanOptions(),
  ): GoalRunnerScopedReplanWriteResult =
    error("Goal runner manifest store must atomically persist a scoped subtask replan.")

  /** Stored shared-preplan payload digest, or null when absent. */
  fun sharedPreplanPayloadSha256(parentWorkflowId: String, dbPathOverride: String? = null): String? = null

  fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    dbPathOverride: String? = null,
  ): GoalRunnerManifestState = error("Goal runner manifest store must atomically persist new child workflow state.")

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
    parallelReviewAgent = policy.parallelReviewAgent,
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

@Suppress("TooManyFunctions") // single cohesive outcome boundary: terminal, review, ledger, and loop-phase reads
interface GoalRunnerWorkflowOutcomeStore : GoalRunnerTerminalOutcomeStore, GoalRunnerReviewOutcomeStore {

  fun authoritativeOutcomes(issueKey: String, dbPathOverride: String? = null): Map<Int, GoalRunnerStoredOutcome> =
    emptyMap()

  // [repoRoot] is the manifest-workflowId-independent self-heal seam (SKILL-68): when supplied, a
  // complete-without-SHA continuation child recovers its commit SHA from measured HEAD and is
  // durably backfilled. null keeps the read-only, no-measure behavior for pure status/read callers.
  // [gate] carries the reconciliation-policy knobs (see [GoalRunnerReconcileGate]); SKILL-87's
  // requireStalenessEvidence lives there so finalize cannot false-kill a still-running subtask.
  fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String> = emptySet(),
    gate: GoalRunnerReconcileGate = GoalRunnerReconcileGate(),
    repoRoot: Path? = null,
    dbPathOverride: String? = null,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent? = null,
    dbPathOverride: String? = null,
  ): String?

  fun progress(workflowId: String, dbPathOverride: String? = null): GoalRunnerWorkflowProgress?

  fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest, dbPathOverride: String? = null): Boolean

  // SKILL-64 Subtask 3 (AC21, AC25): durable declared-progress write seam.
  fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest, dbPathOverride: String? = null): Boolean

  /**
   * Read side of [recordProgressEvent], sequence-ordered oldest first. Returns the retained window
   * only; the store prunes by the same bounded-retention rule the write seam applies.
   */
  @OpenBoundaryMap("Durable goal progress-event artifact maps read back at the goal-runner workflow seam")
  fun progressEvents(workflowId: String, dbPathOverride: String? = null): List<Map<String, Any?>> = emptyList()

  // SKILL-64 Subtask 3 (AC6, AC7): best-effort child-session accounting write.
  fun recordSessionAccounting(
    request: GoalRunnerSessionAccountingRecordRequest,
    dbPathOverride: String? = null,
  ): Boolean

  // SKILL-64 Subtask 3 (AC10, AC11): append-only attempt/event ledger write.
  fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest, dbPathOverride: String? = null): Boolean

  fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String? = null,
  ): Boolean

  // SKILL-64 Subtask 3 (F-D01): highest persisted sequence numbers for the
  // append-only attempt ledger and best-effort session accounting across all
  // continuation children of an issue. The goal-runner ledger recorder seeds
  // its monotonic counters from these so a resume run does not restart at 0 and
  // emit duplicate, non-monotonic sequences into the append-only ledger.
  fun ledgerSequenceWatermarks(issueKey: String, dbPathOverride: String? = null): GoalRunnerLedgerSequenceWatermarks

  // SKILL-142 (AC-008): loop iteration counts aggregated from the child workflow's durable phase
  // records. Returns a map of loopId → max edgeIteration observed across all phase records that
  // carry a backward-edge context. Used by the parent ledger recorder to account for edges
  // completed and edges still in progress within a single child run, beyond the stop-position
  // inference that only catches loop-only-phase stops.
  fun childWorkflowLoopIterations(workflowId: String, dbPathOverride: String? = null): Map<String, Int> = emptyMap()

  /**
   * Operator goal resume: reopen a durably blocked child phase so the next launch continues instead
   * of re-surfacing `needs_user_action`. Idempotent when the preferred (or any) phase is not blocked.
   * Returns false only when the child workflow is missing or already terminal.
   */
  fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    dbPathOverride: String? = null,
  ): Boolean
}

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
