package skillbill.ports.goalrunner

import skillbill.boundary.OpenBoundaryMap
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.model.GoalRunnerSessionAccountingRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import java.nio.file.Path

interface GoalRunnerManifestStore {
  fun loadByIssueKey(
    issueKey: String,
    dbPathOverride: String? = null,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  fun save(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState
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

interface GoalRunnerWorkflowOutcomeStore : GoalRunnerTerminalOutcomeStore {
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
}

fun interface GoalRunnerSubtaskLauncher {
  fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome
}

fun interface GoalPullRequestPort {
  fun open(request: GoalPullRequestRequest): GoalPullRequestResult
}
