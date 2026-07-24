package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError

/**
 * Domain port for schema-validating a delivered handoff envelope. The concrete Draft 2020-12
 * validator lives in `runtime-infra-fs`; keeping only the port here leaves `runtime-domain` free of
 * Jackson and of any contract-schema validator import.
 */
interface FeatureTaskRuntimeHandoffEnvelopeValidator {
  /**
   * Validates one envelope wire map against the canonical handoff-envelope schema. Throws
   * [InvalidFeatureTaskRuntimeHandoffProjectionError] on any violation, naming the consumer phase so
   * the rejection is diagnosable without echoing projection bodies.
   */
  @OpenBoundaryMap("Feature-task-runtime handoff envelope wire map at the schema-validation seam")
  fun validateEnvelope(envelope: Map<String, Any?>, workflowId: String? = null)
}
