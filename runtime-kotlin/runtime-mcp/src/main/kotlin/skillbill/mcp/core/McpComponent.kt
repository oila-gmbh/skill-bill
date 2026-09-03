package skillbill.mcp.core

import me.tatarka.inject.annotations.Component
import skillbill.di.RuntimeComponent
import java.time.Clock

@Component
abstract class McpComponent(
  @Component val runtimeComponent: RuntimeComponent,
) {
  abstract val clock: Clock

  abstract val services: McpRuntimeServices
}

internal fun mcpClock(runtimeComponent: RuntimeComponent): Clock = McpComponent::class.create(runtimeComponent).clock
