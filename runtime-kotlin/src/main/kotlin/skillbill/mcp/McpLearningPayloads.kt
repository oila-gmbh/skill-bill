package skillbill.mcp

import skillbill.application.LearningResolveResult
import skillbill.learnings.learningEntryPayload
import skillbill.learnings.summarizeLearningEntries

internal fun LearningResolveResult.toMcpPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
  "db_path" to dbPath,
  "repo_scope_key" to repoScopeKey,
  "skill_name" to skillName,
  "scope_precedence" to scopePrecedence.map { it.wireName },
  "applied_learnings" to summarizeLearningEntries(learnings),
  "learnings" to learnings.map(::learningEntryPayload),
).also { payload ->
  reviewSessionId?.takeIf(String::isNotBlank)?.let { payload["review_session_id"] = it }
}
