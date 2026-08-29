package skillbill.launcher.process

import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot
import skillbill.ports.agentrun.model.AgentRunMcpStartupProbe
import skillbill.ports.agentrun.model.AgentRunProgressProbe

internal fun AgentRunDeclaredProgressProbe.safeDeclaredProgress(): AgentRunDeclaredProgressSnapshot? =
  runCatching { latestDeclaredProgress() }.getOrNull()

internal fun AgentRunMcpStartupProbe.safeStartupObserved(): Boolean =
  runCatching { startupObserved() }.getOrDefault(false)

internal fun AgentRunProgressProbe.safeProgressToken(): String? =
  runCatching { progressToken() }.getOrNull()

internal fun AgentRunProgressProbe.safeProgressLabel(): String? =
  runCatching { progressLabel() }.getOrNull()

internal fun AgentRunActivityProbe.safeActivityToken(): String? = runCatching { activityToken() }.getOrNull()

internal fun AgentRunActivityProbe.safeActivityLabel(): String? = runCatching { activityLabel() }.getOrNull()
