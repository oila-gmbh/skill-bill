package skillbill.ports.persistence

import skillbill.review.model.FeatureImplementWorkflowStats
import skillbill.review.model.FeatureTaskRuntimeWorkflowStats
import skillbill.review.model.FeatureVerifyWorkflowStats

interface WorkflowStatsRepository {
  fun featureImplementStats(): FeatureImplementWorkflowStats

  fun featureVerifyStats(): FeatureVerifyWorkflowStats

  fun featureTaskRuntimeStats(): FeatureTaskRuntimeWorkflowStats
}
