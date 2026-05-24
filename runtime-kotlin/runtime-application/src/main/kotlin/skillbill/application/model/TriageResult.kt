package skillbill.application.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.review.model.NumberedFinding
import skillbill.review.model.TriageDecision

data class TriageResult(
  @OpenBoundaryMap("Triage wire payload (legacy raw-map surface)")
  val payload: Map<String, Any?>,
  val findings: List<NumberedFinding> = emptyList(),
  val recorded: List<TriageDecision> = emptyList(),
  @OpenBoundaryMap("Triage telemetry wire payload (legacy raw-map surface)")
  val telemetryPayload: Map<String, Any?>? = null,
)
