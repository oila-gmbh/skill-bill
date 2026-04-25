package skillbill.ports.persistence

import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningRecord
import skillbill.learnings.LearningSourceValidation
import skillbill.learnings.UpdateLearningRequest

data class LearningResolution(
  val repoScopeKey: String?,
  val skillName: String?,
  val records: List<LearningRecord>,
)

interface LearningRepository {
  fun list(status: String): List<LearningRecord>

  fun get(id: Int): LearningRecord

  fun resolve(repoScopeKey: String?, skillName: String?): LearningResolution

  fun saveSessionLearnings(reviewSessionId: String, learningsJson: String)

  fun add(request: CreateLearningRequest, sourceValidation: LearningSourceValidation): Int

  fun edit(request: UpdateLearningRequest): LearningRecord

  fun setStatus(id: Int, status: String): LearningRecord

  fun delete(id: Int)
}
