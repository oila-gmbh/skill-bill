package skillbill.application.model

import skillbill.review.model.NumberedFinding
import skillbill.review.model.TriageDecision

data class TriageResult(
  val payload: Map<String, Any?>,
  val findings: List<NumberedFinding> = emptyList(),
  val recorded: List<TriageDecision> = emptyList(),
  val telemetryPayload: Map<String, Any?>? = null,
)
