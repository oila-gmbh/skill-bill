package skillbill.cli

import skillbill.application.LearningDeleteResult
import skillbill.application.LearningListResult
import skillbill.application.LearningRecordResult
import skillbill.application.LearningResolveResult
import skillbill.learnings.learningEntryPayload
import skillbill.learnings.summarizeLearningEntries

internal fun LearningListResult.toPayload(): Map<String, Any?> = linkedMapOf(
  "db_path" to dbPath,
  "learnings" to learnings.map(::learningEntryPayload),
)

internal fun LearningRecordResult.toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
  putAll(learningEntryPayload(learning))
  put("db_path", dbPath)
}

internal fun LearningResolveResult.toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "db_path" to dbPath,
  "repo_scope_key" to repoScopeKey,
  "skill_name" to skillName,
  "scope_precedence" to scopePrecedence.map { it.wireName },
  "applied_learnings" to summarizeLearningEntries(learnings),
  "learnings" to learnings.map(::learningEntryPayload),
).also { payload ->
  reviewSessionId?.takeIf(String::isNotBlank)?.let { payload["review_session_id"] = it }
}

internal fun LearningDeleteResult.toPayload(): Map<String, Any?> = linkedMapOf(
  "db_path" to dbPath,
  "deleted_learning_id" to deletedLearningId,
)
