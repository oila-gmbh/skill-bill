package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.GoalPlanningPreparationValidator
import skillbill.application.featuretask.producerProjectionGateReason
import skillbill.application.featuretask.requireValidPlanningProjection
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationProgress
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.GoalPlanningPreparationEnvelopeValidator

@Inject
class GoalPlanningPreparationCheckpoint(
  private val database: DatabaseSessionFactory,
  envelopeValidator: GoalPlanningPreparationEnvelopeValidator,
  phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
) {
  private val envelopeValidator = envelopeValidator
  private val phaseOutputValidator = phaseOutputValidator
  private val gate =
    GoalPlanningPreparationProjectionGate(envelopeValidator, phaseOutputValidator, planningProjectionValidator)
  private val preparationValidator =
    GoalPlanningPreparationValidator(phaseOutputValidator, planningProjectionValidator)

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
    gate.validateSharedPreplan(checkpoint)
    database.read(dbOverride) { it.goalPlanningPreparations.checkpointSharedPreplan(checkpoint) }
  }

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint, dbOverride: String? = null) {
    gate.validateSubtaskPlan(checkpoint)
    database.read(dbOverride) { it.goalPlanningPreparations.checkpointSubtaskPlan(checkpoint) }
  }

  /**
   * Checkpoints a regenerated shared preplan, overwriting a stored record the projection gate rejects so the
   * regeneration actually lands. A stored record that still satisfies the gate keeps its immutable guard.
   */
  fun recheckpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint, dbOverride: String? = null) {
    gate.validateSharedPreplan(checkpoint)
    val stored = database.read(dbOverride) { it.goalPlanningPreparations.findSharedPreplan(checkpoint.identity) }
    if (stored != null && gate.sharedPreplanIsRegenerable(stored)) {
      database.read(dbOverride) {
        it.goalPlanningPreparations.replaceSharedPreplan(checkpoint, stored.payloadSha256)
      }
    } else {
      database.read(dbOverride) { it.goalPlanningPreparations.checkpointSharedPreplan(checkpoint) }
    }
  }

  /**
   * Checkpoints a regenerated subtask plan, overwriting a stored record the projection gate rejects so the
   * regeneration actually lands. A stored record that still satisfies the gate keeps its immutable guard.
   */
  fun recheckpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint, dbOverride: String? = null) {
    gate.validateSubtaskPlan(checkpoint)
    val stored = findStoredSubtaskPlan(
      checkpoint.identity,
      checkpoint.subtaskId,
      checkpoint.governedSubSpecPath,
      dbOverride,
    )
    if (
      stored != null &&
      (gate.subtaskPlanIsRegenerable(stored) || stored.subSpecHash != checkpoint.subSpecHash)
    ) {
      database.read(dbOverride) { it.goalPlanningPreparations.replaceSubtaskPlan(checkpoint) }
    } else {
      database.read(dbOverride) { it.goalPlanningPreparations.checkpointSubtaskPlan(checkpoint) }
    }
  }

  /** The stored subtask plan as persisted, independent of the projection verdict. */
  fun findStoredSubtaskPlan(
    identity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
    dbOverride: String? = null,
  ): GoalSubtaskPlanCheckpoint? = database.read(dbOverride) {
    it.goalPlanningPreparations.findSubtaskPlan(identity, subtaskId, governedSubSpecPath)
  }

  // A stored record whose projection no longer satisfies the gate is regenerable, not fatal: reporting it
  // as missing lets the sweep re-produce it under the same gate and re-checkpoint it, where throwing here
  // would wedge the goal terminally with no in-band repair. Structural drift still throws.
  fun findSharedPreplan(identity: GoalPlanningIdentity, dbOverride: String? = null): SharedGoalPreplanCheckpoint? =
    database.read(dbOverride) { it.goalPlanningPreparations.findSharedPreplan(identity) }
      ?.takeIf { gate.sharedPreplanRejection(it) == null }

  fun findSubtaskPlan(
    identity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
    expectedDescriptor: GovernedGoalSubtaskDescriptor? = null,
    dbOverride: String? = null,
  ): GoalSubtaskPlanCheckpoint? = database.read(dbOverride) {
    it.goalPlanningPreparations.findSubtaskPlan(identity, subtaskId, governedSubSpecPath)
  }?.let { plan ->
    val projectionRejection = gate.subtaskPlanRejection(plan)
    if (
      expectedDescriptor != null &&
      plan.manifestOrder != expectedDescriptor.manifestOrder
    ) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        identity.parentGoalWorkflowId,
        subtaskId,
        "stored manifest order differs from the authoritative decomposition manifest",
      )
    }
    if (
      expectedDescriptor != null &&
      plan.subSpecHash != expectedDescriptor.subSpecHash
    ) {
      return null
    }
    plan.takeIf { projectionRejection == null }
  }

  fun recoveryProgress(
    identity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
    expectedProvenance: GoalPlanningContractProvenance,
    dbOverride: String? = null,
  ): GoalPlanningPreparationProgress {
    val sharedPrepared = findSharedPreplan(identity, dbOverride) != null
    val prepared = orderedDescriptors.mapNotNull { descriptor ->
      findSubtaskPlan(
        identity,
        descriptor.subtaskId,
        descriptor.governedSubSpecPath,
        descriptor,
        dbOverride,
      )?.also { plan ->
        if (plan.provenance != expectedProvenance) {
          throw IncompatibleGoalPlanningPreparationRecoveryError(
            identity.parentGoalWorkflowId,
            descriptor.subtaskId,
            "stored plan provenance differs from the governing shared preplan",
          )
        }
        val parsed = skillbill.contracts.JsonSupport.parseObjectOrNull(plan.planPayload)
          ?.let(skillbill.contracts.JsonSupport::jsonElementToValue)
          ?.let(skillbill.contracts.JsonSupport::anyToStringAnyMap)
        val status = parsed?.get("status")?.toString()
        val produced = parsed?.get("produced_outputs") as? Map<*, *>
        if (status != "completed" || produced?.isEmpty() != false) {
          throw IncompatibleGoalPlanningPreparationRecoveryError(
            identity.parentGoalWorkflowId,
            descriptor.subtaskId,
            "stored plan payload has status '$status' but must be completed with non-empty produced_outputs",
          )
        }
      }
    }
    val preparedIds = prepared.mapTo(mutableSetOf()) { it.subtaskId }
    return GoalPlanningPreparationProgress(
      sharedPreplanPrepared = sharedPrepared,
      preparedPlanCount = prepared.size,
      expectedPlanCount = orderedDescriptors.size,
      firstMissingSubtaskId = orderedDescriptors.firstOrNull { it.subtaskId !in preparedIds }?.subtaskId,
    )
  }
}

