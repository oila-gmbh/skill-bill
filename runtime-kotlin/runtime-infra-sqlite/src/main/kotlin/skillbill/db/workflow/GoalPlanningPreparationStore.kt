@file:Suppress("TooManyFunctions")

package skillbill.db.workflow

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.contracts.workflow.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.db.core.inImmediateTransaction
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.persistence.GoalPlanningPreparationRepository
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationProvenance
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationState
import skillbill.ports.persistence.model.GoalPlanningPreparationStatus
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

class GoalPlanningPreparationStore(
  private val connection: Connection,
) : GoalPlanningPreparationRepository {
  override fun boundedStatus(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): GoalPlanningStatusSnapshot {
    require(orderedSubtaskIds.distinct().size == orderedSubtaskIds.size) { "Goal planning subtask ids must be unique." }
    val shared = connection.prepareStatement(
      "SELECT preparation_status FROM goal_shared_preplans WHERE parent_goal_workflow_id = ?",
    ).use { statement ->
      statement.setString(1, parentGoalWorkflowId)
      statement.executeQuery().use { result -> result.next() && result.getString(1) == "prepared" }
    }
    val plannedIds = connection.prepareStatement(
      "SELECT subtask_id, preparation_status FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? ORDER BY manifest_order, subtask_id",
    ).use { statement ->
      statement.setString(1, parentGoalWorkflowId)
      statement.executeQuery().use { result ->
        buildList {
          while (result.next()) {
            require(result.getString("preparation_status") == "prepared") {
              "Goal planning status contains a non-prepared plan checkpoint."
            }
            add(result.getInt("subtask_id"))
          }
        }
      }
    }
    require(plannedIds.all { it in orderedSubtaskIds }) { "Goal planning status contains an unknown subtask checkpoint." }
    val firstMissing = orderedSubtaskIds.firstOrNull { it !in plannedIds }
    val state = when {
      !shared -> GoalPlanningStatusState.NOT_STARTED
      firstMissing == null -> GoalPlanningStatusState.PREPARED
      plannedIds.isEmpty() -> GoalPlanningStatusState.PREPLANNED
      else -> GoalPlanningStatusState.PARTIALLY_PLANNED
    }
    val reason = when (state) {
      GoalPlanningStatusState.NOT_STARTED -> "Goal planning has not started."
      GoalPlanningStatusState.PREPLANNED -> "Shared preplan is saved; planning can resume at subtask $firstMissing."
      GoalPlanningStatusState.PARTIALLY_PLANNED -> "Saved plans will be reused; planning can resume at subtask $firstMissing."
      GoalPlanningStatusState.PREPARED -> null
    }
    return GoalPlanningStatusSnapshot(state, shared, plannedIds.size, orderedSubtaskIds.size, firstMissing, reason)
  }
  override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    requireNormalizedSharedPreplan(checkpoint)
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, 0) {
      connection.inImmediateTransaction {
        val inserted = prepareStatement(
          """INSERT INTO goal_shared_preplans (parent_goal_workflow_id, normalized_issue_key, repository_identity,
          preparation_status, contract_version, parent_spec_hash, decomposition_manifest_hash, planning_contract_id,
          planning_contract_version, phase_output_contract_id, phase_output_contract_version, payload_sha256,
          preplan_payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(parent_goal_workflow_id) DO NOTHING""",
        ).use { s ->
          val values = listOf(
            checkpoint.identity.parentGoalWorkflowId, checkpoint.identity.normalizedIssueKey,
            checkpoint.identity.repositoryIdentity, checkpoint.preparationStatus.wireValue, checkpoint.contractVersion,
            checkpoint.provenance.parentSpecHash, checkpoint.provenance.decompositionManifestHash,
            checkpoint.provenance.planningContractId, checkpoint.provenance.planningContractVersion,
            checkpoint.provenance.phaseOutputContractId, checkpoint.provenance.phaseOutputContractVersion,
            checkpoint.payloadSha256, checkpoint.preplanPayload,
          )
          values.forEachIndexed { i, value -> s.setString(i + 1, value) }
          s.executeUpdate() > 0
        }
        if (!inserted) {
          val stored = findSharedPreplan(checkpoint.identity)
          if (stored != checkpoint.copy(createdAt = stored?.createdAt.orEmpty())) {
            throw IncompatibleGoalPlanningPreparationRecoveryError(
              checkpoint.identity.parentGoalWorkflowId,
              0,
              "shared preplan checkpoint is immutable",
            )
          }
        }
      }
    }
  }

  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? {
    return translateSqlFailure(expectedIdentity.parentGoalWorkflowId, 0) {
      rejectLegacy(expectedIdentity.parentGoalWorkflowId)
      connection.prepareStatement("SELECT * FROM goal_shared_preplans WHERE parent_goal_workflow_id = ?").use { s ->
        s.setString(1, expectedIdentity.parentGoalWorkflowId)
        s.executeQuery().use { r -> if (!r.next()) null else r.toShared(expectedIdentity) }
      }
    }
  }

  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    requireNormalizedSubtaskPlan(checkpoint)
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, checkpoint.subtaskId) {
      connection.inImmediateTransaction {
        val shared = findSharedPreplan(checkpoint.identity) ?: throw InvalidGoalPlanningPreparationSchemaError(
          "${checkpoint.identity.parentGoalWorkflowId}#${checkpoint.subtaskId}",
          "parent_goal_workflow_id",
          "shared preplan must be checkpointed first",
        )
        if (shared.provenance != checkpoint.provenance) {
          throw IncompatibleGoalPlanningPreparationRecoveryError(
            checkpoint.identity.parentGoalWorkflowId,
            checkpoint.subtaskId,
            "subtask plan provenance must exactly match the governing shared preplan",
          )
        }
        val inserted = prepareStatement(
          """INSERT INTO goal_subtask_plans
          (parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
          manifest_order, governed_sub_spec_path, sub_spec_hash, preparation_status, contract_version, parent_spec_hash,
          decomposition_manifest_hash, planning_contract_id, planning_contract_version, phase_output_contract_id,
          phase_output_contract_version, payload_sha256, plan_payload_json)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(parent_goal_workflow_id, subtask_id) DO NOTHING""",
        ).use { s ->
          val values = listOf(
            checkpoint.identity.parentGoalWorkflowId, checkpoint.identity.normalizedIssueKey,
            checkpoint.identity.repositoryIdentity, checkpoint.subtaskId, checkpoint.manifestOrder,
            checkpoint.governedSubSpecPath, checkpoint.subSpecHash, checkpoint.preparationStatus.wireValue,
            checkpoint.contractVersion,
            checkpoint.provenance.parentSpecHash,
            checkpoint.provenance.decompositionManifestHash,
            checkpoint.provenance.planningContractId, checkpoint.provenance.planningContractVersion,
            checkpoint.provenance.phaseOutputContractId, checkpoint.provenance.phaseOutputContractVersion,
            checkpoint.payloadSha256, checkpoint.planPayload,
          )
          values.forEachIndexed { i, value ->
            if (value is Int) s.setInt(i + 1, value) else s.setString(i + 1, value.toString())
          }
          s.executeUpdate() > 0
        }
        val stored = findSubtaskPlan(checkpoint.identity, checkpoint.subtaskId, checkpoint.governedSubSpecPath)
        if (!inserted && stored != checkpoint.copy(createdAt = stored?.createdAt.orEmpty())) {
          throw IncompatibleGoalPlanningPreparationRecoveryError(
            checkpoint.identity.parentGoalWorkflowId,
            checkpoint.subtaskId,
            "subtask plan checkpoint is immutable",
          )
        }
      }
    }
  }

  override fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? = translateSqlFailure(expectedIdentity.parentGoalWorkflowId, subtaskId) {
    connection.prepareStatement(
      "SELECT * FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?",
    ).use { s ->
      rejectLegacy(expectedIdentity.parentGoalWorkflowId)
      s.setString(1, expectedIdentity.parentGoalWorkflowId)
      s.setInt(2, subtaskId)
      s.executeQuery().use { r -> if (!r.next()) null else r.toPlan(expectedIdentity, governedSubSpecPath) }
    }
  }

  override fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint> = translateSqlFailure(expectedIdentity.parentGoalWorkflowId, 0) {
    connection.prepareStatement(
      "SELECT * FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? ORDER BY manifest_order, subtask_id",
    ).use { s ->
      rejectLegacy(expectedIdentity.parentGoalWorkflowId)
      val descriptors = orderedDescriptors.associateBy { it.subtaskId }
      if (descriptors.size != orderedDescriptors.size) {
        throw InvalidGoalPlanningPreparationSchemaError(
          expectedIdentity.parentGoalWorkflowId,
          "ordered_descriptors",
          "subtask ids must be unique",
        )
      }
      s.setString(1, expectedIdentity.parentGoalWorkflowId)
      s.executeQuery().use { r ->
        buildList {
          while (r.next()) {
            val subtaskId = r.getInt("subtask_id")
            val descriptor = descriptors[subtaskId] ?: throw IncompatibleGoalPlanningPreparationRecoveryError(
              expectedIdentity.parentGoalWorkflowId,
              subtaskId,
              "stored plan is not present in the expected governed subtask descriptors",
            )
            val plan = r.toPlan(expectedIdentity, descriptor.governedSubSpecPath)
            if (plan.manifestOrder != descriptor.manifestOrder || plan.subSpecHash != descriptor.subSpecHash) {
              throw IncompatibleGoalPlanningPreparationRecoveryError(
                expectedIdentity.parentGoalWorkflowId,
                subtaskId,
                "stored manifest order or governed sub-spec hash differs from the expected descriptor",
              )
            }
            add(plan)
          }
        }
      }
    }
  }

  private fun rejectLegacy(workflowId: String) {
    connection.prepareStatement(
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
  override fun markPrepared(record: GoalPlanningPreparationRecord) {
    requirePreparedEnvelope(record)
    connection.inImmediateTransaction {
      if (upsertPreparedRow(record)) return@inImmediateTransaction
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

  override fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord? =
    connection.selectRecord(parentGoalWorkflowId, subtaskId)

  override fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
    connection.selectOrderedByGoal(parentGoalWorkflowId)

  override fun preparedCount(parentGoalWorkflowId: String): Int = connection.countPrepared(parentGoalWorkflowId)

  override fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int? {
    if (orderedSubtaskIds.isEmpty()) return null
    val prepared = connection.preparedSubtaskStatuses(parentGoalWorkflowId)
    return orderedSubtaskIds.firstOrNull { id -> prepared[id] != GoalPlanningPreparationState.PREPARED.wireValue }
  }

  override fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus? =
    connection.selectStatus(parentGoalWorkflowId, subtaskId)

  override fun deleteByGoal(parentGoalWorkflowId: String): Int {
    val plans = connection.prepareStatement("DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ?").use {
      it.setString(1, parentGoalWorkflowId)
      it.executeUpdate()
    }
    val shared = connection.prepareStatement("DELETE FROM goal_shared_preplans WHERE parent_goal_workflow_id = ?").use {
      it.setString(1, parentGoalWorkflowId)
      it.executeUpdate()
    }
    return plans + shared + connection.deletePreparedByGoal(parentGoalWorkflowId)
  }

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

private fun incompatibleLoadedVersionReason(loaded: String): String = "loaded contract_version '$loaded' is not '0.1'."

private fun Connection.deletePreparedByGoal(parentGoalWorkflowId: String): Int = prepareStatement(
  "DELETE FROM goal_planning_preparations WHERE parent_goal_workflow_id = ?",
).use { statement ->
  statement.setString(1, parentGoalWorkflowId)
  statement.executeUpdate()
}

private fun Connection.upsertPreparedRow(record: GoalPlanningPreparationRecord): Boolean = prepareStatement(
  """
    INSERT INTO goal_planning_preparations (
      parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
      governed_sub_spec_path, preparation_status, contract_version, parent_spec_hash,
      sub_spec_hash, decomposition_manifest_hash, phase_output_contract_id,
      phase_output_contract_version, preplan_payload_json, plan_payload_json
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
  statement.executeUpdate() > 0
}

private data class StoredRecoveryIdentity(
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val provenanceTuple: List<String>,
)

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
    createdAt = getString("created_at").orEmpty(),
    updatedAt = getString("updated_at").orEmpty(),
    contractVersion = contractVersion,
  )
}

private fun statusLabel(rows: ResultSet): String =
  "${rows.getString("parent_goal_workflow_id")}#${rows.getInt("subtask_id")}"

private fun requireColumn(rows: ResultSet, label: String, column: String): String =
  rows.getString(column) ?: throw InvalidGoalPlanningPreparationSchemaError(
    sourceLabel = label,
    fieldPath = column,
    reason = "$column is required but was null on hydrate.",
  )

private fun decodeState(label: String, value: String?): GoalPlanningPreparationState =
  GoalPlanningPreparationState.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidGoalPlanningPreparationSchemaError(
      sourceLabel = label,
      fieldPath = "preparation_status",
      reason = "preparation_status '${value.orEmpty()}' is not supported.",
    )

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
         created_at, updated_at
""".trimIndent()

private fun ResultSet.toShared(expected: GoalPlanningIdentity): SharedGoalPreplanCheckpoint {
  val label = expected.parentGoalWorkflowId
  val identity = GoalPlanningIdentity(
    requireColumn(this, label, "parent_goal_workflow_id"),
    requireColumn(this, label, "normalized_issue_key"),
    requireColumn(this, label, "repository_identity"),
  )
  if (identity != expected) {
    throw IncompatibleGoalPlanningPreparationRecoveryError(
      identity.parentGoalWorkflowId,
      0,
      "stored goal or repository identity differs from expected identity",
    )
  }
  val status = decodeState(label, requireColumn(this, label, "preparation_status"))
  if (status != GoalPlanningPreparationState.PREPARED) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      "preparation_status",
      "normalized shared preplan must be prepared",
    )
  }
  return SharedGoalPreplanCheckpoint(
    identity = identity,
    preparationStatus = status,
    provenance = GoalPlanningContractProvenance(
      requireColumn(this, label, "parent_spec_hash"),
      requireColumn(this, label, "decomposition_manifest_hash"),
      requireColumn(this, label, "planning_contract_id"),
      requireColumn(this, label, "planning_contract_version"),
      requireColumn(this, label, "phase_output_contract_id"),
      requireColumn(this, label, "phase_output_contract_version"),
    ),
    payloadSha256 = requireColumn(this, label, "payload_sha256"),
    preplanPayload = requireColumn(this, label, "preplan_payload_json"),
    createdAt = requireColumn(this, label, "created_at"),
    contractVersion = requireNormalizedVersion(this, label),
  ).also(::requireNormalizedSharedPreplan)
}

private fun ResultSet.toPlan(expected: GoalPlanningIdentity, expectedPath: String): GoalSubtaskPlanCheckpoint {
  val subtaskId = requirePositiveInt(this, expected.parentGoalWorkflowId, "subtask_id")
  val label = "${expected.parentGoalWorkflowId}#$subtaskId"
  val identity = GoalPlanningIdentity(
    requireColumn(this, label, "parent_goal_workflow_id"),
    requireColumn(this, label, "normalized_issue_key"),
    requireColumn(this, label, "repository_identity"),
  )
  val path = requireColumn(this, label, "governed_sub_spec_path")
  if (identity != expected || path != expectedPath) {
    throw IncompatibleGoalPlanningPreparationRecoveryError(
      identity.parentGoalWorkflowId,
      subtaskId,
      "stored identity or governed sub-spec differs from expected descriptor",
    )
  }
  val status = decodeState(label, requireColumn(this, label, "preparation_status"))
  if (status != GoalPlanningPreparationState.PREPARED) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      "preparation_status",
      "normalized subtask plan must be prepared",
    )
  }
  return GoalSubtaskPlanCheckpoint(
    identity = identity, subtaskId = subtaskId, manifestOrder = requireNonNegativeInt(this, label, "manifest_order"),
    governedSubSpecPath = path, subSpecHash = requireColumn(this, label, "sub_spec_hash"),
    preparationStatus = status,
    provenance = GoalPlanningContractProvenance(
      requireColumn(this, label, "parent_spec_hash"),
      requireColumn(this, label, "decomposition_manifest_hash"),
      requireColumn(this, label, "planning_contract_id"),
      requireColumn(this, label, "planning_contract_version"),
      requireColumn(this, label, "phase_output_contract_id"),
      requireColumn(this, label, "phase_output_contract_version"),
    ),
    payloadSha256 = requireColumn(this, label, "payload_sha256"),
    planPayload = requireColumn(this, label, "plan_payload_json"),
    createdAt = requireColumn(this, label, "created_at"), contractVersion = requireNormalizedVersion(this, label),
  ).also(::requireNormalizedSubtaskPlan)
}

private fun requireNormalizedVersion(rows: ResultSet, label: String): String =
  requireColumn(rows, label, "contract_version").also {
    if (it != GOAL_PLANNING_PREPARATION_CONTRACT_VERSION) {
      throw InvalidGoalPlanningPreparationSchemaError(
        label,
        "contract_version",
        "loaded contract_version '$it' is not '$GOAL_PLANNING_PREPARATION_CONTRACT_VERSION'",
      )
    }
  }

private fun requirePositiveInt(rows: ResultSet, label: String, column: String): Int = rows.getInt(column).also {
  if (rows.wasNull() || it < 1) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      column,
      "$column must be a positive integer on hydrate",
    )
  }
}

