package skillbill.db.workflow

import skillbill.contracts.JsonCodec
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationStatus
import java.sql.Connection

internal object GoalPlanningPreparationRecordSqlQueries

internal fun Connection.upsertPreparedRow(record: GoalPlanningPreparationRecord): Boolean = prepareStatement(
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
  statement.setString(index++, record.preplanRepairEvidence?.let { JsonCodec.mapToJsonString(it.toArtifactMap()) })
  statement.setString(index++, record.planRepairEvidence?.let { JsonCodec.mapToJsonString(it.toArtifactMap()) })
  statement.executeUpdate() > 0
}

internal fun Connection.selectStoredRecoveryIdentity(
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

internal fun Connection.selectRecord(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord? =
  prepareStatement(selectRecordSql()).use { statement ->
    statement.setString(FIRST_COLUMN_INDEX, parentGoalWorkflowId)
    statement.setInt(SECOND_COLUMN_INDEX, subtaskId)
    statement.executeQuery().use { rows ->
      if (!rows.next()) return null
      rows.toPreparedRecord()
    }
  }

internal fun Connection.selectOrderedByGoal(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
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
          add(rows.toPreparedRecord())
        }
      }
    }
  }

internal fun Connection.countPrepared(parentGoalWorkflowId: String): Int = prepareStatement(
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

internal fun Connection.preparedSubtaskStatuses(parentGoalWorkflowId: String): Map<Int, String> = prepareStatement(
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

internal fun Connection.selectStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus? =
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

internal fun Connection.deletePreparedByGoal(parentGoalWorkflowId: String): Int = prepareStatement(
  "DELETE FROM goal_planning_preparations WHERE parent_goal_workflow_id = ?",
).use { statement ->
  statement.setString(1, parentGoalWorkflowId)
  statement.executeUpdate()
}
