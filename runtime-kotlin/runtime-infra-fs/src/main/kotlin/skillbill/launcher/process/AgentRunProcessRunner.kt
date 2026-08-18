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
  val mcpStartupProbe: AgentRunMcpStartupProbe = AgentRunMcpStartupProbe.NONE,
  val progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE,
  val activityProbe: AgentRunActivityProbe = AgentRunActivityProbe.NONE,
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
  /**
   * Additional parent-environment keys to pass through during an isolated launch
   * (inheritEnvironment = false). Populated by the command builder for the active agent so each
   * agent contributes its own credential and endpoint keys rather than hard-coding them in the
   * infra layer. Has no effect when inheritEnvironment is true.
   */
  val environmentPassthroughKeys: Set<String> = emptySet(),
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val idlePolicy: AgentRunIdlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY,
  val conversationIsolation: ConversationIsolation? = null,
  val reviewEvidenceBroker: ReviewEvidenceBroker? = null,
  val nativeReviewOperations: NativeReviewOperationProtocol? = null,
  val reviewEvidenceEndpoint: GovernedReviewEvidenceEndpointHandle? = null,
  val spawnAuthorization: AgentRunSpawnAuthorization? = null,
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
    require((reviewEvidenceBroker == null) == (nativeReviewOperations == null)) {
      "A process review evidence transport and its pre-execution operation protocol must be supplied together."
    }
    require((reviewEvidenceBroker == null) == (reviewEvidenceEndpoint == null)) {
      "A process review evidence transport and its bound endpoint must be supplied together."
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
  val stdoutSha256: String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(stdoutBytes).joinToString("") { "%02x".format(it) },
)

interface AgentRunProcessRunner {
  fun run(request: AgentRunProcessRequest): AgentRunProcessResult
}
