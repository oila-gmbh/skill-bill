package skillbill.ports.workflow.gitops.model

import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat

data class WorkflowWorktreeActivityResult(
  val status: String,
  val changedFileSummary: GoalObservabilityChangedFileSummary? = null,
  val diffStat: GoalObservabilityDiffStat? = null,
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok"
}
