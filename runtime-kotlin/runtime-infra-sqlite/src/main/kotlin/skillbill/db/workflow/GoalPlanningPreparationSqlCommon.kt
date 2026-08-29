package skillbill.db.workflow

import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

internal inline fun <T> translateSqlFailure(workflowId: String, subtaskId: Int, block: () -> T): T = try {
  block()
} catch (failure: SQLException) {
  throw IncompatibleGoalPlanningPreparationRecoveryError(
    workflowId,
    subtaskId,
    "SQLite rejected the immutable planning checkpoint: ${failure.message.orEmpty()}",
    failure,
  )
}

internal fun Connection.rejectLegacy(workflowId: String) {
  prepareStatement(
    "SELECT 1 FROM goal_planning_preparations WHERE parent_goal_workflow_id = ? LIMIT 1",
  ).use { s ->
    s.setString(1, workflowId)
    s.executeQuery().use {
      if (it.next()) {
        throw IncompatibleGoalPlanningPreparationRecoveryError(
          workflowId,
          0,
          "legacy 0.1 pair requires hard reset or operator migration",
        )
      }
    }
  }
}

internal fun requireParentGoalWorkflowId(parentGoalWorkflowId: String) {
  if (parentGoalWorkflowId.isBlank()) {
    throw InvalidGoalPlanningPreparationSchemaError(
      parentGoalWorkflowId,
      "parent_goal_workflow_id",
      "parent_goal_workflow_id is required",
    )
  }
}

internal fun requirePositiveSubtaskId(parentGoalWorkflowId: String, subtaskId: Int) {
  if (subtaskId < 1) {
    throw InvalidGoalPlanningPreparationSchemaError(
      "$parentGoalWorkflowId#$subtaskId",
      "subtask_id",
      "subtask_id must be a positive integer",
    )
  }
}

internal fun normalizedIdentityFailure(identity: GoalPlanningIdentity): Pair<String, String>? = when {
  identity.parentGoalWorkflowId.isBlank() ->
    "identity.parent_goal_workflow_id" to "parent_goal_workflow_id is required"
  identity.normalizedIssueKey.isBlank() -> "identity.normalized_issue_key" to "normalized_issue_key is required"
  identity.repositoryIdentity.isBlank() -> "identity.repository_identity" to "repository_identity is required"
  else -> null
}

internal fun normalizedProvenanceFailure(provenance: GoalPlanningContractProvenance): Pair<String, String>? = when {
  !provenance.parentSpecHash.isSha256() ->
    "provenance.parent_spec_hash" to "parent_spec_hash must be a lowercase SHA-256"
  !provenance.decompositionManifestHash.isSha256() ->
    "provenance.decomposition_manifest_hash" to "decomposition_manifest_hash must be a lowercase SHA-256"
  provenance.planningContractId != GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID ->
    "provenance.planning_contract_id" to "planning_contract_id is incompatible"
  provenance.planningContractVersion != GOAL_PLANNING_PREPARATION_CONTRACT_VERSION ->
    "provenance.planning_contract_version" to "planning_contract_version is incompatible"
  provenance.phaseOutputContractId != FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID ->
    "provenance.phase_output_contract_id" to "phase_output_contract_id is incompatible"
  provenance.phaseOutputContractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION ->
    "provenance.phase_output_contract_version" to
      "phase_output_contract_version is incompatible; hard-reset the workflow with " +
      "'skill-bill goal reset <issue-key> --hard --yes'"
  else -> null
}

internal fun normalizedEnvelopeFailure(
  contractVersion: String,
  status: GoalPlanningPreparationState,
  payloadSha256: String,
  payload: String,
): Pair<String, String>? = when {
  contractVersion != GOAL_PLANNING_PREPARATION_CONTRACT_VERSION ->
    "contract_version" to "contract_version is incompatible"
  status != GoalPlanningPreparationState.PREPARED ->
    "preparation_status" to "preparation_status must be prepared"
  !payloadSha256.isSha256() -> "payload_sha256" to "payload_sha256 must be a lowercase SHA-256"
  payload.isBlank() -> "payload" to "payload is required"
  else -> null
}

