package skillbill.application.featuretask.model

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact

sealed interface FeatureTaskRuntimePreparation {
  data class Prepared(val request: FeatureTaskRuntimeRunRequest) : FeatureTaskRuntimePreparation

  data class PreparationBlocked(val report: FeatureTaskRuntimeRunReport.Blocked) : FeatureTaskRuntimePreparation
}

sealed interface ContinuationRead {
  data object None : ContinuationRead

  data class Available(
    val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    val baseline: GoalSubtaskReviewBaseline,
  ) : ContinuationRead

  // A resumed child whose review state has not been captured yet (or disappeared): its identity is
  // durable, but there is no immutable baseline to freeze into run invariants. The review phase is
  // the one that decides whether that absence blocks progress, not preparation.
  data class AvailableWithoutReviewState(
    val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  ) : ContinuationRead

  data class Failure(val reason: String) : ContinuationRead
}
