package skillbill.application.goalrunner

import skillbill.application.model.GoalRunnerRunRequest
import skillbill.goalrunner.GoalRunnerWorkerSubtaskRequestParser
import skillbill.goalrunner.GoalRunnerWorkerSubtaskScheduler
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.workflow.model.DecompositionManifest

internal class GoalRunnerWorkerRequestHandler(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) {
  fun handle(
    state: GoalRunnerManifestState,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerWorkerRequestHandlingResult {
    val output = launchOutcome.workerOutput()
    return if (output == null) {
      GoalRunnerWorkerRequestHandlingResult(state)
    } else {
      handleOutput(state, output, subtaskId, request)
    }
  }

  private fun handleOutput(
    state: GoalRunnerManifestState,
    output: WorkerLaunchOutput,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerWorkerRequestHandlingResult {
    val parsed = GoalRunnerWorkerSubtaskRequestParser.parse(
      stdout = output.stdout,
      stderr = output.stderr,
      manifest = state.manifest,
    )
    return if (parsed.isEmpty()) {
      GoalRunnerWorkerRequestHandlingResult(state)
    } else {
      persistParsedOutcomes(state, parsed, subtaskId, request)
    }
  }

  private fun persistParsedOutcomes(
    state: GoalRunnerManifestState,
    parsed: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerWorkerRequestHandlingResult {
    val scheduled = GoalRunnerWorkerSubtaskScheduler.scheduleQueuedRequests(state.manifest, parsed)
    val workflowId = state.manifest.workflowIdFor(subtaskId)
    val auditRecorded = workflowId?.let {
      runCatching {
        outcomeStore.recordWorkerSubtaskRequestOutcomes(
          workflowId = it,
          outcomes = scheduled.outcomes,
          dbPathOverride = request.dbPathOverride,
        )
      }.getOrDefault(false)
    } ?: false
    if (!auditRecorded) {
      return GoalRunnerWorkerRequestHandlingResult(
        state = state,
        operatorConfirmationStop = workerRequestAuditFailureStop(state.manifest, subtaskId, workflowId),
      )
    }
    val saved = if (scheduled.manifest == state.manifest) {
      state
    } else {
      manifestStore.save(state.copy(manifest = scheduled.manifest), request.dbPathOverride)
    }
    return GoalRunnerWorkerRequestHandlingResult(
      state = saved,
      operatorConfirmationStop = scheduled.outcomes.operatorConfirmationStop(saved.manifest, subtaskId),
    )
  }

  private fun workerRequestAuditFailureStop(
    manifest: DecompositionManifest,
    subtaskId: Int,
    workflowId: String?,
  ): GoalRunnerReconciledOutcome.Stop = GoalRunnerReconciledOutcome.Stop(
    reason = GoalRunnerStopReason.BLOCKED,
    blockedReason = "Worker subtask request outcome audit could not be recorded; " +
      "additional worker work was not scheduled.",
    workflowId = workflowId,
    commitSha = null,
    lastResumableStep = manifest.subtasks
      .firstOrNull { subtask -> subtask.id == subtaskId }
      ?.lastResumableStep
      .orEmpty()
      .ifBlank { "implement" },
  )
}

internal data class GoalRunnerWorkerRequestHandlingResult(
  val state: GoalRunnerManifestState,
  val operatorConfirmationStop: GoalRunnerReconciledOutcome.Stop? = null,
)

internal fun DecompositionManifest.workflowIdFor(subtaskId: Int): String? =
  subtasks.firstOrNull { subtask -> subtask.id == subtaskId }?.workflowId?.takeIf(String::isNotBlank)

private data class WorkerLaunchOutput(
  val stdout: String,
  val stderr: String,
)

private fun AgentRunLaunchOutcome.workerOutput(): WorkerLaunchOutput? = (this as? AgentRunLaunchFacts)
  ?.let { facts -> WorkerLaunchOutput(stdout = facts.stdout, stderr = facts.stderr) }

private fun List<GoalRunnerWorkerSubtaskRequestOutcome>.operatorConfirmationStop(
  manifest: DecompositionManifest,
  subtaskId: Int,
): GoalRunnerReconciledOutcome.Stop? =
  filterIsInstance<GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation>()
    .firstOrNull()
    ?.let { outcome ->
      GoalRunnerReconciledOutcome.Stop(
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason = outcome.reason,
        workflowId = manifest.workflowIdFor(subtaskId),
        commitSha = null,
        lastResumableStep = manifest.subtasks
          .firstOrNull { subtask -> subtask.id == subtaskId }
          ?.lastResumableStep
          .orEmpty()
          .ifBlank { "implement" },
      )
    }
