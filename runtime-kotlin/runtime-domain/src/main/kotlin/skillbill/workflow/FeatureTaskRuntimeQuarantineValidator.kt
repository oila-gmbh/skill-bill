package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-140: domain-owned validator port for the canonical quarantine schema. Concrete Draft 2020-12
 * validation lives in infra-fs; the recorder's append and read seams call this port so a malformed
 * quarantine artifact fails loudly rather than round-tripping silently.
 *
 * The domain cannot depend on infra-fs (that would invert the module graph), so the port is injected,
 * mirroring [FeatureTaskRuntimePlanningProjectionValidator].
 */
interface FeatureTaskRuntimeQuarantineValidator {
  @OpenBoundaryMap("Feature-task-runtime quarantine record wire map at the schema-validation seam")
  fun validateQuarantineRecord(quarantineRecord: Map<String, Any?>, sourceLabel: String)
}

/**
 * Test-only stand-in for suites that do not exercise the quarantine schema seam. Production wiring
 * binds the infra-fs adapter; using this in production would leave the canonical schema unenforced.
 */
object NoopFeatureTaskRuntimeQuarantineValidator : FeatureTaskRuntimeQuarantineValidator {
  override fun validateQuarantineRecord(quarantineRecord: Map<String, Any?>, sourceLabel: String) = Unit
}
