package skillbill.application.goalrunner.planning

import skillbill.application.runtime.RuntimeSingleton
import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.planning.model.GoalPlanningAttemptRecord
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.workflow.goal.model.GoalProgressEvent
import java.time.Instant

fun interface GoalPlanningAttemptRecorder {
  fun record(attempt: GoalPlanningAttemptRecord)

  companion object {
    val NONE: GoalPlanningAttemptRecorder = GoalPlanningAttemptRecorder {}
  }
}

@RuntimeSingleton
@Inject
class DurableGoalPlanningAttemptRecorder(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) : GoalPlanningAttemptRecorder {
  private val nextSequenceByWorkflow = mutableMapOf<String, Int>()

  @Synchronized
  override fun record(attempt: GoalPlanningAttemptRecord) {
    outcomeStore.recordProgressEvent(
      GoalRunnerProgressEventRecordRequest(
        workflowId = attempt.parentWorkflowId,
        event = GoalProgressEvent(
          eventKind = attempt.eventKind,
          workflowId = attempt.parentWorkflowId,
          workflowPhase = "goal_planning",
          processAlive = true,
          sequenceNumber = nextSequenceByWorkflow.getOrPut(attempt.parentWorkflowId) {
            outcomeStore.ledgerSequenceWatermarks(attempt.issueKey, attempt.dbPathOverride)
              .maxProgressSequence
              ?.plus(1)
              ?: 0
          },
          timestamp = Instant.now().toString(),
          stepId = attempt.phaseId,
          operationName = "${attempt.phaseId}:${attempt.subtaskId}:attempt:${attempt.attempt}",
          operationKind = "planning_projection_attempt",
          expectedLong = true,
          outcome = attempt.outcome,
        ),
      ),
      attempt.dbPathOverride,
    )
    nextSequenceByWorkflow[attempt.parentWorkflowId] = nextSequenceByWorkflow.getValue(attempt.parentWorkflowId) + 1
  }
}
