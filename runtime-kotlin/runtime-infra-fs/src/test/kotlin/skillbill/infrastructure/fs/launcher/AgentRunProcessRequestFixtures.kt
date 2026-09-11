package skillbill.infrastructure.fs.launcher

import skillbill.infrastructure.fs.launcher.process.AgentRunProcessRequest
import skillbill.infrastructure.fs.launcher.process.AgentRunProcessRequestDsl
import skillbill.infrastructure.fs.launcher.process.agentRunProcessRequest
import java.nio.file.Path

fun testAgentRunProcessRequest(
  command: List<String>,
  workingDirectory: Path,
  configure: AgentRunProcessRequestDsl.() -> Unit = {},
): AgentRunProcessRequest = agentRunProcessRequest(command, workingDirectory, configure)
