package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidDecompositionManifestSchemaError

/**
 * SKILL-52.3 Subtask 1: domain-owned validator port for decomposition
 * manifest wire payloads.
 *
 * Mirrors [WorkflowSnapshotValidator]: the concrete schema + coherence
 * validators now live in `runtime-infra-fs`, and `runtime-application`
 * reaches them only through this port. Implementations MUST run the
 * canonical JSON-Schema validation followed by the Kotlin-enforced
 * coherence checks, throwing [InvalidDecompositionManifestSchemaError] on
 * any violation so every parse/emission seam stays loud.
 *
 * The `sourceLabel` is the caller-supplied identifier (on-disk path or an
 * in-memory marker) woven into the loud-fail message.
 */
interface DecompositionManifestValidator {
  /**
   * Validates a decomposition-manifest map against the canonical schema
   * and coherence rules. Throws [InvalidDecompositionManifestSchemaError]
   * on any violation.
   */
  @OpenBoundaryMap("Decomposition manifest wire map at the schema-validation seam")
  fun validate(manifest: Map<String, Any?>, sourceLabel: String)

  /**
   * Parses [yamlText] into a decomposition-manifest map, then validates it
   * via [validate]. Returns the parsed map. Throws
   * [InvalidDecompositionManifestSchemaError] on malformed YAML, a
   * non-object root, schema violations, or coherence violations.
   */
  @OpenBoundaryMap("Parsed decomposition manifest wire map at the schema-validation seam")
  fun validateYamlText(yamlText: String, sourceLabel: String): Map<String, Any?>
}
