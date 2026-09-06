package skillbill.ports.workflow.gitops.model

import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat

sealed interface WorkflowWorktreeActivityResult {
  val changedFileSummary: GoalObservabilityChangedFileSummary?
  val diffStat: GoalObservabilityDiffStat?
  val error: String
  val ok: Boolean
    get() = this is WorkflowWorktreeActivityResult.Ok

  data class Ok(
    override val changedFileSummary: GoalObservabilityChangedFileSummary? = null,
    override val diffStat: GoalObservabilityDiffStat? = null,
  ) : WorkflowWorktreeActivityResult {
    override val error: String = ""
  }

  data class Failed(
    override val error: String,
    override val changedFileSummary: GoalObservabilityChangedFileSummary? = null,
    override val diffStat: GoalObservabilityDiffStat? = null,
  ) : WorkflowWorktreeActivityResult

  companion object {
    operator fun invoke(
      status: String,
      changedFileSummary: GoalObservabilityChangedFileSummary? = null,
      diffStat: GoalObservabilityDiffStat? = null,
      error: String = "",
    ): WorkflowWorktreeActivityResult = if (status == OK_STATUS) {
      Ok(changedFileSummary, diffStat)
    } else {
      Failed(error.ifBlank { status }, changedFileSummary, diffStat)
    }
  }
}

private const val OK_STATUS = "ok"
