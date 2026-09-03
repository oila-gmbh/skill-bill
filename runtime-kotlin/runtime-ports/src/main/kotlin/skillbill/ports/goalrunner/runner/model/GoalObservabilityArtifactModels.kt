package skillbill.ports.goalrunner.runner.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat

data class GoalObservabilityWorktreeActivity(
  val changedFileSummary: GoalObservabilityChangedFileSummary?,
  val diffStat: GoalObservabilityDiffStat?,
)

data class GoalObservabilityProgressInput(
  @OpenBoundaryMap("Existing durable workflow artifacts when projecting goal observability from progress")
  val artifacts: Map<String, Any?>,
  val workflowId: String,
  val workflowStatus: String,
  val currentStepId: String,
  val worktreeActivity: GoalObservabilityWorktreeActivity? = null,
)

data class GoalObservabilityRuntimeEventInput(
  @OpenBoundaryMap("Existing durable workflow artifacts when recording a goal observability runtime event")
  val artifacts: Map<String, Any?>,
  val request: GoalRunnerObservabilityRecordRequest,
)
