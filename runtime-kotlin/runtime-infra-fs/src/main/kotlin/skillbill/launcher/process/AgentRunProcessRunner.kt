package skillbill.launcher.process

import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunLivenessSnapshot
import skillbill.ports.agentrun.model.AgentRunMcpStartupProbe
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.ports.review.ReviewEvidenceBroker
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal const val AGENT_RUN_OUTPUT_LIMIT_BYTES: Int = 1024 * 1024

fun interface AgentRunActivityProbe {
  fun activityToken(): String?

  fun activityLabel(): String? = null

  companion object {
    val NONE: AgentRunActivityProbe = AgentRunActivityProbe { null }
  }
}

/**
 * Liveness observations available when the durable-progress idle window has elapsed.
 * [lastOutputNanos] is null until the child has written its first stdout/stderr byte.
 */
data class AgentRunIdleSignals(
  val lastLiveHeartbeatNanos: Long,
  val lastOutputNanos: Long?,
  val idleTimeoutNanos: Long,
  val nowNanos: Long,
)

fun interface AgentRunIdlePolicy {
  fun extendIdleWindow(signals: AgentRunIdleSignals): Boolean

  companion object {
    val HEARTBEAT_EXTENDED: AgentRunIdlePolicy = AgentRunIdlePolicy { signals ->
      signals.nowNanos - signals.lastLiveHeartbeatNanos < signals.idleTimeoutNanos
    }
    val DB_PROGRESS_ONLY: AgentRunIdlePolicy = AgentRunIdlePolicy { false }

    /**
     * For launches that report progress only by producing output. Incremental provider output
     * keeps the window open; a silent child still dies at the idle deadline.
     */
    val OUTPUT_EXTENDED: AgentRunIdlePolicy = AgentRunIdlePolicy { signals ->
      signals.lastOutputNanos?.let { observed -> signals.nowNanos - observed < signals.idleTimeoutNanos } == true
    }
  }
}

val DEFAULT_FILE_ACTIVITY_GRACE_TIMEOUT: Duration = 2.minutes
val DEFAULT_STATUS_HEARTBEAT_INTERVAL: Duration = 90.seconds

data class AgentRunProcessResult(
  val exitStatus: Int?,
  val stdout: String,
  val stdoutBytes: ByteArray = stdout.encodeToByteArray(),
  val stderr: String,
  val timedOut: Boolean,
  val interrupted: Boolean,
  val spawnFailed: Boolean,
  val liveness: AgentRunLivenessSnapshot? = null,
  // Defaulted from spawnFailed rather than to a bare false: the two are one fact, and a runner that
  // omitted this used to report "never started" for a child that ran — which downstream turns into a
  // cleared launched-model stamp. Deriving the default makes the coherent value the automatic one.
  val processStarted: Boolean = !spawnFailed,
  val mcpStartupObserved: Boolean = false,
  /** True when raw output exceeded the retention cap, so [stdout] is missing trailing content. */
  val stdoutTruncated: Boolean = false,
  val stdoutByteSize: Long = stdoutBytes.size.toLong(),
  val stdoutSha256: String = MessageDigest.getInstance("SHA-256")
    .digest(stdoutBytes).joinToString("") { "%02x".format(it) },
)

interface AgentRunProcessRunner {
  fun run(request: AgentRunProcessRequest): AgentRunProcessResult
}
