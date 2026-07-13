package skillbill.application.model

import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact

internal sealed interface FeatureTaskRuntimePreparation {
  data class Prepared(val request: FeatureTaskRuntimeRunRequest) : FeatureTaskRuntimePreparation

  data class PreparationBlocked(val report: FeatureTaskRuntimeRunReport.Blocked) : FeatureTaskRuntimePreparation
}

internal sealed interface ContinuationRead {
  data object None : ContinuationRead

  data class Available(
    val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
    val baseline: GoalSubtaskReviewBaseline,
  ) : ContinuationRead

  data class Failure(val reason: String) : ContinuationRead
}
