package skillbill.application

import skillbill.application.model.GoalRunnerRunRequest
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.workflow.model.GoalProgressEvent
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger

/**
 * SKILL-64 Subtask 3 (AC21, AC25, AC20, AC22, AC23): the concrete production
 * declared-progress emitter wired into the supervisor-side process-lifecycle
 * wrapper. The pure [AgentRunProgressEmitter] port is called by the process
 * loop with effect-free emissions; this adapter is the effect layer that:
 *
 *  - resolves the child workflow id mid-run via the shared tick reader (the
 *    same per-tick read the legacy/declared probes use, F-PF01),
 *  - mints the timestamp here in the adapter layer (the domain/port stays
 *    effect-free),
 *  - seeds a DISTINCT, monotonic goal_progress sequence from the persisted max
 *    goal_progress sequence so resume runs stay monotonic (the F-D01 watermark
 *    pattern shared with [GoalRunnerLedgerRecorder]),
 *  - persists best-effort via [GoalRunnerWorkflowOutcomeStore.recordProgressEvent].
 *
 * Emission is a no-op until the child workflow id is known; a write failure
 * NEVER fails the run (logged at WARNING with workflowId/action only — no
 * secrets or prompt content, mirroring the F-R02 best-effort logging fix).
 *
 * Each emitter instance is created per child launch and is only ever called on
 * the single supervisor poll thread, so the monotonic [sequence] counter needs
 * no synchronization.
 */
internal class GoalRunnerProgressEventEmitter(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val request: GoalRunnerRunRequest,
  private val resolveWorkflowId: () -> String?,
  watermarkSeed: Int?,
) : AgentRunProgressEmitter {
  private var sequence: Int = watermarkSeed?.let { it + 1 } ?: 0
  private val log: Logger = Logger.getLogger(GoalRunnerProgressEventEmitter::class.java.name)

  override fun emit(emission: AgentRunProgressEmission) {
    val workflowId = runCatching { resolveWorkflowId() }.getOrNull()?.takeIf(String::isNotBlank)
      ?: return // No-op until the child workflow id is durably known.
    val event = GoalProgressEvent(
      eventKind = emission.eventKind,
      workflowId = workflowId,
      // AC25/AC21: the supervisor declares a long child operation. The workflow
      // phase is the supervision phase; the operation descriptors carry the
      // long-op identity and the authoritative process-alive signal.
      workflowPhase = "goal_runner_supervision",
      processAlive = emission.processAlive,
      sequenceNumber = sequence++,
      timestamp = Instant.now().toString(),
      operationName = emission.operationName,
      operationKind = emission.operationKind,
      expectedLong = emission.expectedLong,
      outcome = emission.outcome,
    )
    runCatching {
      outcomeStore.recordProgressEvent(
        GoalRunnerProgressEventRecordRequest(workflowId = workflowId, event = event),
        request.dbPathOverride,
      )
    }
      .onFailure { error -> logBestEffortFailure(emission, workflowId, error) }
      .onSuccess { recorded -> if (!recorded) logBestEffortMissingWorkflow(emission, workflowId) }
  }

  private fun logBestEffortFailure(emission: AgentRunProgressEmission, workflowId: String, error: Throwable) {
    log.log(
      Level.WARNING,
      "Best-effort goal progress emit failed: action='${emission.eventKind.wireValue}' " +
        "workflowId='$workflowId' errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
  }

  private fun logBestEffortMissingWorkflow(emission: AgentRunProgressEmission, workflowId: String) {
    log.log(
      Level.WARNING,
      "Best-effort goal progress emit skipped (workflow not found): " +
        "action='${emission.eventKind.wireValue}' workflowId='$workflowId'",
    )
  }
}
