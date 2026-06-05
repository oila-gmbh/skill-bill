package skillbill.ports.persistence

import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats
import skillbill.review.model.GoalWorkflowStats

interface WorkflowStatsRepository {
  fun featureImplementStats(): FeatureImplementWorkflowStats

  fun featureVerifyStats(): FeatureVerifyWorkflowStats

  fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats

  fun goalStats(): GoalWorkflowStats
}
