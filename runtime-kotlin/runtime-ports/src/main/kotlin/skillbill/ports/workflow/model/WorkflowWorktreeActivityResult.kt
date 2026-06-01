package skillbill.ports.workflow.model

import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat

data class WorkflowWorktreeActivityResult(
  val status: String,
  val changedFileSummary: GoalObservabilityChangedFileSummary? = null,
  val diffStat: GoalObservabilityDiffStat? = null,
  val error: String = "",
) {
  val ok: Boolean get() = status == "ok"
}
