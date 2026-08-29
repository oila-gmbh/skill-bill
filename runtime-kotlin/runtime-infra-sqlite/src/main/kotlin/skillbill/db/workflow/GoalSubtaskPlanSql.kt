package skillbill.db.workflow

import skillbill.db.core.inImmediateTransaction
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import java.sql.Connection
import java.sql.ResultSet

internal class GoalSubtaskPlanSql(
  private val connection: Connection,
  private val sharedPreplan: GoalSharedPreplanSql,
) {
  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    requireNormalizedSubtaskPlan(checkpoint)
    connection.inImmediateTransaction {
      val shared = translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, 0) {
        sharedPreplan.findSharedPreplan(checkpoint.identity)
      } ?: throw InvalidGoalPlanningPreparationSchemaError(
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
      val inserted = connection.insertSubtaskPlanRow(checkpoint)
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

  fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    requireNormalizedSubtaskPlan(checkpoint)
    connection.inImmediateTransaction {
      val shared = translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, 0) {
        sharedPreplan.findSharedPreplan(checkpoint.identity)
      } ?: throw InvalidGoalPlanningPreparationSchemaError(
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
      connection.prepareStatement(
        "DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?",
      ).use { s ->
        s.setString(1, checkpoint.identity.parentGoalWorkflowId)
        s.setInt(2, checkpoint.subtaskId)
        s.executeUpdate()
      }
      connection.insertSubtaskPlanRow(checkpoint)
    }
  }

  fun deleteSubtaskPlan(parentGoalWorkflowId: String, subtaskId: Int): Int {
    requireParentGoalWorkflowId(parentGoalWorkflowId)
    requirePositiveSubtaskId(parentGoalWorkflowId, subtaskId)
    return connection.prepareStatement(
      "DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?",
    ).use { statement ->
      statement.setString(1, parentGoalWorkflowId)
      statement.setInt(2, subtaskId)
      statement.executeUpdate()
    }
  }

  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? {
    connection.rejectLegacy(expectedIdentity.parentGoalWorkflowId)
    return connection.prepareStatement(
      "SELECT * FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?",
    ).use { s ->
      s.setString(1, expectedIdentity.parentGoalWorkflowId)
      s.setInt(2, subtaskId)
      s.executeQuery().use { r -> if (!r.next()) null else r.toPlan(expectedIdentity, governedSubSpecPath) }
    }
  }

  fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint> {
    connection.rejectLegacy(expectedIdentity.parentGoalWorkflowId)
    return connection.prepareStatement(
      "SELECT * FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? ORDER BY manifest_order, subtask_id",
    ).use { s ->
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

  fun deleteAllByGoal(parentGoalWorkflowId: String): Int =
    connection.prepareStatement("DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ?").use {
      it.setString(1, parentGoalWorkflowId)
      it.executeUpdate()
    }
}

internal fun Connection.insertSubtaskPlanRow(checkpoint: GoalSubtaskPlanCheckpoint): Boolean = prepareStatement(
  """INSERT INTO goal_subtask_plans
  (parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
  manifest_order, governed_sub_spec_path, sub_spec_hash, preparation_status, contract_version, parent_spec_hash,
  decomposition_manifest_hash, planning_contract_id, planning_contract_version, phase_output_contract_id,
  phase_output_contract_version, payload_sha256, plan_payload_json, repair_evidence_json)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
    checkpoint.payloadSha256, checkpoint.planPayload, checkpoint.repairEvidenceJson(),
  )
  values.forEachIndexed { i, value ->
    s.setObject(i + 1, value)
  }
  s.executeUpdate() > 0
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

private fun requireHydratedSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
  val label = "${checkpoint.identity.parentGoalWorkflowId}#${checkpoint.subtaskId}"
  val failure = normalizedIdentityFailure(checkpoint.identity)
    ?: hydratedProvenanceFailure(checkpoint.provenance)
    ?: hydratedEnvelopeFailure(
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
    repairEvidence = optionalRepairEvidence(this, label, "repair_evidence_json"),
    createdAt = requireColumn(this, label, "created_at"),
    contractVersion = requireColumn(this, label, "contract_version"),
  ).also(::requireHydratedSubtaskPlan)
}