/**
 * The one producer-side projection gate for durable goal planning records. Both the write seam and the
 * recovery read seam route through it, so a record can never be admitted by one and refused by the other.
 */
internal class GoalPlanningPreparationProjectionGate(
  private val envelopeValidator: GoalPlanningPreparationEnvelopeValidator,
  private val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
) {
  fun validateSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    val (label, envelope) = sharedPreplanEnvelope(checkpoint)
    envelope.requirePrepared(label)
    requireValidPlanningProjection(envelope, "preplan", label, planningProjectionValidator)
  }

  fun validateSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    val (label, envelope) = subtaskPlanEnvelope(checkpoint)
    envelope.requirePrepared(label)
    requireValidPlanningProjection(envelope, "plan", label, planningProjectionValidator)
  }

  /**
   * Null when the stored shared preplan satisfies the projection gate; the bounded reason otherwise.
   *
   * An envelope, digest, or phase-output failure is reported as a rejection rather than thrown: on a read
   * seam every one of those means the same thing operationally — the stored bytes cannot be handed to a
   * consumer — and the in-band recovery for all of them is the same regeneration. Throwing instead would
   * block every existing goal on the first contract bump with no path that can repair the record.
   */
  fun sharedPreplanRejection(checkpoint: SharedGoalPreplanCheckpoint): String? = rejectionOf {
    val (_, envelope) = sharedPreplanEnvelope(checkpoint)
    producerProjectionGateReason("preplan", envelope, planningProjectionValidator)
  }

  /** Null when the stored subtask plan satisfies the projection gate; the bounded reason otherwise. */
  fun subtaskPlanRejection(checkpoint: GoalSubtaskPlanCheckpoint): String? = rejectionOf {
    val (_, envelope) = subtaskPlanEnvelope(checkpoint)
    producerProjectionGateReason("plan", envelope, planningProjectionValidator)
  }

  private fun rejectionOf(compute: () -> String?): String? = try {
    compute()
  } catch (error: InvalidGoalPlanningPreparationSchemaError) {
    "stored record failed its durable contract: ${error.message.orEmpty()}"
  } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
    "stored record failed its durable contract: ${error.message.orEmpty()}"
  }

  private fun sharedPreplanEnvelope(checkpoint: SharedGoalPreplanCheckpoint): Pair<String, Map<String, Any?>> {
    val label = checkpoint.identity.parentGoalWorkflowId
    envelopeValidator.validate(checkpoint.toEnvelopeMap(), label)
    val envelope = phaseOutputValidator.validateAndReadPhaseOutput(checkpoint.preplanPayload, "preplan")
    requireHash(checkpoint.payloadSha256, checkpoint.preplanPayload, label)
    return label to envelope
  }

  private fun subtaskPlanEnvelope(checkpoint: GoalSubtaskPlanCheckpoint): Pair<String, Map<String, Any?>> {
    val label = "${checkpoint.identity.parentGoalWorkflowId}#${checkpoint.subtaskId}"
    envelopeValidator.validate(checkpoint.toEnvelopeMap(), label)
    val envelope = phaseOutputValidator.validateAndReadPhaseOutput(checkpoint.planPayload, "plan")
    requireHash(checkpoint.payloadSha256, checkpoint.planPayload, label)
    return label to envelope
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

  // A stored record the gate rejects — or one whose bounded envelope no longer parses at all — is
  // regenerable rather than immutable: the replacement is already gate-valid, so keeping the old bytes
  // would only wedge the goal.
  fun sharedPreplanIsRegenerable(stored: SharedGoalPreplanCheckpoint): Boolean = sharedPreplanRejection(stored) != null

  fun subtaskPlanIsRegenerable(stored: GoalSubtaskPlanCheckpoint): Boolean = subtaskPlanRejection(stored) != null
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
