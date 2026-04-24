package skillbill.learnings

import skillbill.review.LearningRecord

data class LearningEntry(
  val id: Int,
  val reference: String,
  val scope: LearningScope,
  val scopeKey: String,
  val status: String,
  val title: String,
  val ruleText: String,
  val rationale: String,
  val sourceReviewRunId: String?,
  val sourceFindingId: String?,
)

fun learningEntry(record: LearningRecord): LearningEntry = LearningEntry(
  id = record.id,
  reference = learningReference(record),
  scope = LearningScope.fromWireName(record.scope),
  scopeKey = record.scopeKey,
  status = record.status,
  title = record.title,
  ruleText = record.ruleText,
  rationale = record.rationale,
  sourceReviewRunId = record.sourceReviewRunId,
  sourceFindingId = record.sourceFindingId,
)

fun learningEntryPayload(entry: LearningEntry): Map<String, Any?> = mapOf(
  "reference" to entry.reference,
  "scope" to entry.scope.wireName,
  "scope_key" to entry.scopeKey,
  "status" to entry.status,
  "title" to entry.title,
  "rule_text" to entry.ruleText,
  "rationale" to entry.rationale,
  "source_review_run_id" to entry.sourceReviewRunId,
  "source_finding_id" to entry.sourceFindingId,
)

fun learningEntrySessionJson(skillName: String?, entries: List<LearningEntry>): String =
  learningSessionJson(skillName, entries.map(::learningEntryPayload))

fun summarizeLearningEntries(entries: List<LearningEntry>): String =
  if (entries.isEmpty()) "none" else entries.joinToString(", ") { it.reference }
