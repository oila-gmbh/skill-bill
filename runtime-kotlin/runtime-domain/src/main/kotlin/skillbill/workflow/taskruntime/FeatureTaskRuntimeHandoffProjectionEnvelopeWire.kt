package skillbill.workflow.taskruntime

import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

internal object FeatureTaskRuntimeHandoffProjectionEnvelopeWire {
  const val REPOSITORY_CHECKPOINT_FIELD: String = "repository_checkpoint"

  /**
   * Applies the declared checkpoint policy and returns the fields the consumer actually receives.
   *
   * The fingerprint a receipt carries is authored by the producing agent and is not comparable to the
   * runtime's own: the resolved value is a content hash over HEAD, the staged/unstaged diffs, and
   * untracked contents, while the carried value is whatever string the agent wrote. Comparing them
   * would reject or "refresh" on producer phrasing rather than on repository movement, so the carried
   * value is treated as an opaque claim throughout.
   *
   * `must_match` is retained as a legacy durable wire value. Like `refresh_from_repository`, it
   * requires and substitutes a freshly resolved checkpoint without rejecting repository movement.
   */
  fun enforceCheckpointPolicy(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ): List<FeatureTaskRuntimeHandoffProjectionField> {
    val carried = if (
      declaration.projectionContractId ==
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE
    ) {
      null
    } else {
      receiptCarriedCheckpointFingerprint(fields)
    }
    checkpointPolicyViolation(inputs, declaration)?.let { violation ->
      rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION,
        violation,
      )
    }
    if (declaration.checkpointPolicy == FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED) {
      return fields
    }
    val resolvedFingerprint = inputs.resolvedCheckpoint?.fingerprint ?: return fields
    val refreshed = fields.map { field ->
      resolvedCheckpointField(field, resolvedFingerprint, carried)
    }
    if (
      REPOSITORY_CHECKPOINT_FIELD in declaration.declaredFieldNames &&
      refreshed.none { it.name == REPOSITORY_CHECKPOINT_FIELD }
    ) {
      return refreshed + FeatureTaskRuntimeHandoffProjectionField(
        REPOSITORY_CHECKPOINT_FIELD,
        FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
          kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
          value = resolvedFingerprint,
        ),
      )
    }
    return refreshed
  }

  private fun checkpointPolicyViolation(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): String? = when (declaration.checkpointPolicy) {
    FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED -> null
    FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY ->
      if (inputs.resolvedCheckpoint == null) {
        "checkpoint-aware policy requires a freshly resolved repository checkpoint, none was supplied."
      } else {
        null
      }
    FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH ->
      if (inputs.resolvedCheckpoint == null) {
        "must_match requires a freshly resolved repository checkpoint, none was supplied."
      } else {
        null
      }
  }

  private fun resolvedCheckpointField(
    field: FeatureTaskRuntimeHandoffProjectionField,
    resolvedFingerprint: String,
    carriedFingerprint: String?,
  ): FeatureTaskRuntimeHandoffProjectionField = if (field.name == REPOSITORY_CHECKPOINT_FIELD) {
    field.copy(
      value = FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
        value = resolvedFingerprint +
          (carriedFingerprint?.let {
            FeatureTaskRuntimeHandoffProjectionValidator.CHECKPOINT_PRODUCER_CLAIM_SEPARATOR + it
          }.orEmpty()),
      ),
    )
  } else {
    field
  }

  // Re-projecting an already-substituted field must keep the producer's original claim rather than
  // promote the runtime fingerprint written over it, so an appended claim wins over the whole value.
  private fun receiptCarriedCheckpointFingerprint(fields: List<FeatureTaskRuntimeHandoffProjectionField>): String? =
    fields.firstOrNull { it.name == REPOSITORY_CHECKPOINT_FIELD }
      ?.value
      ?.let { it as? FeatureTaskRuntimeHandoffProjectionValue.CompactReference }
      ?.takeIf { it.kind == FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT }
      ?.value
      ?.substringAfter(FeatureTaskRuntimeHandoffProjectionValidator.CHECKPOINT_PRODUCER_CLAIM_SEPARATOR)
      ?.takeIf(String::isNotBlank)
}
