package skillbill.learnings

import skillbill.review.LearningRecord

fun learningReference(record: LearningRecord): String = "L-%03d".format(record.id)

fun learningPayload(record: LearningRecord): Map<String, Any?> = mapOf(
  "reference" to learningReference(record),
  "scope" to record.scope,
  "scope_key" to record.scopeKey,
  "title" to record.title,
  "rule_text" to record.ruleText,
  "rationale" to record.rationale,
  "source_review_run_id" to record.sourceReviewRunId,
  "source_finding_id" to record.sourceFindingId,
)

fun learningSummaryPayload(payload: Map<String, Any?>): Map<String, Any?> = mapOf(
  "reference" to payload["reference"],
  "scope" to payload["scope"],
  "title" to payload["title"],
  "rule_text" to payload["rule_text"],
)

fun scopeCounts(payloads: List<Map<String, Any?>>): Map<String, Int> = LearningScope.emptyScopeCounts().apply {
  payloads.forEach { payload ->
    val scope = LearningScope.fromWireNameOrNull(payload["scope"]?.toString()) ?: return@forEach
    put(scope.wireName, getValue(scope.wireName) + 1)
  }
}
