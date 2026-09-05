package skillbill.infrastructure.sqlite.review

import skillbill.contracts.JsonCodec

internal fun embeddedReviewPayloads(row: Map<String, Any?>): List<ReviewHealthPayload> {
  val rawChildSteps = row.stringValue("child_steps_json")
  if (rawChildSteps.isBlank()) {
    return emptyList()
  }
  val parsed = JsonCodec.parseArrayOrEmpty(rawChildSteps)
  return if (parsed.isEmpty() && rawChildSteps.trim() != "[]") {
    listOf(ReviewHealthPayload("malformed", emptyMap()))
  } else {
    parsed.mapNotNull(::childStepToReviewPayload)
  }
}

private fun childStepToReviewPayload(childStep: Any?): ReviewHealthPayload? {
  val payload = childStep as? Map<*, *> ?: return ReviewHealthPayload("malformed", emptyMap())
  val normalized = payload.toHealthStringAnyMap()
  return if (isReviewChildStep(normalized)) {
    ReviewHealthPayload("embedded", normalized)
  } else {
    null
  }
}

private fun isReviewChildStep(payload: Map<String, Any?>): Boolean {
  val skill = payload.stringHealthValue("skill")
  return skill.endsWith("code-review") || "-code-review-" in skill
}
