package skillbill.ports.persistence

import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDispositionRecord
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGeneration
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGenerationIdentity
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewSummary

interface ReviewGenerationRepository {
  fun appendGeneration(generation: GoalSubtaskReviewGeneration)
  fun appendPass(workflowId: String, generationId: String, passNumber: Int, repositoryCheckpoint: String)
  fun appendFinding(workflowId: String, generationId: String, passNumber: Int, finding: GoalSubtaskReviewFinding)
  fun appendDisposition(record: GoalSubtaskReviewFindingDispositionRecord)
  fun loadGeneration(identity: GoalSubtaskReviewGenerationIdentity): GoalSubtaskReviewGeneration?
  fun hasGenerations(workflowId: String): Boolean
  fun unresolvedBlockers(workflowId: String): List<GoalSubtaskReviewFinding>
  fun summary(workflowId: String): GoalSubtaskReviewSummary
}

object UnavailableReviewGenerationRepository : ReviewGenerationRepository {
  private fun unavailable(): Nothing = error("Review-generation persistence is unavailable.")

  override fun appendGeneration(generation: GoalSubtaskReviewGeneration) = unavailable()
  override fun appendPass(workflowId: String, generationId: String, passNumber: Int, repositoryCheckpoint: String) =
    unavailable()
  override fun appendFinding(
    workflowId: String,
    generationId: String,
    passNumber: Int,
    finding: GoalSubtaskReviewFinding,
  ) = unavailable()
  override fun appendDisposition(record: GoalSubtaskReviewFindingDispositionRecord) = unavailable()
  override fun loadGeneration(identity: GoalSubtaskReviewGenerationIdentity): GoalSubtaskReviewGeneration? =
    unavailable()
  override fun hasGenerations(workflowId: String): Boolean = unavailable()
  override fun unresolvedBlockers(workflowId: String): List<GoalSubtaskReviewFinding> = unavailable()
  override fun summary(workflowId: String): GoalSubtaskReviewSummary = unavailable()
}
