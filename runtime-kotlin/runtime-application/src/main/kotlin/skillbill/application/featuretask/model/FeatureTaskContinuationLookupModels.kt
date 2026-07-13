package skillbill.application.featuretask.model

import skillbill.ports.persistence.model.FeatureTaskWorkflowMode

sealed interface FeatureTaskContinuationLookupResult {
  data object NoMatch : FeatureTaskContinuationLookupResult
  data class Resumable(val candidate: FeatureTaskContinuationCandidate) : FeatureTaskContinuationLookupResult
  data class AlreadyRunning(val candidate: FeatureTaskContinuationCandidate) : FeatureTaskContinuationLookupResult
  data class Ambiguous(val candidates: List<FeatureTaskContinuationCandidate>) : FeatureTaskContinuationLookupResult
  data class TerminalOnly(val candidates: List<FeatureTaskContinuationCandidate>) : FeatureTaskContinuationLookupResult
}

data class FeatureTaskContinuationCandidate(
  val workflowId: String,
  val mode: FeatureTaskWorkflowMode,
  val status: String,
  val currentStep: String,
  val governedSpecPath: String,
  val updatedAt: String?,
  val summary: String,
)
