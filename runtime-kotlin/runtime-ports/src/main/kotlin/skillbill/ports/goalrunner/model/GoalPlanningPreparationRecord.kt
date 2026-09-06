package skillbill.ports.goalrunner.model

import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.decomposition.model.IssueKey

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.contracts.workflow.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence

data class GoalPlanningIdentity(
  val parentGoalWorkflowId: WorkflowId,
  val normalizedIssueKey: IssueKey,
  val repositoryIdentity: String,
)

data class GoalPlanningContractProvenance(
  val parentSpecHash: String,
  val decompositionManifestHash: String,
  val planningContractId: String,
  val planningContractVersion: String = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
  val phaseOutputContractId: String = FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID,
  val phaseOutputContractVersion: String = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
)

data class SharedGoalPreplanCheckpoint(
  val identity: GoalPlanningIdentity,
  val preparationStatus: GoalPlanningPreparationState = GoalPlanningPreparationState.PREPARED,
  val provenance: GoalPlanningContractProvenance,
  val payloadSha256: String,
  val preplanPayload: String,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val createdAt: String = "",
  val contractVersion: String = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
)

data class GoalSubtaskPlanCheckpoint(
  val identity: GoalPlanningIdentity,
  val subtaskId: SubtaskId,
  val manifestOrder: Int,
  val governedSubSpecPath: String,
  val subSpecHash: String,
  val preparationStatus: GoalPlanningPreparationState = GoalPlanningPreparationState.PREPARED,
  val provenance: GoalPlanningContractProvenance,
  val payloadSha256: String,
  val planPayload: String,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val createdAt: String = "",
  val contractVersion: String = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
)

data class GoalPlanningPreparationProgress(
  val sharedPreplanPrepared: Boolean,
  val preparedPlanCount: Int,
  val expectedPlanCount: Int,
  val missingSubtaskIds: List<Int>,
) {
  val firstMissingSubtaskId: Int? get() = missingSubtaskIds.firstOrNull()
}

data class GovernedGoalSubtaskDescriptor(
  val subtaskId: SubtaskId,
  val manifestOrder: Int,
  val governedSubSpecPath: String,
  val subSpecHash: String,
)

data class GoalPlanningPreparationRecord(
  val parentGoalWorkflowId: WorkflowId,
  val normalizedIssueKey: IssueKey,
  val repositoryIdentity: String,
  val subtaskId: SubtaskId,
  val governedSubSpecPath: String,
  val preparationStatus: GoalPlanningPreparationState,
  val provenance: GoalPlanningPreparationProvenance,
  val preplanPayload: String,
  val planPayload: String,
  val preplanRepairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val planRepairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val createdAt: String = "",
  val updatedAt: String = "",
  val contractVersion: String = LEGACY_GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
)

private const val LEGACY_GOAL_PLANNING_PREPARATION_CONTRACT_VERSION = "0.1"

data class GoalPlanningPreparationProvenance(
  val parentSpecHash: String,
  val subSpecHash: String,
  val decompositionManifestHash: String,
  val phaseOutputContractId: String = FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID,
  val phaseOutputContractVersion: String = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
) {
  fun asRecoveryTuple(): List<String> =
    listOf(parentSpecHash, subSpecHash, decompositionManifestHash, phaseOutputContractId, phaseOutputContractVersion)
}

data class GoalPlanningPreparationStatus(
  val parentGoalWorkflowId: WorkflowId,
  val subtaskId: SubtaskId,
  val preparationStatus: GoalPlanningPreparationState,
  val provenance: GoalPlanningPreparationProvenance,
)

enum class GoalPlanningPreparationState(val wireValue: String) {
  PENDING("pending"),
  PREPARED("prepared"),
  ;

  companion object {
    fun fromWireValue(value: String): GoalPlanningPreparationState = entries.singleOrNull { it.wireValue == value }
      ?: throw IllegalArgumentException("Unsupported goal planning preparation status '$value'.")
  }
}
