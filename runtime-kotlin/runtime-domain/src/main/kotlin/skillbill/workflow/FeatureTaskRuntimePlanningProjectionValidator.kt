package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-137: domain-owned validator port for the canonical planning-projections schema. Concrete
 * Draft 2020-12 validation lives in infra-fs; the domain parse seam calls this port on a producing
 * phase's `produced_outputs` before building the typed projection, so `additionalProperties:false`,
 * the `compactSummary` anti-paste patterns, `uniqueItems`, and every required list are enforced at
 * runtime rather than only in the schema file.
 *
 * The domain cannot depend on infra-fs (that would invert the module graph), so the port is passed in
 * with the projection inputs, mirroring [GoalProgressEventValidator].
 */
interface FeatureTaskRuntimePlanningProjectionValidator {
  @OpenBoundaryMap("Feature-task-runtime planning projection wire map at the schema-validation seam")
  fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String)
}

/**
 * Test-only stand-in for suites that assert the typed Kotlin rules in isolation. Production wiring
 * binds the infra-fs adapter; using this in production would leave the canonical schema unenforced.
 */
object NoopFeatureTaskRuntimePlanningProjectionValidator : FeatureTaskRuntimePlanningProjectionValidator {
  override fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String) = Unit
}
