package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-64 Subtask 3: domain-owned validator port for durable goal-progress
 * (declared-progress) event maps. Concrete JSON-Schema validation lives in
 * infra-fs; the application durable write seam calls this port before persisting
 * a declared-progress event, mirroring [GoalObservabilityEventValidator].
 */
interface GoalProgressEventValidator {
  @OpenBoundaryMap("Goal progress event wire map at the schema-validation seam")
  fun validate(event: Map<String, Any?>, sourceLabel: String)
}

object NoopGoalProgressEventValidator : GoalProgressEventValidator {
  override fun validate(event: Map<String, Any?>, sourceLabel: String) = Unit
}
