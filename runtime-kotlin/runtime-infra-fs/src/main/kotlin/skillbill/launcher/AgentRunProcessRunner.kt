package skillbill.launcher

import skillbill.ports.agentrun.model.AgentRunOutputSink
import java.nio.file.Path
import kotlin.time.Duration

internal const val AGENT_RUN_OUTPUT_LIMIT_BYTES: Int = 1024 * 1024

data class AgentRunProcessRequest(
  val command: List<String>,
  val workingDirectory: Path,
  val timeout: Duration,
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
) {
  init {
    require(command.isNotEmpty()) { "Agent run command is required." }
    require(command.first().isNotBlank()) { "Agent run executable is required." }
    require(timeout.isPositive()) { "Agent run timeout must be positive." }
  }
}

data class AgentRunProcessResult(
  val exitStatus: Int?,
  val stdout: String,
  val stderr: String,
  val timedOut: Boolean,
  val spawnFailed: Boolean,
)

interface AgentRunProcessRunner {
  fun run(request: AgentRunProcessRequest): AgentRunProcessResult
}
