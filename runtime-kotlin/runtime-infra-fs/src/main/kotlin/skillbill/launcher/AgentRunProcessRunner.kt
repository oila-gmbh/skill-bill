package skillbill.launcher

import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal const val AGENT_RUN_OUTPUT_LIMIT_BYTES: Int = 1024 * 1024

data class AgentRunProcessRequest(
  val command: List<String>,
  val workingDirectory: Path,
  val timeout: Duration? = null,
  val stdinText: String? = null,
  val progressIdleTimeout: Duration? = null,
  val fileActivityGraceTimeout: Duration = DEFAULT_FILE_ACTIVITY_GRACE_TIMEOUT,
  val statusHeartbeatInterval: Duration = DEFAULT_STATUS_HEARTBEAT_INTERVAL,
  val progressProbe: AgentRunProgressProbe = AgentRunProgressProbe.NONE,
  val activityProbe: AgentRunActivityProbe = AgentRunActivityProbe.NONE,
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
) {
  init {
    require(command.isNotEmpty()) { "Agent run command is required." }
    require(command.first().isNotBlank()) { "Agent run executable is required." }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "Agent run timeout must be positive when provided." }
    }
    progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "Agent run progress idle timeout must be positive." }
    }
    require(fileActivityGraceTimeout.isPositive()) { "Agent run file activity grace timeout must be positive." }
    require(statusHeartbeatInterval.isPositive()) { "Agent run status heartbeat interval must be positive." }
  }
}

fun interface AgentRunActivityProbe {
  fun activityToken(): String?

  fun activityLabel(): String? = null

  companion object {
    val NONE: AgentRunActivityProbe = AgentRunActivityProbe { null }
  }
}

val DEFAULT_FILE_ACTIVITY_GRACE_TIMEOUT: Duration = 2.minutes
val DEFAULT_STATUS_HEARTBEAT_INTERVAL: Duration = 90.seconds

data class AgentRunProcessResult(
  val exitStatus: Int?,
  val stdout: String,
  val stderr: String,
  val timedOut: Boolean,
  val spawnFailed: Boolean,
  val liveness: AgentRunLivenessSnapshot? = null,
)

interface AgentRunProcessRunner {
  fun run(request: AgentRunProcessRequest): AgentRunProcessResult
}
