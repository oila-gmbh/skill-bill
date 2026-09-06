package skillbill.application.goalrunner
import me.tatarka.inject.annotations.Inject
import skillbill.error.ShellContentContractException
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.workflow.engine.model.WorkflowId
import kotlin.coroutines.cancellation.CancellationException

@Inject
public class GoalRunnerProgressReader(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) {
  internal fun read(workflowId: WorkflowId): GoalRunnerChildProgressRead =
    runCatching { GoalRunnerChildProgressRead.Present(outcomeStore.progress(workflowId)) }
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

  fun safeProgress(workflowId: WorkflowId): GoalRunnerWorkflowProgress? = when (val read = read(workflowId)) {
    is GoalRunnerChildProgressRead.Present -> read.progress
    is GoalRunnerChildProgressRead.Absent -> null
    is GoalRunnerChildProgressRead.Failed -> null
  }
}

@Inject
public class GoalRunnerPauseBoundary(
  private val manifestStore: GoalRunnerManifestStore,
) {
  internal fun pauseBeforeLaunch(
    state: GoalRunnerManifestState,
    knownControl: GoalRunnerControlState? = null,
  ): GoalRunnerIterationResult? {
    val control = knownControl ?: manifestStore.controlState(state.parentWorkflowId)
    if (!control.requiresPauseBoundary(state.manifest)) return null
    val pausedState = manifestStore.pauseAtBoundary(
      state.copy(controlState = control),
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
