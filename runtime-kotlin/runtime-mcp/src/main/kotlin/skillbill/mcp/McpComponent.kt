package skillbill.mcp

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Inject
import skillbill.application.LearningService
import skillbill.application.ReviewService
import skillbill.application.SystemService
import skillbill.application.TelemetryService
import skillbill.application.WorkflowService
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
  val reviewService: ReviewService,
  val systemService: SystemService,
  val telemetryService: TelemetryService,
  val workflowService: WorkflowService,
)
