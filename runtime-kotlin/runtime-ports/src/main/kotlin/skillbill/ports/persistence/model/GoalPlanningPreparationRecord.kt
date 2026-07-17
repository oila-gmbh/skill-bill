package skillbill.ports.persistence.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.contracts.workflow.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION

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
  val contractVersion: String = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
)

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
