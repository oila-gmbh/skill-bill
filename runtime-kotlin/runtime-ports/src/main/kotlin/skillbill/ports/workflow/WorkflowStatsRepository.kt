package skillbill.ports.workflow

import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.GoalWorkflowStats

interface WorkflowStatsRepository {
  fun featureVerifyStats(): FeatureVerifyWorkflowStats

  fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats

  fun goalStats(): GoalWorkflowStats
}
