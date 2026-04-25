package skillbill.ports.persistence

import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningRecord
import skillbill.learnings.model.LearningSourceValidation
import skillbill.learnings.model.UpdateLearningRequest
import skillbill.ports.persistence.model.LearningResolution

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
