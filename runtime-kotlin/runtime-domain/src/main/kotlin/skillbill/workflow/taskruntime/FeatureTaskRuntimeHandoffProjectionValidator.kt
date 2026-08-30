package skillbill.workflow.taskruntime

import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

/**
 * Builds the delivered handoff envelope from static declarations, rejecting rather than repairing.
 *
 * Shape and contract are validated without truncating. A consumer either receives a whole
 * validated projection or the launch fails loudly with a typed error naming the projection.
 */
object FeatureTaskRuntimeHandoffProjectionValidator {
  const val COMPACT_REFERENCE_MAX_LENGTH: Int = 512

  fun validate(inputs: FeatureTaskRuntimeHandoffProjectionInputs): FeatureTaskRuntimeHandoffEnvelope {
    FeatureTaskRuntimeHandoffProjectionDeclarationChecks.rejectConflictingGateReceipts(inputs)
    FeatureTaskRuntimeHandoffProjectionDeclarationChecks.rejectDuplicateProjectionNames(inputs)
    val projections = inputs.declarations.mapNotNull { declaration ->
      FeatureTaskRuntimeHandoffProjectionDeclarationChecks.requireSameConsumer(inputs, declaration)
      FeatureTaskRuntimeHandoffProjectionDeclarationChecks.requireSupportedContractVersion(inputs, declaration)
      val resolved = FeatureTaskRuntimeHandoffProjectionFieldResolver.resolveFields(inputs, declaration)
      val fields = FeatureTaskRuntimeHandoffProjectionEnvelopeWire.enforceCheckpointPolicy(
        inputs,
        declaration,
        resolved.orEmpty(),
      )
      if (resolved == null) return@mapNotNull null
      FeatureTaskRuntimeHandoffProjectionDeclarationChecks.enforceDeclaredShape(inputs, declaration, fields)
      FeatureTaskRuntimeHandoffProjectionDeclarationChecks.enforceCompactReferences(inputs, declaration, fields)
      FeatureTaskRuntimeHandoffProjection(
        projectionName = declaration.projectionName,
        sourceRef = declaration.sourceRef,
        projectionContractId = declaration.projectionContractId,
        projectionContractVersion = declaration.projectionContractVersion,
        promptVisibility = declaration.promptVisibility,
        fields = fields,
        producerIteration = FeatureTaskRuntimeHandoffProjectionFieldResolver.resolvedProducerIteration(
          inputs,
          declaration,
        ),
      )
    }
    return FeatureTaskRuntimeHandoffEnvelope(
      consumerPhaseId = inputs.consumerPhaseId,
      projections = projections,
      repositoryCheckpoint = inputs.resolvedCheckpoint,
    )
  }

  /**
   * Deterministic locator for one private-evidence artifact: the phase-records store, the producing
   * phase, and the iteration. A consumer resolves it through the runtime's record lookup, so nothing
   * here grants a model an open retrieval capability.
   */
  fun privateEvidenceReference(producingPhaseId: String, iteration: Int): String =
    PRIVATE_EVIDENCE_LOCATOR_PREFIX + "$producingPhaseId#$iteration"

  /**
   * Joins the authoritative runtime fingerprint to the producer's own checkpoint claim. The two are
   * not comparable values — see `enforceCheckpointPolicy` — so the claim is carried as provenance,
   * not as a superseded fingerprint. Single-token, so the field stays a compact reference.
   */
  const val CHECKPOINT_PRODUCER_CLAIM_SEPARATOR: String = "+producer-claimed:"

  const val PRIVATE_EVIDENCE_LOCATOR_PREFIX: String = "$FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY/"
  const val PHASE_OUTPUT_RECEIPT_FIELD: String = "phase_output_receipt"
  const val CEREMONY_SCALING_FIELD: String = "ceremony_scaling"
  const val ADDON_CONTENT_FIELD: String = "addon_content"
}

internal fun rejectFeatureTaskRuntimeHandoffProjection(
  inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  declaration: PhaseHandoffProjectionDeclaration,
  failureKind: FeatureTaskRuntimeHandoffProjectionFailureKind,
  reason: String,
): Nothing = throw InvalidFeatureTaskRuntimeHandoffProjectionError(
  context = InvalidFeatureTaskRuntimeHandoffProjectionContext(
    workflowId = inputs.workflowId,
    consumerPhaseId = inputs.consumerPhaseId,
    projectionName = declaration.projectionName,
    projectionContractId = declaration.projectionContractId,
    projectionContractVersion = declaration.projectionContractVersion,
    failureKind = failureKind,
    reason = reason,
  ),
)
