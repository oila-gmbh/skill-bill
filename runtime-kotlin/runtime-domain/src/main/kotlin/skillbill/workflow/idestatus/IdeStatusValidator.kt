package skillbill.workflow.idestatus

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-148 Subtask 1: domain-owned validator port for IDE status snapshot maps.
 * Concrete JSON-Schema validation lives in infra-fs; the application emit seam
 * calls this port before returning a snapshot to CLI consumers.
 */
interface IdeStatusValidator {
  @OpenBoundaryMap("IDE status snapshot wire map at the schema-validation seam")
  fun validate(snapshot: Map<String, Any?>, sourceLabel: String)
}

object NoopIdeStatusValidator : IdeStatusValidator {
  override fun validate(snapshot: Map<String, Any?>, sourceLabel: String) = Unit
}
