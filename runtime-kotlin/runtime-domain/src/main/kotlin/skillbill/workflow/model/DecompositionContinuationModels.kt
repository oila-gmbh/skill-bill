package skillbill.workflow.model

sealed class DecompositionContinuationSelection {
  data class Resume(
    val subtask: DecompositionSubtask,
    val workflowId: String,
    val resumeStepId: String,
  ) : DecompositionContinuationSelection()

  data class Start(
    val subtask: DecompositionSubtask,
    val branchPlan: DecompositionBranchPlan,
  ) : DecompositionContinuationSelection()

  data class Blocked(
    val subtask: DecompositionSubtask,
    val reason: String,
  ) : DecompositionContinuationSelection()

  data class TerminalSubtask(val subtask: DecompositionSubtask) : DecompositionContinuationSelection()

  data class Done(val manifest: DecompositionManifest) : DecompositionContinuationSelection()
}

data class DecompositionBranchPlan(
  val branch: String,
  val baseBranch: String,
  val validateBase: Boolean,
)
