package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-150: domain-owned validator port for the canonical implementation-attempt schema. Concrete
 * Draft 2020-12 validation lives in infra-fs; the recorder's append seam calls this port so a
 * malformed attempt record fails loudly before it is written rather than round-tripping silently and
 * later decoding into a continuation projection that understates the still-open obligations.
 *
 * The domain cannot depend on infra-fs (that would invert the module graph), so the port is injected,
 * mirroring [FeatureTaskRuntimeQuarantineValidator].
 */
interface FeatureTaskRuntimeImplementationAttemptValidator {
  @OpenBoundaryMap("Feature-task-runtime implementation-attempt wire map at the schema-validation seam")
  fun validateImplementationAttemptRecord(attemptRecord: Map<String, Any?>, sourceLabel: String)
}

/**
 * Test-only stand-in for suites that do not exercise the implementation-attempt schema seam.
 * Production wiring binds the infra-fs adapter; using this in production would leave the canonical
 * schema unenforced.
 */
object NoopFeatureTaskRuntimeImplementationAttemptValidator : FeatureTaskRuntimeImplementationAttemptValidator {
  override fun validateImplementationAttemptRecord(attemptRecord: Map<String, Any?>, sourceLabel: String) = Unit
}
