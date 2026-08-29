package skillbill.db.workflow

import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.db.core.inImmediateTransaction
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalPlanningPreparationStatus
import java.sql.Connection
import java.sql.ResultSet

internal class GoalPlanningPreparationRecordSql(
  private val connection: Connection,
) {
  fun markPrepared(record: GoalPlanningPreparationRecord) {
    requirePreparedEnvelope(record)
    connection.inImmediateTransaction {
      if (connection.upsertPreparedRow(record)) return@inImmediateTransaction
      val stored = connection.selectStoredRecoveryIdentity(record.parentGoalWorkflowId, record.subtaskId)
        ?: return@inImmediateTransaction
      val reason = recoveryIdentityFailure(stored, record) ?: return@inImmediateTransaction
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        workflowId = record.parentGoalWorkflowId,
        subtaskId = record.subtaskId,
        reason = reason,
      )
    }
  }

  fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord? =
    connection.selectRecord(parentGoalWorkflowId, subtaskId)

  fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
    connection.selectOrderedByGoal(parentGoalWorkflowId)

  fun preparedCount(parentGoalWorkflowId: String): Int = connection.countPrepared(parentGoalWorkflowId)

  fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int? {
    if (orderedSubtaskIds.isEmpty()) return null
    val prepared = connection.preparedSubtaskStatuses(parentGoalWorkflowId)
    return orderedSubtaskIds.firstOrNull { id -> prepared[id] != GoalPlanningPreparationState.PREPARED.wireValue }
  }

  fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus? =
    connection.selectStatus(parentGoalWorkflowId, subtaskId)

  fun deletePreparedByGoal(parentGoalWorkflowId: String): Int = connection.deletePreparedByGoal(parentGoalWorkflowId)

  private fun requirePreparedEnvelope(record: GoalPlanningPreparationRecord) {
    val label = "${record.parentGoalWorkflowId}#${record.subtaskId}"
    val failure = envelopeFailure(record) ?: provenanceFailure(record)
    failure?.let {
      throw InvalidGoalPlanningPreparationSchemaError(sourceLabel = label, fieldPath = "", reason = it)
    }
  }

  private fun envelopeFailure(record: GoalPlanningPreparationRecord): String? = when {
    record.contractVersion != LEGACY_GOAL_PLANNING_PREPARATION_CONTRACT_VERSION ->
      "contract_version must be '$LEGACY_GOAL_PLANNING_PREPARATION_CONTRACT_VERSION' " +
        "but was '${record.contractVersion}'"
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

  private fun recoveryIdentityFailure(stored: StoredRecoveryIdentity, record: GoalPlanningPreparationRecord): String? {
    if (stored.normalizedIssueKey != record.normalizedIssueKey ||
      stored.repositoryIdentity != record.repositoryIdentity
    ) {
      return incompatibleIdentityReason(
        stored.normalizedIssueKey,
        stored.repositoryIdentity,
        record.normalizedIssueKey,
        record.repositoryIdentity,
      )
    }
    val incomingTuple = record.provenance.asRecoveryTuple()
    if (stored.provenanceTuple != incomingTuple) {
      return incompatibleProvenanceReason(stored.provenanceTuple, incomingTuple)
    }
    return null
  }

  private fun incompatibleIdentityReason(
    storedIssueKey: String,
    storedRepo: String,
    incomingIssueKey: String,
    incomingRepo: String,
  ): String = "stored normalized_issue_key/repository_identity '$storedIssueKey/$storedRepo'" +
    " differs from incoming '$incomingIssueKey/$incomingRepo'" +
    " (normalized_issue_key/repository_identity mismatch indicates a different goal or repository);" +
    " a prepared pair is immutable and cannot be regenerated by resume."

  private fun incompatibleProvenanceReason(stored: List<String>, incoming: List<String>): String =
    "stored provenance ${stored.joinToString("/")}" +
      " differs from incoming ${incoming.joinToString("/")}" +
      " (sub_spec_hash/decomposition_manifest_hash mismatch indicates a different spec);" +
      " a prepared pair is immutable and cannot be regenerated by resume."

  private companion object {
    const val LEGACY_GOAL_PLANNING_PREPARATION_CONTRACT_VERSION = "0.1"
  }
}

private data class StoredRecoveryIdentity(
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val provenanceTuple: List<String>,
)

