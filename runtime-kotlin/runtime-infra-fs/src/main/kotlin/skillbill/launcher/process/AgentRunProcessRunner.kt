package skillbill.launcher.process

import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal const val AGENT_RUN_OUTPUT_LIMIT_BYTES: Int = 1024 * 1024

@Suppress("LongParameterList")
data class AgentRunProcessRequest(
  val command: List<String>,
  val workingDirectory: Path,
  val timeout: Duration? = null,
  val stdinText: String? = null,
  val progressIdleTimeout: Duration? = null,
  val fileActivityGraceTimeout: Duration = DEFAULT_FILE_ACTIVITY_GRACE_TIMEOUT,
  val statusHeartbeatInterval: Duration = DEFAULT_STATUS_HEARTBEAT_INTERVAL,
  val operationDeadline: Duration? = null,
  val progressProbe: AgentRunProgressProbe = AgentRunProgressProbe.NONE,
  // SKILL-64 Subtask 3 (AC20): authoritative declared-progress probe; the
  // legacy progressProbe/activityProbe become non-authoritative hints.
  val declaredProgressProbe: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe.NONE,
  // SKILL-64 Subtask 3 (AC21, AC25): supervisor-side declared-progress emitter
  // driven from the process lifecycle. Defaults to a no-op (AC15).
  val progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE,
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
    operationDeadline?.let { deadline ->
      require(deadline.isPositive()) { "Agent run operation deadline must be positive when provided." }
    }
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
  val interrupted: Boolean,
  val spawnFailed: Boolean,
  val liveness: AgentRunLivenessSnapshot? = null,
)

interface AgentRunProcessRunner {
  fun run(request: AgentRunProcessRequest): AgentRunProcessResult
}
