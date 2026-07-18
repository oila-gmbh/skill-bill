package skillbill.ports.persistence.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.contracts.workflow.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION

data class GoalPlanningIdentity(
  val parentGoalWorkflowId: String,
  val normalizedIssueKey: String,
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
  val createdAt: String = "",
  val contractVersion: String = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
)

data class GoalSubtaskPlanCheckpoint(
  val identity: GoalPlanningIdentity,
  val subtaskId: Int,
  val manifestOrder: Int,
  val governedSubSpecPath: String,
  val subSpecHash: String,
  val preparationStatus: GoalPlanningPreparationState = GoalPlanningPreparationState.PREPARED,
  val provenance: GoalPlanningContractProvenance,
  val payloadSha256: String,
  val planPayload: String,
  val createdAt: String = "",
  val contractVersion: String = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
)

data class GoalPlanningPreparationProgress(
  val sharedPreplanPrepared: Boolean,
  val preparedPlanCount: Int,
  val expectedPlanCount: Int,
  val firstMissingSubtaskId: Int?,
)

data class GovernedGoalSubtaskDescriptor(
  val subtaskId: Int,
  val manifestOrder: Int,
  val governedSubSpecPath: String,
  val subSpecHash: String,
)

data class GoalPlanningPreparationRecord(
  val parentGoalWorkflowId: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val subtaskId: Int,
  val governedSubSpecPath: String,
  val preparationStatus: GoalPlanningPreparationState,
  val provenance: GoalPlanningPreparationProvenance,
  val preplanPayload: String,
  val planPayload: String,
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
  val parentGoalWorkflowId: String,
  val subtaskId: Int,
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
