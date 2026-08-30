package skillbill.mcp.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskPhaseSettlementService
import skillbill.application.learning.LearningService
import skillbill.application.review.ReviewService
import skillbill.application.system.SystemService
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.telemetry.TelemetryService
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.application.workflow.WorkflowService

@Inject
data class McpInsightServices(
  val learningService: LearningService,
  val lifecycleTelemetryService: LifecycleTelemetryService,
  val telemetryService: TelemetryService,
)

@Inject
data class McpOperationsServices(
  val reviewService: ReviewService,
  val systemService: SystemService,
  val workflowService: WorkflowService,
  val updateCheckService: UpdateCheckService,
  val featureTaskPhaseSettlementService: FeatureTaskPhaseSettlementService,
)

@Inject
class McpRuntimeServices(
  insight: McpInsightServices,
  operations: McpOperationsServices,
) {
  val learningService = insight.learningService
  val lifecycleTelemetryService = insight.lifecycleTelemetryService
  val telemetryService = insight.telemetryService
  val reviewService = operations.reviewService
  val systemService = operations.systemService
  val workflowService = operations.workflowService
  val updateCheckService = operations.updateCheckService
  val featureTaskPhaseSettlementService = operations.featureTaskPhaseSettlementService
}
