package skillbill.learnings

import skillbill.contracts.JsonSupport
import skillbill.review.LearningRecord

fun learningReference(record: LearningRecord): String = "L-%03d".format(record.id)

fun learningPayload(record: LearningRecord): Map<String, Any?> = mapOf(
  "reference" to learningReference(record),
  "scope" to record.scope,
  "scope_key" to record.scopeKey,
  "status" to record.status,
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

fun learningSessionJson(skillName: String?, payloadEntries: List<Map<String, Any?>>): String =
  JsonSupport.mapToJsonString(
    linkedMapOf(
      "skill_name" to skillName,
      "applied_learning_count" to payloadEntries.size,
      "applied_learning_references" to payloadEntries.map { it["reference"] },
      "applied_learnings" to summarizeLearningReferences(payloadEntries),
      "scope_counts" to scopeCounts(payloadEntries),
      "learnings" to payloadEntries.map(::learningSummaryPayload),
    ),
  )

fun summarizeLearningReferences(entries: List<Map<String, Any?>>): String =
  if (entries.isEmpty()) "none" else entries.joinToString(", ") { it["reference"].toString() }
