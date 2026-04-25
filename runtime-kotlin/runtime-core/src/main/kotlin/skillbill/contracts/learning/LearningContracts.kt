package skillbill.contracts.learning

import skillbill.contracts.JsonPayloadContract

data class LearningEntryDto(
  val reference: String,
  val scope: String,
  val scopeKey: String,
  val status: String,
  val title: String,
  val ruleText: String,
  val rationale: String,
  val sourceReviewRunId: String?,
  val sourceFindingId: String?,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = mapOf(
    "reference" to reference,
    "scope" to scope,
    "scope_key" to scopeKey,
    "status" to status,
    "title" to title,
    "rule_text" to ruleText,
    "rationale" to rationale,
    "source_review_run_id" to sourceReviewRunId,
    "source_finding_id" to sourceFindingId,
  )
}

data class LearningListContract(
  val dbPath: String,
  val learnings: List<LearningEntryDto>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "db_path" to dbPath,
    "learnings" to learnings.map(LearningEntryDto::toPayload),
  )
}

data class LearningRecordContract(
  val dbPath: String,
  val learning: LearningEntryDto,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    putAll(learning.toPayload())
    put("db_path", dbPath)
  }
}

data class LearningResolveContract(
  val dbPath: String,
  val repoScopeKey: String?,
  val skillName: String?,
  val reviewSessionId: String?,
  val scopePrecedence: List<String>,
  val learnings: List<LearningEntryDto>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "db_path" to dbPath,
    "repo_scope_key" to repoScopeKey,
    "skill_name" to skillName,
    "scope_precedence" to scopePrecedence,
    "applied_learnings" to summarizeLearningReferences(learnings),
    "learnings" to learnings.map(LearningEntryDto::toPayload),
  ).also { payload ->
    reviewSessionId?.takeIf(String::isNotBlank)?.let { payload["review_session_id"] = it }
  }
}

data class LearningDeleteContract(
  val dbPath: String,
  val deletedLearningId: Int,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = linkedMapOf(
    "db_path" to dbPath,
    "deleted_learning_id" to deletedLearningId,
  )
}

private fun summarizeLearningReferences(entries: List<LearningEntryDto>): String =
  if (entries.isEmpty()) "none" else entries.joinToString(", ") { it.reference }
