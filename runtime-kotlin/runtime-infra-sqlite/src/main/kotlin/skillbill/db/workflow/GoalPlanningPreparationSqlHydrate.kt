package skillbill.db.workflow

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import java.sql.ResultSet

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
