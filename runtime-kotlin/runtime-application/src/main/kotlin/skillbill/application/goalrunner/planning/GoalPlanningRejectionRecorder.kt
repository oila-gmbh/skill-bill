package skillbill.application.goalrunner.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.planning.model.GoalPlanningRejectionRecord

fun interface GoalPlanningRejectionRecorder {
  fun record(record: GoalPlanningRejectionRecord)

  companion object {
    val NONE: GoalPlanningRejectionRecorder = GoalPlanningRejectionRecorder {}
  }
}

@Inject
class DurableGoalPlanningRejectionRecorder(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
) : GoalPlanningRejectionRecorder {
  override fun record(record: GoalPlanningRejectionRecord) {
    runCatching {
      recorder.recordRejectedOutput(
        RejectedOutputDiagnosticRequest(
          workflowId = record.parentWorkflowId,
          phaseId = record.phaseId,
          attempt = record.attempt.coerceAtLeast(1),
          rule = record.rule,
          path = "/",
          reason = record.reason,
          agentId = record.agentId,
          model = "unspecified",
          rawResponse = record.rawEvidence.encodeToByteArray(),
        ),
        record.dbPathOverride,
      )
    }
  }
}