internal fun hydratedProvenanceFailure(provenance: GoalPlanningContractProvenance): Pair<String, String>? = when {
  !provenance.parentSpecHash.isSha256() ->
    "provenance.parent_spec_hash" to "parent_spec_hash must be a lowercase SHA-256"
  !provenance.decompositionManifestHash.isSha256() ->
    "provenance.decomposition_manifest_hash" to "decomposition_manifest_hash must be a lowercase SHA-256"
  else -> null
}

internal fun hydratedEnvelopeFailure(
  status: GoalPlanningPreparationState,
  payloadSha256: String,
  payload: String,
): Pair<String, String>? = when {
  status != GoalPlanningPreparationState.PREPARED ->
    "preparation_status" to "preparation_status must be prepared"
  !payloadSha256.isSha256() -> "payload_sha256" to "payload_sha256 must be a lowercase SHA-256"
  payload.isBlank() -> "payload" to "payload is required"
  else -> null
}

internal fun SharedGoalPreplanCheckpoint.repairEvidenceJson(): String? = repairEvidence?.let {
  JsonSupport.mapToJsonString(it.toArtifactMap())
}

internal fun GoalSubtaskPlanCheckpoint.repairEvidenceJson(): String? = repairEvidence?.let {
  JsonSupport.mapToJsonString(it.toArtifactMap())
}

internal fun incompatibleLoadedVersionReason(loaded: String): String = "loaded contract_version '$loaded' is not '0.1'."

internal fun statusLabel(rows: ResultSet): String =
  "${rows.getString("parent_goal_workflow_id")}#${rows.getInt("subtask_id")}"

internal fun requireColumn(rows: ResultSet, label: String, column: String): String =
  rows.getString(column) ?: throw InvalidGoalPlanningPreparationSchemaError(
    sourceLabel = label,
    fieldPath = column,
    reason = "$column is required but was null on hydrate.",
  )

internal fun optionalRepairEvidence(
  rows: ResultSet,
  label: String,
  column: String,
): FeatureTaskRuntimePhaseOutputRepairEvidence? {
  val raw = rows.getString(column) ?: return null
  return try {
    val decoded = JsonSupport.parseObjectOrNull(raw)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: throw IllegalArgumentException("repair evidence must be a JSON object")
    FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap(decoded)
  } catch (_: Exception) {
    throw InvalidGoalPlanningPreparationSchemaError(
      sourceLabel = label,
      fieldPath = column,
      reason = "repair evidence is malformed",
    )
  }
}

internal fun decodeState(label: String, value: String?): GoalPlanningPreparationState =
  GoalPlanningPreparationState.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidGoalPlanningPreparationSchemaError(
      sourceLabel = label,
      fieldPath = "preparation_status",
      reason = "preparation_status '${value.orEmpty()}' is not supported.",
    )

internal fun requirePositiveInt(rows: ResultSet, label: String, column: String): Int = rows.getInt(column).also {
  if (rows.wasNull() || it < 1) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      column,
      "$column must be a positive integer on hydrate",
    )
  }
}

internal fun requireNonNegativeInt(rows: ResultSet, label: String, column: String): Int = rows.getInt(column).also {
  if (rows.wasNull() || it < 0) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      column,
      "$column must be a non-negative integer on hydrate",
    )
  }
}

internal fun String.isSha256(): Boolean = length == SHA256_HEX_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

internal const val FIRST_COLUMN_INDEX: Int = 1
internal const val SECOND_COLUMN_INDEX: Int = 2
internal const val THIRD_COLUMN_INDEX: Int = 3
internal const val FOURTH_COLUMN_INDEX: Int = 4
internal const val SHA256_HEX_LENGTH: Int = 64
