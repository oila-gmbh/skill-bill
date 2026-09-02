package skillbill.application.goalrunner.planning

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

object GoalPlanningSweepConstants {
  const val PHASE_PREPLAN: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
  const val PHASE_PLAN: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
  const val SHARED_CONTEXT_FIELD = "_goal_planning_shared_context"
  const val EMPTY_PLANNING_HARVEST_RULE = "empty-planning-harvest"
  const val SCHEMA_REJECTED_PLANNING_RULE = "planning-schema-rejected"
  const val UNSUCCESSFUL_PLANNING_STATUS_RULE = "planning-unsuccessful-status"
  const val RETRYABLE_PLANNING_DECLINE_RULE = "planning-retryable-decline"
  const val MAX_RETRYABLE_PLANNING_DECLINES = 3
  const val NANOS_PER_MILLI = 1_000_000L
  const val PLANNING_STOP_DETAIL_MAX_CHARS = 400
}