private fun requireNonNegativeInt(rows: ResultSet, label: String, column: String): Int = rows.getInt(column).also {
  if (rows.wasNull() || it < 0) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      column,
      "$column must be a non-negative integer on hydrate",
    )
  }
}

private inline fun <T> translateSqlFailure(workflowId: String, subtaskId: Int, block: () -> T): T = try {
  block()
} catch (failure: SQLException) {
  throw IncompatibleGoalPlanningPreparationRecoveryError(
    workflowId,
    subtaskId,
    "SQLite rejected the immutable planning checkpoint: ${failure.message.orEmpty()}",
    failure,
  )
}

private fun requireNormalizedSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
  val label = checkpoint.identity.parentGoalWorkflowId
  val failure = normalizedIdentityFailure(checkpoint.identity)
    ?: normalizedProvenanceFailure(checkpoint.provenance)
    ?: normalizedEnvelopeFailure(
      checkpoint.contractVersion,
      checkpoint.preparationStatus,
      checkpoint.payloadSha256,
      checkpoint.preplanPayload,
    )
  if (failure != null) {
    throw InvalidGoalPlanningPreparationSchemaError(label, failure.first, failure.second)
  }
}

private fun requireNormalizedSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
  val label = "${checkpoint.identity.parentGoalWorkflowId}#${checkpoint.subtaskId}"
  val failure = normalizedIdentityFailure(checkpoint.identity)
    ?: normalizedProvenanceFailure(checkpoint.provenance)
    ?: normalizedEnvelopeFailure(
      checkpoint.contractVersion,
      checkpoint.preparationStatus,
      checkpoint.payloadSha256,
      checkpoint.planPayload,
    )
    ?: when {
      checkpoint.subtaskId < 1 -> "subtask_id" to "subtask_id must be a positive integer"
      checkpoint.manifestOrder < 0 -> "manifest_order" to "manifest_order must be non-negative"
      checkpoint.governedSubSpecPath.isBlank() ->
        "governed_sub_spec_path" to "governed_sub_spec_path is required"
      !checkpoint.subSpecHash.isSha256() -> "sub_spec_hash" to "sub_spec_hash must be a lowercase SHA-256"
      else -> null
    }
  if (failure != null) {
    throw InvalidGoalPlanningPreparationSchemaError(label, failure.first, failure.second)
  }
}

private fun normalizedIdentityFailure(identity: GoalPlanningIdentity): Pair<String, String>? = when {
  identity.parentGoalWorkflowId.isBlank() ->
    "identity.parent_goal_workflow_id" to "parent_goal_workflow_id is required"
  identity.normalizedIssueKey.isBlank() -> "identity.normalized_issue_key" to "normalized_issue_key is required"
  identity.repositoryIdentity.isBlank() -> "identity.repository_identity" to "repository_identity is required"
  else -> null
}

private fun normalizedProvenanceFailure(provenance: GoalPlanningContractProvenance): Pair<String, String>? = when {
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
    "provenance.phase_output_contract_version" to "phase_output_contract_version is incompatible"
  else -> null
}

private fun normalizedEnvelopeFailure(
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

private fun String.isSha256(): Boolean = length == SHA256_HEX_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

private const val FIRST_COLUMN_INDEX: Int = 1
private const val SECOND_COLUMN_INDEX: Int = 2
private const val SHA256_HEX_LENGTH: Int = 64
