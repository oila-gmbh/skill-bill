package skillbill.ports.persistence.model

import skillbill.learnings.model.LearningRecord

data class LearningResolution(
  val repoScopeKey: String?,
  val skillName: String?,
  val records: List<LearningRecord>,
)
