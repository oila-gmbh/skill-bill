package skillbill.application

import skillbill.learnings.LearningEntry
import skillbill.learnings.LearningScope

data class LearningListResult(
  val dbPath: String,
  val learnings: List<LearningEntry>,
)

data class LearningRecordResult(
  val dbPath: String,
  val learning: LearningEntry,
)

data class LearningResolveResult(
  val dbPath: String,
  val repoScopeKey: String?,
  val skillName: String?,
  val reviewSessionId: String?,
  val scopePrecedence: List<LearningScope>,
  val learnings: List<LearningEntry>,
)

data class LearningDeleteResult(
  val dbPath: String,
  val deletedLearningId: Int,
)
