package skillbill.workflow.decomposition.model

import skillbill.agent.model.AgentId
import skillbill.contracts.workflow.DECOMPOSITION_MANIFEST_CONTRACT_VERSION
import skillbill.workflow.engine.model.WorkflowId

enum class DecompositionExecutionModel(val wireValue: String) {
  SAME_BRANCH_COMMIT_PER_SUBTASK("same_branch_commit_per_subtask"),
  STACKED_BRANCHES("stacked_branches"),
  ;

  companion object {
    fun fromWireValue(value: String): DecompositionExecutionModel? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class SpecSource(val wireValue: String) {
  LOCAL("local"),
  LINEAR("linear"),
  ;

  companion object {
    fun fromWireValue(value: String): SpecSource? = entries.firstOrNull { it.wireValue == value }
  }
}

data class DecompositionSubtask(
  val id: SubtaskId,
  val name: String,
  val specPath: String,
  val status: String = "pending",
  val branch: String? = null,
  val commitSha: String? = null,
  val workflowId: WorkflowId? = null,
  val blockedReason: String? = null,
  val lastResumableStep: String? = null,
  val linearIssueId: String? = null,
  val finalizingAgentId: AgentId? = null,
  val participatingAgentIds: List<AgentId> = emptyList(),
  val dependencies: List<DecompositionDependency> = emptyList(),
) {
  fun hasStarted(): Boolean = status != "pending" ||
    branch != null ||
    commitSha != null ||
    workflowId != null ||
    blockedReason != null ||
    lastResumableStep != null
}

data class DecompositionDependency(
  val subtaskId: SubtaskId,
  val optional: Boolean = false,
  val skipped: Boolean = false,
)

data class DecompositionStackBranch(
  val subtaskId: SubtaskId,
  val branch: String,
  val baseBranch: String,
)

data class CurrentSubtaskIntent(
  val subtaskId: SubtaskId,
  val action: String,
)

/** Typed application input used before a manifest wire map is emitted. */
data class DecompositionManifestPlan(
  val parentSpecPath: String,
  val baseBranch: String,
  val featureBranch: String?,
  val specSource: SpecSource,
  val executionModel: DecompositionExecutionModel,
  val stackBranches: List<DecompositionStackBranch>,
  val currentSubtaskId: SubtaskId,
  val subtasks: List<DecompositionSubtask>,
)

data class DecompositionManifest(
  val contractVersion: String = DECOMPOSITION_MANIFEST_CONTRACT_VERSION,
  val issueKey: IssueKey,
  val featureName: String,
  val parentSpecPath: String,
  val specSource: SpecSource = SpecSource.LOCAL,
  val status: String = "pending",
  val executionModel: DecompositionExecutionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
  val baseBranch: String,
  val featureBranch: String?,
  val stackBranches: List<DecompositionStackBranch> = emptyList(),
  val currentSubtaskIntent: CurrentSubtaskIntent,
  val subtasks: List<DecompositionSubtask>,
) {
  fun nextSubtaskId(): SubtaskId = SubtaskId((subtasks.maxOfOrNull { it.id.toString().toInt() } ?: 0) + 1)
}
