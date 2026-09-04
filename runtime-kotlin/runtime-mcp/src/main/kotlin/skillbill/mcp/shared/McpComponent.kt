package skillbill.mcp.shared

import me.tatarka.inject.annotations.Component
import skillbill.di.RuntimeComponent
import skillbill.di.create
import java.time.Clock

@Component
abstract class McpComponent(
  @Component val runtimeComponent: RuntimeComponent,
) {
  abstract val clock: Clock

  abstract val services: McpRuntimeServices
}

fun mcpClock(runtimeComponent: RuntimeComponent): Clock = McpComponent::class.create(runtimeComponent).clock

fun services(context: McpRuntimeContext, stdinText: String? = null): McpRuntimeServices {
  val runtimeComponent = RuntimeComponent::class.create(context.toRuntimeContext(stdinText))
  return McpComponent::class.create(runtimeComponent).services
}