private fun Connection.upsertPreparedRow(record: GoalPlanningPreparationRecord): Boolean = prepareStatement(
  """
    INSERT INTO goal_planning_preparations (
      parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
      governed_sub_spec_path, preparation_status, contract_version, parent_spec_hash,
      sub_spec_hash, decomposition_manifest_hash, phase_output_contract_id,
      phase_output_contract_version, preplan_payload_json, plan_payload_json,
      preplan_repair_evidence_json, plan_repair_evidence_json
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(parent_goal_workflow_id, subtask_id) DO NOTHING
  """.trimIndent(),
).use { statement ->
  var index = FIRST_COLUMN_INDEX
  statement.setString(index++, record.parentGoalWorkflowId)
  statement.setString(index++, record.normalizedIssueKey)
  statement.setString(index++, record.repositoryIdentity)
  statement.setInt(index++, record.subtaskId)
  statement.setString(index++, record.governedSubSpecPath)
  statement.setString(index++, record.preparationStatus.wireValue)
  statement.setString(index++, record.contractVersion)
  statement.setString(index++, record.provenance.parentSpecHash)
  statement.setString(index++, record.provenance.subSpecHash)
  statement.setString(index++, record.provenance.decompositionManifestHash)
  statement.setString(index++, record.provenance.phaseOutputContractId)
  statement.setString(index++, record.provenance.phaseOutputContractVersion)
  statement.setString(index++, record.preplanPayload)
  statement.setString(index++, record.planPayload)
  statement.setString(index++, record.preplanRepairEvidence?.let { JsonSupport.mapToJsonString(it.toArtifactMap()) })
  statement.setString(index++, record.planRepairEvidence?.let { JsonSupport.mapToJsonString(it.toArtifactMap()) })
  statement.executeUpdate() > 0
}

private fun Connection.selectStoredRecoveryIdentity(
  parentGoalWorkflowId: String,
  subtaskId: Int,
): StoredRecoveryIdentity? = prepareStatement(
  """
    SELECT normalized_issue_key, repository_identity,
           parent_spec_hash, sub_spec_hash, decomposition_manifest_hash,
           phase_output_contract_id, phase_output_contract_version
    FROM goal_planning_preparations
    WHERE parent_goal_workflow_id = ? AND subtask_id = ?
  """.trimIndent(),
).use { statement ->
  statement.setString(FIRST_COLUMN_INDEX, parentGoalWorkflowId)
  statement.setInt(SECOND_COLUMN_INDEX, subtaskId)
  statement.executeQuery().use { rows ->
    if (!rows.next()) return null
    StoredRecoveryIdentity(
      normalizedIssueKey = rows.getString("normalized_issue_key"),
      repositoryIdentity = rows.getString("repository_identity"),
      provenanceTuple = listOf(
        rows.getString("parent_spec_hash"),
        rows.getString("sub_spec_hash"),
        rows.getString("decomposition_manifest_hash"),
        rows.getString("phase_output_contract_id"),
        rows.getString("phase_output_contract_version"),
      ),
    )
  }
}

private fun Connection.selectRecord(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord? =
  prepareStatement(selectRecordSql()).use { statement ->
    statement.setString(FIRST_COLUMN_INDEX, parentGoalWorkflowId)
    statement.setInt(SECOND_COLUMN_INDEX, subtaskId)
    statement.executeQuery().use { rows ->
      if (!rows.next()) return null
      rows.toRecord()
    }
  }

private fun Connection.selectOrderedByGoal(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
  prepareStatement(
    """
    ${selectRecordColumns()}
    FROM goal_planning_preparations
    WHERE parent_goal_workflow_id = ?
    ORDER BY subtask_id
    """.trimIndent(),
  ).use { statement ->
    statement.setString(FIRST_COLUMN_INDEX, parentGoalWorkflowId)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          add(rows.toRecord())
        }
      }
    }
  }

private fun Connection.countPrepared(parentGoalWorkflowId: String): Int = prepareStatement(
  """
    SELECT COUNT(*)
    FROM goal_planning_preparations
    WHERE parent_goal_workflow_id = ? AND preparation_status = 'prepared'
  """.trimIndent(),
).use { statement ->
  statement.setString(FIRST_COLUMN_INDEX, parentGoalWorkflowId)
  statement.executeQuery().use { rows ->
    rows.next()
    rows.getInt(FIRST_COLUMN_INDEX)
  }
}

private fun Connection.preparedSubtaskStatuses(parentGoalWorkflowId: String): Map<Int, String> = prepareStatement(
  """
    SELECT subtask_id, preparation_status
    FROM goal_planning_preparations
    WHERE parent_goal_workflow_id = ?
  """.trimIndent(),
).use { statement ->
  statement.setString(FIRST_COLUMN_INDEX, parentGoalWorkflowId)
  statement.executeQuery().use { rows ->
    buildMap {
      while (rows.next()) {
        put(rows.getInt("subtask_id"), rows.getString("preparation_status"))
      }
    }
  }
}

