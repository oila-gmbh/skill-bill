package skillbill.launcher.process

import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.review.ReviewEvidenceBroker
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
  val declaredProgressProbe: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe.NONE,
  val progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE,
  val activityProbe: AgentRunActivityProbe = AgentRunActivityProbe.NONE,
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val usePtyStdio: Boolean = false,
  val idlePolicy: AgentRunIdlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY,
  val conversationIsolation: ConversationIsolation? = null,
  val reviewEvidenceBroker: ReviewEvidenceBroker? = null,
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
    require(reviewEvidenceBroker == null || conversationIsolation == ConversationIsolation.NONE) {
      "A process review evidence transport requires fresh-context isolation."
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

fun interface AgentRunIdlePolicy {
  fun extendIdleWindow(lastLiveHeartbeatNanos: Long, idleTimeoutNanos: Long, nowNanos: Long): Boolean

  companion object {
    val HEARTBEAT_EXTENDED: AgentRunIdlePolicy = AgentRunIdlePolicy { heartbeat, timeout, now ->
      now - heartbeat < timeout
    }
    val DB_PROGRESS_ONLY: AgentRunIdlePolicy = AgentRunIdlePolicy { _, _, _ -> false }
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
