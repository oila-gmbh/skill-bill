package skillbill.application

import skillbill.learnings.LearningScope
import skillbill.learnings.summarizeLearningReferences
import skillbill.review.LearningRecord

internal fun learningRecordPayload(dbPath: String, record: LearningRecord): Map<String, Any?> =
  linkedMapOf<String, Any?>().apply {
    putAll(skillbill.learnings.learningPayload(record))
    put("db_path", dbPath)
  }

internal fun learningsResolvePayload(
  dbPath: String,
  repoScopeKey: String?,
  skillName: String?,
  reviewSessionId: String?,
  payloadEntries: List<Map<String, Any?>>,
): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
  "db_path" to dbPath,
  "repo_scope_key" to repoScopeKey,
  "skill_name" to skillName,
  "scope_precedence" to LearningScope.precedenceWireNames(),
  "applied_learnings" to summarizeLearningReferences(payloadEntries),
  "learnings" to payloadEntries,
).also { payload ->
  reviewSessionId?.takeIf(String::isNotBlank)?.let { payload["review_session_id"] = it }
}
