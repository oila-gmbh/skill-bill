package skillbill.mcp.core

import me.tatarka.inject.annotations.Component
import skillbill.di.RuntimeComponent

@Component
abstract class McpComponent(
  @Component val runtimeComponent: RuntimeComponent,
) {
  abstract val services: McpRuntimeServices
}
