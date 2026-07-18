package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationState
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import java.security.MessageDigest

class GoalPlanningPreparationValidator(
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
) {
  fun validate(record: GoalPlanningPreparationRecord) {
    val label = "${record.parentGoalWorkflowId}#${record.subtaskId}"
    outputValidator.validatePhaseOutputText(record.preplanPayload, PREPLAN_PHASE_ID)
    outputValidator.validatePhaseOutputText(record.planPayload, PLAN_PHASE_ID)
    val failure = envelopeFailure(record) ?: provenanceFailure(record)
    failure?.let { throw InvalidGoalPlanningPreparationSchemaError(sourceLabel = label, fieldPath = "", reason = it) }
    requireCompleted(record.preplanPayload, PREPLAN_PHASE_ID, label)
    requireCompleted(record.planPayload, PLAN_PHASE_ID, label)
  }

  private fun requireCompleted(payload: String, phaseId: String, label: String) {
    val status = JsonSupport.parseObjectOrNull(payload)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("status")
      ?.toString()
    if (status != "completed") {
      throw InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = label,
        fieldPath = "${phaseId}_payload.status",
        reason = "status must be 'completed' for a prepared pair",
      )
    }
  }

  private fun envelopeFailure(record: GoalPlanningPreparationRecord): String? = when {
    record.contractVersion != LEGACY_GOAL_PLANNING_PREPARATION_CONTRACT_VERSION ->
      "contract_version must be '$LEGACY_GOAL_PLANNING_PREPARATION_CONTRACT_VERSION'"
    record.subtaskId < 1 -> "subtask_id must be a positive integer"
    record.parentGoalWorkflowId.isBlank() -> "parent_goal_workflow_id is required"
    record.normalizedIssueKey.isBlank() -> "normalized_issue_key is required"
    record.repositoryIdentity.isBlank() -> "repository_identity is required"
    record.governedSubSpecPath.isBlank() -> "governed_sub_spec_path is required"
    record.preparationStatus != GoalPlanningPreparationState.PREPARED ->
      "preparation_status must be 'prepared' to checkpoint a pair"
    record.preplanPayload.isBlank() -> "preplan_payload is required"
    record.planPayload.isBlank() -> "plan_payload is required"
    else -> null
  }

  private fun provenanceFailure(record: GoalPlanningPreparationRecord): String? = when {
    record.provenance.parentSpecHash.isBlank() -> "provenance.parent_spec_hash is required"
    record.provenance.subSpecHash.isBlank() -> "provenance.sub_spec_hash is required"
    record.provenance.decompositionManifestHash.isBlank() -> "provenance.decomposition_manifest_hash is required"
    record.provenance.phaseOutputContractId != FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID ->
      "provenance.phase_output_contract_id must be the feature-task-runtime phase output schema id"
    record.provenance.phaseOutputContractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION ->
      "provenance.phase_output_contract_version must be '$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'"
    else -> null
  }

  companion object {
    private const val LEGACY_GOAL_PLANNING_PREPARATION_CONTRACT_VERSION = "0.1"
    const val PREPLAN_PHASE_ID: String = "preplan"
    const val PLAN_PHASE_ID: String = "plan"
  }
}

fun sha256HexUtf8(text: String): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
  return digest.joinToString("") { byte -> "%02x".format(byte) }
}
