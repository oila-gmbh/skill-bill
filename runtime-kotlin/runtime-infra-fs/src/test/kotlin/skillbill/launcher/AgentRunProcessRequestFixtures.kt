package skillbill.launcher

import skillbill.launcher.process.AgentRunProcessRequest
import skillbill.launcher.process.AgentRunProcessRequestDsl
import skillbill.launcher.process.agentRunProcessRequest
import java.nio.file.Path

fun testAgentRunProcessRequest(
  command: List<String>,
  workingDirectory: Path,
  configure: AgentRunProcessRequestDsl.() -> Unit = {},
): AgentRunProcessRequest = agentRunProcessRequest(command, workingDirectory, configure)
