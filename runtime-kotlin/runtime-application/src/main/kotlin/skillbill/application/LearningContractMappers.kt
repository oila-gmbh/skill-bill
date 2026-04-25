package skillbill.application

import skillbill.application.model.LearningDeleteResult
import skillbill.application.model.LearningListResult
import skillbill.application.model.LearningRecordResult
import skillbill.application.model.LearningResolveResult
import skillbill.contracts.learning.LearningDeleteContract
import skillbill.contracts.learning.LearningEntryDto
import skillbill.contracts.learning.LearningListContract
import skillbill.contracts.learning.LearningRecordContract
import skillbill.contracts.learning.LearningResolveContract
import skillbill.learnings.model.LearningEntry

fun LearningEntry.toLearningEntryDto(): LearningEntryDto = LearningEntryDto(
  reference = reference,
  scope = scope.wireName,
  scopeKey = scopeKey,
  status = status,
  title = title,
  ruleText = ruleText,
  rationale = rationale,
  sourceReviewRunId = sourceReviewRunId,
  sourceFindingId = sourceFindingId,
)

fun LearningListResult.toLearningListContract(): LearningListContract =
  LearningListContract(dbPath = dbPath, learnings = learnings.map(LearningEntry::toLearningEntryDto))

fun LearningRecordResult.toLearningRecordContract(): LearningRecordContract =
  LearningRecordContract(dbPath = dbPath, learning = learning.toLearningEntryDto())

fun LearningResolveResult.toLearningResolveContract(): LearningResolveContract = LearningResolveContract(
  dbPath = dbPath,
  repoScopeKey = repoScopeKey,
  skillName = skillName,
  reviewSessionId = reviewSessionId,
  scopePrecedence = scopePrecedence.map { scope -> scope.wireName },
  learnings = learnings.map(LearningEntry::toLearningEntryDto),
)

fun LearningDeleteResult.toLearningDeleteContract(): LearningDeleteContract =
  LearningDeleteContract(dbPath = dbPath, deletedLearningId = deletedLearningId)
