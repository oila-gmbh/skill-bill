package skillbill.launcher.agentrun

import skillbill.launcher.mcp.McpConfigFormat

data class GovernedReviewLaunchCapability(
  val governedOnlyTooling: Boolean,
  val mcpIsolation: Boolean,
  val configFormat: McpConfigFormat,
)