private fun Connection.selectStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus? =
  prepareStatement(
    """
    SELECT parent_goal_workflow_id, subtask_id, preparation_status, contract_version,
           parent_spec_hash, sub_spec_hash, decomposition_manifest_hash,
           phase_output_contract_id, phase_output_contract_version
    FROM goal_planning_preparations
    WHERE parent_goal_workflow_id = ? AND subtask_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(FIRST_COLUMN_INDEX, parentGoalWorkflowId)
    statement.setInt(SECOND_COLUMN_INDEX, subtaskId)
    statement.executeQuery().use { rows ->
      if (!rows.next()) return null
      val label = statusLabel(rows)
      val contractVersion = requireColumn(rows, label, "contract_version")
      if (contractVersion != "0.1") {
        throw InvalidGoalPlanningPreparationSchemaError(
          sourceLabel = label,
          fieldPath = "contract_version",
          reason = incompatibleLoadedVersionReason(contractVersion),
        )
      }
      GoalPlanningPreparationStatus(
        parentGoalWorkflowId = rows.getString("parent_goal_workflow_id"),
        subtaskId = rows.getInt("subtask_id"),
        preparationStatus = decodeState(label, rows.getString("preparation_status")),
        provenance = GoalPlanningPreparationProvenance(
          parentSpecHash = requireColumn(rows, label, "parent_spec_hash"),
          subSpecHash = requireColumn(rows, label, "sub_spec_hash"),
          decompositionManifestHash = requireColumn(rows, label, "decomposition_manifest_hash"),
          phaseOutputContractId = requireColumn(rows, label, "phase_output_contract_id"),
          phaseOutputContractVersion = requireColumn(rows, label, "phase_output_contract_version"),
        ),
      )
    }
  }

private fun Connection.deletePreparedByGoal(parentGoalWorkflowId: String): Int = prepareStatement(
  "DELETE FROM goal_planning_preparations WHERE parent_goal_workflow_id = ?",
).use { statement ->
  statement.setString(1, parentGoalWorkflowId)
  statement.executeUpdate()
}

private fun ResultSet.toRecord(): GoalPlanningPreparationRecord {
  val parentGoalWorkflowId = getString("parent_goal_workflow_id")
  val subtaskId = getInt("subtask_id")
  val label = "$parentGoalWorkflowId#$subtaskId"
  val contractVersion = requireColumn(this, label, "contract_version")
  if (contractVersion != "0.1") {
    throw InvalidGoalPlanningPreparationSchemaError(
      sourceLabel = label,
      fieldPath = "contract_version",
      reason = incompatibleLoadedVersionReason(contractVersion),
    )
  }
  return GoalPlanningPreparationRecord(
    parentGoalWorkflowId = parentGoalWorkflowId,
    normalizedIssueKey = requireColumn(this, label, "normalized_issue_key"),
    repositoryIdentity = requireColumn(this, label, "repository_identity"),
    subtaskId = subtaskId,
    governedSubSpecPath = requireColumn(this, label, "governed_sub_spec_path"),
    preparationStatus = decodeState(label, getString("preparation_status")),
    provenance = GoalPlanningPreparationProvenance(
      parentSpecHash = requireColumn(this, label, "parent_spec_hash"),
      subSpecHash = requireColumn(this, label, "sub_spec_hash"),
      decompositionManifestHash = requireColumn(this, label, "decomposition_manifest_hash"),
      phaseOutputContractId = requireColumn(this, label, "phase_output_contract_id"),
      phaseOutputContractVersion = requireColumn(this, label, "phase_output_contract_version"),
    ),
    preplanPayload = requireColumn(this, label, "preplan_payload_json"),
    planPayload = requireColumn(this, label, "plan_payload_json"),
    preplanRepairEvidence = optionalRepairEvidence(this, label, "preplan_repair_evidence_json"),
    planRepairEvidence = optionalRepairEvidence(this, label, "plan_repair_evidence_json"),
    createdAt = getString("created_at").orEmpty(),
    updatedAt = getString("updated_at").orEmpty(),
    contractVersion = contractVersion,
  )
}

private fun selectRecordSql(): String = """
  ${selectRecordColumns()}
  FROM goal_planning_preparations
  WHERE parent_goal_workflow_id = ? AND subtask_id = ?
""".trimIndent()

private fun selectRecordColumns(): String = """
  SELECT parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
         governed_sub_spec_path, preparation_status, contract_version, parent_spec_hash,
         sub_spec_hash, decomposition_manifest_hash, phase_output_contract_id,
         phase_output_contract_version, preplan_payload_json, plan_payload_json,
         preplan_repair_evidence_json, plan_repair_evidence_json,
         created_at, updated_at
""".trimIndent()
