package skillbill.application.review

import skillbill.contracts.JsonSupport
import skillbill.review.context.model.SpecIntentProjection

internal fun SpecIntentProjection.toProjectionPayload(): Map<String, Any?> = linkedMapOf(
  "intended_outcome" to intendedOutcome,
  "acceptance_criteria" to acceptanceCriteria,
  "constraints" to constraints,
  "non_goals" to nonGoals,
  "deferred_items" to deferredItems,
  "provenance" to linkedMapOf(
    "spec_path" to provenance.specPath,
    "content_digest" to provenance.contentDigest,
  ),
  "declared_byte_budget" to declaredByteBudget,
)

internal fun specIntentProjectionUtf8Bytes(projection: SpecIntentProjection): Int =
  JsonSupport.mapToJsonString(projection.toProjectionPayload()).toByteArray(Charsets.UTF_8).size

