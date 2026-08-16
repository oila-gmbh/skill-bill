package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.DecompositionManifestSchemaValidator
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputStructuralRepair
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputStructuralRepairDecision
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.workflow.DecompositionManifestCodec
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.DecompositionManifestRepairEvidence
import skillbill.workflow.model.DecompositionManifestRepairOperation
import skillbill.workflow.model.DecompositionManifestValidationFailureCode
import skillbill.workflow.model.DecompositionManifestValidationFormat
import skillbill.workflow.model.DecompositionManifestValidationResult
import skillbill.workflow.model.DecompositionManifestValidationSourceLocation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation

/**
 * SKILL-52.3 Subtask 1: infra-side adapter that bridges the domain-owned
 * [DecompositionManifestValidator] port to the concrete
 * [DecompositionManifestSchemaValidator] (now owned by `runtime-infra-fs`).
 *
 * Mirrors `WorkflowSnapshotValidatorAdapter`. The schema validator runs the
 * canonical JSON-Schema validation followed by the co-located coherence
 * checks, so `runtime-application` reaches both only through this port.
 * Loud-fail behavior is unchanged: the delegate throws
 * [skillbill.error.InvalidDecompositionManifestSchemaError] on any
 * schema, structural, or coherence violation.
 */
@Inject
class DecompositionManifestValidatorAdapter : DecompositionManifestValidator {
  override fun validate(manifest: Map<String, Any?>, sourceLabel: String) {
    DecompositionManifestSchemaValidator.validate(manifest, sourceLabel)
  }

  override fun validateYamlText(yamlText: String, sourceLabel: String): Map<String, Any?> =
    DecompositionManifestSchemaValidator.validateYamlText(yamlText, sourceLabel)

  override fun validateYamlTextResult(yamlText: String, sourceLabel: String): DecompositionManifestValidationResult {
    val decision = FeatureTaskRuntimePhaseOutputStructuralRepair.inspectWholeDocument(yamlText, sourceLabel)
    return when (decision) {
      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected ->
        DecompositionManifestValidationResult.Rejected(
          code = decision.code.toManifestFailureCode(),
          reason = decision.reason,
          sourceLocation = decision.sourceLocation?.toManifestLocation(),
        )
      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted -> try {
        val wireMap = DecompositionManifestSchemaValidator.validateYamlText(decision.text, sourceLabel)
        val manifest = DecompositionManifestCodec.decodeMap(wireMap, sourceLabel)
        val evidence = decision.evidence?.toManifestEvidence()
        if (evidence == null) {
          DecompositionManifestValidationResult.AcceptedUnchanged(manifest, decision.text)
        } else {
          DecompositionManifestValidationResult.AcceptedAfterRepair(manifest, decision.text, evidence)
        }
      } catch (error: InvalidDecompositionManifestSchemaError) {
        DecompositionManifestValidationResult.Rejected(
          code = DecompositionManifestValidationFailureCode.fromWire(error.failureCode),
          reason = error.reason,
        )
      }
    }
  }

  private fun FeatureTaskRuntimePhaseOutputFailureCode.toManifestFailureCode():
    DecompositionManifestValidationFailureCode =
    when (this) {
      FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED ->
        DecompositionManifestValidationFailureCode.MALFORMED
      FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT ->
        DecompositionManifestValidationFailureCode.ROOT_NOT_OBJECT
      FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY ->
        DecompositionManifestValidationFailureCode.DUPLICATE_KEY
      FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE ->
        DecompositionManifestValidationFailureCode.NO_REPAIR_CANDIDATE
      FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR,
      FeatureTaskRuntimePhaseOutputFailureCode.MULTIPLE_OUTPUT_CANDIDATES,
      -> DecompositionManifestValidationFailureCode.AMBIGUOUS_REPAIR
      FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED ->
        DecompositionManifestValidationFailureCode.REPAIR_LIMIT_EXCEEDED
      FeatureTaskRuntimePhaseOutputFailureCode.UNSUPPORTED_REPAIR ->
        DecompositionManifestValidationFailureCode.UNSUPPORTED_REPAIR
      else -> DecompositionManifestValidationFailureCode.SCHEMA_INVALID
    }

  private fun FeatureTaskRuntimePhaseOutputRepairEvidence.toManifestEvidence(): DecompositionManifestRepairEvidence =
    DecompositionManifestRepairEvidence(
      format = when (format) {
        FeatureTaskRuntimePhaseOutputFormat.JSON -> DecompositionManifestValidationFormat.JSON
        FeatureTaskRuntimePhaseOutputFormat.YAML -> DecompositionManifestValidationFormat.YAML
      },
      originalDigest = originalDigest,
      repairedDigest = repairedDigest,
      operation = when (operation) {
        FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER ->
          DecompositionManifestRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER
        FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER ->
          DecompositionManifestRepairOperation.ADD_MISSING_CLOSING_DELIMITER
        FeatureTaskRuntimePhaseOutputRepairOperation.DEDUPLICATE_KEYS ->
          error("Decomposition-manifest repair does not include duplicate-key merge.")
      },
      sourceLocation = sourceLocation.toManifestLocation(),
    )

  private fun FeatureTaskRuntimePhaseOutputSourceLocation.toManifestLocation():
    DecompositionManifestValidationSourceLocation =
    DecompositionManifestValidationSourceLocation(sourceLabel, offset, line, column)
}
