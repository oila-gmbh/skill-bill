package skillbill.mcp.core

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Inject
import skillbill.application.learning.LearningService
import skillbill.application.review.ReviewService
import skillbill.application.system.SystemService
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.telemetry.TelemetryService
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.application.workflow.WorkflowService
import skillbill.application.featuretask.FeatureTaskContinuationLookupService
import skillbill.di.RuntimeComponent

@Component
abstract class McpComponent(
  @Component val runtimeComponent: RuntimeComponent,
) {
  abstract val services: McpRuntimeServices
}

@Inject
@Suppress("LongParameterList") // DI aggregate: one cohesive bundle for all MCP runtime services
class McpRuntimeServices(
  val featureTaskContinuationLookupService: FeatureTaskContinuationLookupService,
  val learningService: LearningService,
  val lifecycleTelemetryService: LifecycleTelemetryService,
  val reviewService: ReviewService,
  val systemService: SystemService,
  val telemetryService: TelemetryService,
  val workflowService: WorkflowService,
  val updateCheckService: UpdateCheckService,
)
