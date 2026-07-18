package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.GoalPlanningPreparationValidator
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationProgress
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.GoalPlanningPreparationEnvelopeValidator

@Inject
class GoalPlanningPreparationCheckpoint(
  private val database: DatabaseSessionFactory,
  private val envelopeValidator: GoalPlanningPreparationEnvelopeValidator,
  phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
) {
  private val phaseOutputValidator = phaseOutputValidator
  private val preparationValidator = GoalPlanningPreparationValidator(phaseOutputValidator)

  fun checkpoint(record: GoalPlanningPreparationRecord, dbOverride: String? = null) {
    validate(record)
    database.read(dbOverride) { unitOfWork -> unitOfWork.goalPlanningPreparations.markPrepared(record) }
  }

  fun validate(record: GoalPlanningPreparationRecord) {
    val sourceLabel = "${record.parentGoalWorkflowId}#${record.subtaskId}"
    envelopeValidator.validate(record.toEnvelopeMap(), sourceLabel)
    preparationValidator.validate(record)
  }

  fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint, dbOverride: String? = null) {
    validateSharedPreplan(checkpoint)
    database.read(dbOverride) { it.goalPlanningPreparations.checkpointSharedPreplan(checkpoint) }
  }

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint, dbOverride: String? = null) {
    validateSubtaskPlan(checkpoint)
    database.read(dbOverride) { it.goalPlanningPreparations.checkpointSubtaskPlan(checkpoint) }
  }

  fun findSharedPreplan(identity: GoalPlanningIdentity, dbOverride: String? = null): SharedGoalPreplanCheckpoint? =
    database.read(dbOverride) { it.goalPlanningPreparations.findSharedPreplan(identity) }
      ?.also(::validateSharedPreplan)

  fun findSubtaskPlan(
    identity: GoalPlanningIdentity,
    descriptor: GovernedGoalSubtaskDescriptor,
    dbOverride: String? = null,
  ): GoalSubtaskPlanCheckpoint? = database.read(dbOverride) {
    it.goalPlanningPreparations.findSubtaskPlan(identity, descriptor.subtaskId, descriptor.governedSubSpecPath)
  }?.also { plan ->
    validateSubtaskPlan(plan)
    if (plan.manifestOrder != descriptor.manifestOrder || plan.subSpecHash != descriptor.subSpecHash) {
      throw skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError(
        identity.parentGoalWorkflowId,
        descriptor.subtaskId,
        "stored manifest order or governed sub-spec hash differs from the expected descriptor",
      )
    }
  }

  fun recoveryProgress(
    identity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
    dbOverride: String? = null,
  ): GoalPlanningPreparationProgress {
    val sharedPrepared = findSharedPreplan(identity, dbOverride) != null
    val prepared = orderedDescriptors.mapNotNull { findSubtaskPlan(identity, it, dbOverride) }
    val preparedIds = prepared.mapTo(mutableSetOf()) { it.subtaskId }
    return GoalPlanningPreparationProgress(
      sharedPreplanPrepared = sharedPrepared,
      preparedPlanCount = prepared.size,
      expectedPlanCount = orderedDescriptors.size,
      firstMissingSubtaskId = orderedDescriptors.firstOrNull { it.subtaskId !in preparedIds }?.subtaskId,
    )
  }

  fun validateSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    val label = checkpoint.identity.parentGoalWorkflowId
    envelopeValidator.validate(checkpoint.toEnvelopeMap(), label)
    phaseOutputValidator.validateAndReadPhaseOutput(checkpoint.preplanPayload, "preplan").requirePrepared(label)
    requireHash(checkpoint.payloadSha256, checkpoint.preplanPayload, label)
  }

  fun validateSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    val label = "${checkpoint.identity.parentGoalWorkflowId}#${checkpoint.subtaskId}"
    envelopeValidator.validate(checkpoint.toEnvelopeMap(), label)
    phaseOutputValidator.validateAndReadPhaseOutput(checkpoint.planPayload, "plan").requirePrepared(label)
    requireHash(checkpoint.payloadSha256, checkpoint.planPayload, label)
  }

  private fun requireHash(expected: String, payload: String, label: String) {
    if (sha256HexUtf8(payload) != expected) {
      throw InvalidGoalPlanningPreparationSchemaError(
        label,
        "payload_sha256",
        "payload_sha256 does not match the exact UTF-8 payload bytes",
      )
    }
  }
}

private fun Map<String, Any?>.requirePrepared(label: String) {
  if (get("status") != "completed" || (get("produced_outputs") as? Map<*, *>)?.isEmpty() != false) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      "payload",
      "phase output must be completed with non-empty produced_outputs",
    )
  }
}

private fun SharedGoalPreplanCheckpoint.toEnvelopeMap(): Map<String, Any?> = linkedMapOf(
  "contract_version" to contractVersion,
  "record_type" to "shared_preplan",
  "identity" to identity.asMap(),
  "preparation_status" to preparationStatus.wireValue,
  "provenance" to provenance.asMap(),
  "payload_sha256" to payloadSha256,
  "preplan_payload" to preplanPayload,
)

private fun GoalSubtaskPlanCheckpoint.toEnvelopeMap(): Map<String, Any?> = linkedMapOf(
  "contract_version" to contractVersion, "record_type" to "subtask_plan", "identity" to identity.asMap(),
  "subtask_id" to subtaskId, "manifest_order" to manifestOrder, "governed_sub_spec_path" to governedSubSpecPath,
  "sub_spec_hash" to subSpecHash, "preparation_status" to preparationStatus.wireValue,
  "provenance" to provenance.asMap(), "payload_sha256" to payloadSha256, "plan_payload" to planPayload,
)

private fun skillbill.ports.persistence.model.GoalPlanningIdentity.asMap() = linkedMapOf(
  "parent_goal_workflow_id" to parentGoalWorkflowId,
  "normalized_issue_key" to normalizedIssueKey,
  "repository_identity" to repositoryIdentity,
)

private fun skillbill.ports.persistence.model.GoalPlanningContractProvenance.asMap() = linkedMapOf(
  "parent_spec_hash" to parentSpecHash,
  "decomposition_manifest_hash" to decompositionManifestHash,
  "planning_contract_id" to planningContractId,
  "planning_contract_version" to planningContractVersion,
  "phase_output_contract_id" to phaseOutputContractId,
  "phase_output_contract_version" to phaseOutputContractVersion,
)
