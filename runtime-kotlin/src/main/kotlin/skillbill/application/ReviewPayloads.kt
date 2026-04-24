package skillbill.application

import skillbill.review.NumberedFinding

internal fun findingPayload(finding: NumberedFinding): Map<String, Any?> = linkedMapOf(
  "number" to finding.number,
  "finding_id" to finding.findingId,
  "severity" to finding.severity,
  "confidence" to finding.confidence,
  "location" to finding.location,
  "description" to finding.description,
)
