package skillbill.application.model

import skillbill.learnings.model.LearningScope

data class AddLearningInput(
  val scope: LearningScope,
  val scopeKey: String,
  val title: String,
  val rule: String,
  val reason: String,
  val fromRun: String,
  val fromFinding: String,
)

data class EditLearningInput(
  val id: Int,
  val scope: LearningScope?,
  val scopeKey: String?,
  val title: String?,
  val rule: String?,
  val reason: String?,
)
