package skillbill.infrastructure.fs.launcher.agentrun

import skillbill.infrastructure.fs.launcher.mcp.McpConfigFormat

data class GovernedReviewLaunchCapability(
  val governedOnlyTooling: Boolean,
  val mcpIsolation: Boolean,
  val configFormat: McpConfigFormat,
)
