package skillbill.application.featuretask.model

import skillbill.ports.persistence.model.FeatureTaskWorkflowMode

sealed interface FeatureTaskContinuationLookupResult {
  data object NoMatch : FeatureTaskContinuationLookupResult
  data class Resumable(val candidate: FeatureTaskContinuationCandidate) : FeatureTaskContinuationLookupResult
  data class AlreadyRunning(val candidate: FeatureTaskContinuationCandidate) : FeatureTaskContinuationLookupResult
  data class Ambiguous(val candidates: List<FeatureTaskContinuationCandidate>) : FeatureTaskContinuationLookupResult
  data class TerminalOnly(val candidates: List<FeatureTaskContinuationCandidate>) : FeatureTaskContinuationLookupResult

  /**
   * A prepared goal for this issue already owns durable state. Goal parents are not feature-task
   * workflows — they carry no execution identity and are excluded from the standalone candidate
   * scan — so without this variant every goal-orchestrated feature looked like unstarted work and
   * the caller's continuation gate could never fire.
   */
  data class GoalContinuation(val candidate: GoalContinuationCandidate) : FeatureTaskContinuationLookupResult
}

data class GoalContinuationCandidate(
  val parentWorkflowId: String,
  val issueKey: String,
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val updatedAt: String?,
  val summary: String,
)

data class FeatureTaskContinuationCandidate(
  val workflowId: String,
  val mode: FeatureTaskWorkflowMode,
  val status: String,
  val currentStep: String,
  val governedSpecPath: String,
  val updatedAt: String?,
  val liveness: FeatureTaskContinuationLiveness?,
  val summary: String,
)

data class FeatureTaskContinuationLiveness(
  val classification: String,
  val lastEvidenceAt: String?,
  val evidence: String,
)
