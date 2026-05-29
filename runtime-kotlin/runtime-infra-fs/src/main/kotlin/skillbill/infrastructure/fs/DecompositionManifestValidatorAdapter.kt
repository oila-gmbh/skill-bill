package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.DecompositionManifestSchemaValidator
import skillbill.workflow.DecompositionManifestValidator

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
}
