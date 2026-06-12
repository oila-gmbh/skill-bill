package skillbill.application

import skillbill.application.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalSessionAccountingFields
import skillbill.goalrunner.model.GoalSessionAccountingParser
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSessionAccountingRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import java.time.Instant

/**
 * SKILL-64 Subtask 3 (AC6, AC7, AC10, AC11): isolates the durable side effects
 * for best-effort session accounting and the append-only attempt/event ledger.
 * Timestamps are minted here (the adapter/effect layer), keeping the domain
 * models effect-free. Writes are best-effort: a failure to record never fails
 * an otherwise valid goal run.
 *
 * The ledger sequence space is distinct from the goal_event and
 * goal_observability sequence spaces.
 */
internal class GoalRunnerLedgerRecorder(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val request: GoalRunnerRunRequest,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  // SKILL-64 Subtask 3 (F-D01): the durable attempt ledger and session
  // accounting are append-only across resume runs. Seed each monotonic counter
  // from the persisted max sequence for this issue so a resume continues the
  // stream instead of restarting at 0 and emitting duplicate, non-monotonic
  // sequence numbers. A fresh run (no durable entries) starts from the base.
  private val watermarks = runCatching {
    outcomeStore.ledgerSequenceWatermarks(request.issueKey, request.dbPathOverride)
  }.getOrNull()
  private var accountingSequence: Int = watermarks?.maxAccountingSequence?.let { it + 1 } ?: 0
  private var ledgerSequence: Int = watermarks?.maxLedgerSequence?.let { it + 1 } ?: 0

  fun recordAccounting(workflowId: String, subtaskId: Int, phase: String, launchOutcome: AgentRunLaunchOutcome) {
    val facts = launchOutcome as? AgentRunLaunchFacts
    val accounting = GoalSessionAccountingParser.parse(
      subtaskId = subtaskId,
      phase = phase.ifBlank { "goal_runner_supervision" },
      sequenceNumber = accountingSequence++,
      timestamp = Instant.now().toString(),
      // SKILL-64 Subtask 3 (AC6, AC7): best-effort, provider-neutral. Launch
      // facts now expose a provider-neutral child session path/id (the launcher-
      // controlled working dir + session marker). When either is determinable
      // the parser yields available=true; only when NO token data AND no session
      // path/id exist is accounting recorded unavailable. Provider-private token
      // logs are never required (Non-Goal).
      fields = GoalSessionAccountingFields(
        childSessionPath = facts?.childSessionPath,
        childSessionId = facts?.childSessionId,
        finalStatus = facts?.let { launchFinalStatus(it) },
        unavailableReason = facts?.let { "Provider session token accounting was not available from launch facts." }
          ?: "No launch facts were produced for this child run.",
      ),
    )
    runCatching {
      outcomeStore.recordSessionAccounting(
        GoalRunnerSessionAccountingRecordRequest(workflowId = workflowId, accounting = accounting),
        request.dbPathOverride,
      )
    }
      .onFailure { error ->
        logBestEffortFailure("session_accounting", workflowId, subtaskId, error)
      }
      .onSuccess { recorded ->
        if (!recorded) {
          logBestEffortMissingWorkflow("session_accounting", workflowId, subtaskId)
        }
      }
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

  // SKILL-64 Subtask 3 (F-R02): best-effort ledger/accounting writes must never
  // fail the run, but a silent gap must be detectable. Log WARNING on both a
  // thrown failure and a false return (workflow not found). The message carries
  // only workflowId/action/subtaskId — never secrets or prompt content.
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
)
