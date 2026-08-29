package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import java.time.Instant

/**
 * SKILL-64 Subtask 3 (AC10, AC11): isolates the durable side effects for the
 * append-only attempt/event ledger. Timestamps are minted here (the
 * adapter/effect layer), keeping the domain models effect-free. Writes are
 * best-effort: a failure to record never fails an otherwise valid goal run.
 *
 * The ledger sequence space is distinct from the goal_event and
 * goal_observability sequence spaces.
 */
internal class GoalRunnerLedgerRecorder(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val request: GoalRunnerRunRequest,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  // SKILL-64 Subtask 3 (F-D01): the durable attempt ledger is append-only
  // across resume runs. Seed its monotonic counter from the persisted max
  // sequence for this issue so a resume continues the stream instead of
  // restarting at 0 and emitting duplicate, non-monotonic sequence numbers. A
  // fresh run (no durable entries) starts from the base.
  private val watermarks = runCatching {
    outcomeStore.ledgerSequenceWatermarks(request.issueKey, request.dbPathOverride)
  }.getOrNull()
  private var ledgerSequence: Int = watermarks?.maxLedgerSequence?.let { it + 1 } ?: 0

  // Cumulative backward-edge counts keyed by "subtaskId:loopId". Seeded from persisted watermarks
  // so a resume continues each loop's count rather than restarting from 0.
  private val cumulativeBackwardEdgeCounts: MutableMap<String, Int> =
    watermarks?.backwardEdgeCounts?.toMutableMap() ?: mutableMapOf()

  fun recordBackwardEdgeEntry(edge: GoalRunnerBackwardEdge) {
    val key = "${edge.subtaskId}:${edge.loopId}"
    val newCount = (cumulativeBackwardEdgeCounts[key] ?: 0) + edge.edgeIteration.coerceAtLeast(1)
    cumulativeBackwardEdgeCounts[key] = newCount
    recordLedgerEntry(
      GoalRunnerLedgerContext(
        workflowId = edge.workflowId,
        action = GoalAttemptLedgerAction.BACKWARD_EDGE_ENTRY,
        issueKey = edge.issueKey,
        subtaskId = edge.subtaskId,
        progress = edge.progress,
        loopId = edge.loopId,
        cumulativeLoopCount = newCount,
      ),
    )
  }

  fun recordLedgerEntry(context: GoalRunnerLedgerContext) {
    val targetWorkflowId = context.workflowId?.takeIf(String::isNotBlank) ?: return
    val facts = context.launchOutcome as? AgentRunLaunchFacts
    val entry = GoalAttemptLedgerEntry(
      action = context.action,
      sequenceNumber = ledgerSequence++,
      timestamp = Instant.now().toString(),
      issueKey = context.issueKey.takeIf(String::isNotBlank),
      subtaskId = context.subtaskId.takeIf { it > 0 },
      previousWorkflowId = targetWorkflowId,
      previousStatus = context.progress?.workflowStatus,
      previousStep = context.progress?.currentStepId,
      blockedReason = context.blockedReason?.takeIf(String::isNotBlank),
      latestLiveness = context.progress?.latestLivenessSignal,
      launchOutcome = facts?.let { launchFinalStatus(it) },
      timedOut = facts?.timedOut,
      interrupted = facts?.interrupted,
      // SKILL-64 Subtask 3 (AC11): carry the provider-neutral child session
      // path/id from launch facts instead of the prior hardcoded nulls.
      childSessionPath = facts?.childSessionPath,
      childSessionId = facts?.childSessionId,
      finalReconciledResult = context.finalReconciledResult?.takeIf(String::isNotBlank),
      stopReason = context.stopReason?.takeIf(String::isNotBlank),
      diagnosticClass = context.diagnosticClass?.takeIf(String::isNotBlank),
      currentStep = context.progress?.currentStepId?.takeIf(String::isNotBlank),
      exitStatus = facts?.exitStatus,
      recoverableJsonPresent = context.recoverableJsonPresent,
      nextSafeAction = context.nextSafeAction?.takeIf(String::isNotBlank),
      loopId = context.loopId?.takeIf(String::isNotBlank),
      cumulativeLoopCount = context.cumulativeLoopCount,
      attemptDurationMillis = context.attemptDurationMillis,
      causingLoopEntry = context.causingLoopEntry?.takeIf(String::isNotBlank),
      reAttemptCause = context.reAttemptCause?.takeIf(String::isNotBlank),
      findingsInScope = context.findingsInScope,
    )
    runCatching {
      outcomeStore.recordAttemptLedgerEntry(
        GoalRunnerAttemptLedgerRecordRequest(workflowId = targetWorkflowId, entry = entry),
        request.dbPathOverride,
      )
    }
      .onFailure { error ->
        logBestEffortFailure("attempt_ledger:${context.action.wireValue}", targetWorkflowId, context.subtaskId, error)
      }
      .onSuccess { recorded ->
        if (!recorded) {
          logBestEffortMissingWorkflow(
            "attempt_ledger:${context.action.wireValue}",
            targetWorkflowId,
            context.subtaskId,
          )
        }
      }
  }

  // SKILL-64 Subtask 3 (F-R02): best-effort ledger writes must never fail the
  // run, but a silent gap must be detectable. Log WARNING on both a thrown
  // failure and a false return (workflow not found). The message carries only
  // workflowId/action/subtaskId — never secrets or prompt content.
  private fun logBestEffortFailure(action: String, workflowId: String, subtaskId: Int, error: Throwable) {
    diagnostics.warning(
      "Best-effort goal ledger write failed: action='$action' workflowId='$workflowId' subtaskId=$subtaskId " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
  }

  private fun logBestEffortMissingWorkflow(action: String, workflowId: String, subtaskId: Int) {
    diagnostics.warning(
      "Best-effort goal ledger write skipped (workflow not found): action='$action' " +
        "workflowId='$workflowId' subtaskId=$subtaskId",
    )
  }

  private fun launchFinalStatus(facts: AgentRunLaunchFacts): String = when {
    facts.spawnFailed -> "spawn_failed"
    facts.timedOut -> "timed_out"
    facts.interrupted -> "interrupted"
    facts.exitStatus == 0 -> "exited_ok"
    else -> "exited_${facts.exitStatus ?: "unknown"}"
  }
}

internal data class GoalRunnerBackwardEdge(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val loopId: String,
  val edgeIteration: Int,
  val progress: GoalRunnerWorkflowProgress?,
)

internal data class GoalRunnerLedgerContext(
  val workflowId: String?,
  val action: GoalAttemptLedgerAction,
  val issueKey: String,
  val subtaskId: Int,
  val progress: GoalRunnerWorkflowProgress? = null,
  val launchOutcome: AgentRunLaunchOutcome? = null,
  val blockedReason: String? = null,
  val finalReconciledResult: String? = null,
  val stopReason: String? = null,
  val diagnosticClass: String? = null,
  val recoverableJsonPresent: Boolean? = null,
  val nextSafeAction: String? = null,
  val loopId: String? = null,
  val cumulativeLoopCount: Int? = null,
  val attemptDurationMillis: Long? = null,
  val causingLoopEntry: String? = null,
  val reAttemptCause: String? = null,
  val findingsInScope: Int? = null,
)
