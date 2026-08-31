package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.ShellContentContractException
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import kotlin.coroutines.cancellation.CancellationException

internal class GoalRunnerProgressReader(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) {
  fun read(workflowId: String, request: GoalRunnerRunRequest): GoalRunnerChildProgressRead =
    runCatching { GoalRunnerChildProgressRead.Present(outcomeStore.progress(workflowId, request.dbPathOverride)) }
      .fold(
        onSuccess = { it },
        onFailure = { error ->
          when (error) {
            is CancellationException -> throw error
            is ShellContentContractException -> throw error
            else -> {
              val exception = error as? Exception ?: throw error
              GoalRunnerChildProgressRead.Failed(exception)
            }
          }
        },
      )

  fun safeProgress(workflowId: String, request: GoalRunnerRunRequest): GoalRunnerWorkflowProgress? =
    when (val read = read(workflowId, request)) {
      is GoalRunnerChildProgressRead.Present -> read.progress
      is GoalRunnerChildProgressRead.Absent -> null
      is GoalRunnerChildProgressRead.Failed -> null
    }
}

internal class GoalRunnerPauseBoundary(
  private val manifestStore: GoalRunnerManifestStore,
) {
  fun pauseBeforeLaunch(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    knownControl: GoalRunnerControlState? = null,
  ): GoalRunnerIterationResult? {
    val control = knownControl ?: manifestStore.controlState(state.parentWorkflowId, request.dbPathOverride)
    if (!control.requiresPauseBoundary(state.manifest)) return null
    val pausedState = manifestStore.pauseAtBoundary(
      state.copy(controlState = control),
      request.dbPathOverride,
    )
    val subtaskId = pausedState.manifest.currentSubtaskIntent.subtaskId
    return GoalRunnerIterationResult(
      state = pausedState,
      report = stopped(
        StoppedReportArgs(
          issueKey = pausedState.manifest.issueKey,
          attempted = emptyList(),
          subtaskId = subtaskId,
          reason = GoalRunnerStopReason.PAUSED,
          blockedReason = "Goal paused at a durable boundary: ${pausedState.controlState.pauseReason}",
          workflowId = pausedState.manifest.workflowIdFor(subtaskId),
          lastResumableStep = pausedState.manifest.subtasks
            .firstOrNull { it.id == subtaskId }
            ?.lastResumableStep
            .orEmpty()
            .ifBlank { "plan" },
        ),
      ),
    )
  }
}
