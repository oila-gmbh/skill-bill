package skillbill.mcp.core

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Inject
import skillbill.application.learning.LearningService
import skillbill.application.review.ReviewService
import skillbill.application.system.SystemService
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.telemetry.TelemetryService
import skillbill.application.workflow.WorkflowService
import skillbill.di.RuntimeComponent

@Component
abstract class McpComponent(
  @Component val runtimeComponent: RuntimeComponent,
) {
  abstract val services: McpRuntimeServices
}

@Inject
class McpRuntimeServices(
  val learningService: LearningService,
  val lifecycleTelemetryService: LifecycleTelemetryService,
  val reviewService: ReviewService,
  val systemService: SystemService,
  val telemetryService: TelemetryService,
  val workflowService: WorkflowService,
)
