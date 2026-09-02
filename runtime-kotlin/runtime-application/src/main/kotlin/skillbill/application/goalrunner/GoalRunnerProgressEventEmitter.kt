package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.workflow.goal.model.GoalProgressEvent
import java.time.Clock

class GoalRunnerProgressEventEmitter(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val request: GoalRunnerRunRequest,
  private val resolveWorkflowId: () -> String?,
  watermarkSeed: Int?,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) : AgentRunProgressEmitter {
  private var sequence: Int = watermarkSeed?.let { it + 1 } ?: 0

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
      timestamp = clock.instant().toString(),
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
    diagnostics.warning(
      "Best-effort goal progress emit failed: action='${emission.eventKind.wireValue}' " +
        "workflowId='$workflowId' errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
  }

  private fun logBestEffortMissingWorkflow(emission: AgentRunProgressEmission, workflowId: String) {
    diagnostics.warning(
      "Best-effort goal progress emit skipped (workflow not found): " +
        "action='${emission.eventKind.wireValue}' workflowId='$workflowId'",
    )
  }
}
