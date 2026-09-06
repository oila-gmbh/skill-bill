package skillbill.application.goalrunner.model

import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import java.nio.file.Path

data class GoalRunnerReplanRequest(
  val issueKey: IssueKey,
  val subtaskId: SubtaskId,
  val repoRoot: Path? = null,
  val includeSharedPreplan: Boolean = false,
) {
  init {
    require(issueKey.value.isNotBlank()) { "issueKey is required." }
    require(subtaskId.value > 0) { "subtaskId must be positive." }
  }
}

data class GoalRunnerReplanResult(
  val issueKey: IssueKey,
  val parentWorkflowId: WorkflowId,
  val subtaskId: SubtaskId,
  val discardedPlan: Boolean,
  val discardedSharedPreplan: Boolean = false,
  val cascadedPlanSubtaskIds: List<Int> = emptyList(),
  val clearedChildSubtaskIds: List<Int> = emptyList(),
  val before: GoalRunnerReplanSnapshot,
  val after: GoalRunnerReplanSnapshot,
)

data class GoalRunnerReplanSnapshot(
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val sharedPreplanPrepared: Boolean,
  val plannedSubtaskIds: List<Int>,
  val subtasks: List<GoalRunnerResetSubtaskSnapshot>,
)
