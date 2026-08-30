package skillbill.launcher.process

import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
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

data class AgentRunProcessLaunchFields(
  val command: List<String>,
  val workingDirectory: Path,
  val stdinText: String? = null,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
)

data class AgentRunProcessTimingFields(
  val timeout: Duration? = null,
  val progressIdleTimeout: Duration? = null,
  val fileActivityGraceTimeout: Duration = DEFAULT_FILE_ACTIVITY_GRACE_TIMEOUT,
  val statusHeartbeatInterval: Duration = DEFAULT_STATUS_HEARTBEAT_INTERVAL,
  val operationDeadline: Duration? = null,
)

data class AgentRunProcessProbeFields(
  val progressProbe: AgentRunProgressProbe = AgentRunProgressProbe.NONE,
  val declaredProgressProbe: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe.NONE,
  val mcpStartupProbe: AgentRunMcpStartupProbe = AgentRunMcpStartupProbe.NONE,
  val progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE,
  val activityProbe: AgentRunActivityProbe = AgentRunActivityProbe.NONE,
  val idlePolicy: AgentRunIdlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY,
)

data class AgentRunProcessEnvironmentFields(
  val environment: Map<String, String> = emptyMap(),
  val inheritEnvironment: Boolean = true,
  val environmentPassthroughKeys: Set<String> = emptySet(),
)

data class AgentRunProcessReviewFields(
  val conversationIsolation: ConversationIsolation? = null,
  val reviewEvidenceBroker: ReviewEvidenceBroker? = null,
  val nativeReviewOperations: NativeReviewOperationProtocol? = null,
  val reviewEvidenceEndpoint: GovernedReviewEvidenceEndpointHandle? = null,
  val spawnAuthorization: AgentRunSpawnAuthorization? = null,
)

data class AgentRunProcessRequest(
  val launch: AgentRunProcessLaunchFields,
  val timing: AgentRunProcessTimingFields = AgentRunProcessTimingFields(),
  val probes: AgentRunProcessProbeFields = AgentRunProcessProbeFields(),
  val environmentFields: AgentRunProcessEnvironmentFields = AgentRunProcessEnvironmentFields(),
  val review: AgentRunProcessReviewFields = AgentRunProcessReviewFields(),
) {
  val command: List<String> get() = launch.command
  val workingDirectory: Path get() = launch.workingDirectory
  val stdinText: String? get() = launch.stdinText
  val outputSink: AgentRunOutputSink get() = launch.outputSink
  val timeout: Duration? get() = timing.timeout
  val progressIdleTimeout: Duration? get() = timing.progressIdleTimeout
  val fileActivityGraceTimeout: Duration get() = timing.fileActivityGraceTimeout
  val statusHeartbeatInterval: Duration get() = timing.statusHeartbeatInterval
  val operationDeadline: Duration? get() = timing.operationDeadline
  val progressProbe: AgentRunProgressProbe get() = probes.progressProbe
  val declaredProgressProbe: AgentRunDeclaredProgressProbe get() = probes.declaredProgressProbe
  val mcpStartupProbe: AgentRunMcpStartupProbe get() = probes.mcpStartupProbe
  val progressEmitter: AgentRunProgressEmitter get() = probes.progressEmitter
  val activityProbe: AgentRunActivityProbe get() = probes.activityProbe
  val idlePolicy: AgentRunIdlePolicy get() = probes.idlePolicy
  val environment: Map<String, String> get() = environmentFields.environment
  val inheritEnvironment: Boolean get() = environmentFields.inheritEnvironment
  val environmentPassthroughKeys: Set<String> get() = environmentFields.environmentPassthroughKeys
  val conversationIsolation: ConversationIsolation? get() = review.conversationIsolation
  val reviewEvidenceBroker: ReviewEvidenceBroker? get() = review.reviewEvidenceBroker
  val nativeReviewOperations: NativeReviewOperationProtocol? get() = review.nativeReviewOperations
  val reviewEvidenceEndpoint: GovernedReviewEvidenceEndpointHandle? get() = review.reviewEvidenceEndpoint
  val spawnAuthorization: AgentRunSpawnAuthorization? get() = review.spawnAuthorization

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

fun agentRunProcessRequest(
  command: List<String>,
  workingDirectory: Path,
  configure: AgentRunProcessRequestDsl.() -> Unit = {},
): AgentRunProcessRequest = AgentRunProcessRequestDsl().apply(configure).build(command, workingDirectory)

class AgentRunProcessRequestDsl {
  var stdinText: String? = null
  var outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE
  var timeout: Duration? = null
  var progressIdleTimeout: Duration? = null
  var fileActivityGraceTimeout: Duration = DEFAULT_FILE_ACTIVITY_GRACE_TIMEOUT
  var statusHeartbeatInterval: Duration = DEFAULT_STATUS_HEARTBEAT_INTERVAL
  var operationDeadline: Duration? = null
  var progressProbe: AgentRunProgressProbe = AgentRunProgressProbe.NONE
  var declaredProgressProbe: AgentRunDeclaredProgressProbe = AgentRunDeclaredProgressProbe.NONE
  var mcpStartupProbe: AgentRunMcpStartupProbe = AgentRunMcpStartupProbe.NONE
  var progressEmitter: AgentRunProgressEmitter = AgentRunProgressEmitter.NONE
  var activityProbe: AgentRunActivityProbe = AgentRunActivityProbe.NONE
  var idlePolicy: AgentRunIdlePolicy = AgentRunIdlePolicy.DB_PROGRESS_ONLY
  var environment: Map<String, String> = emptyMap()
  var inheritEnvironment: Boolean = true
  var environmentPassthroughKeys: Set<String> = emptySet()
  var conversationIsolation: ConversationIsolation? = null
  var reviewEvidenceBroker: ReviewEvidenceBroker? = null
  var nativeReviewOperations: NativeReviewOperationProtocol? = null
  var reviewEvidenceEndpoint: GovernedReviewEvidenceEndpointHandle? = null
  var spawnAuthorization: AgentRunSpawnAuthorization? = null

  internal fun build(command: List<String>, workingDirectory: Path): AgentRunProcessRequest = AgentRunProcessRequest(
    launch = AgentRunProcessLaunchFields(
      command = command,
      workingDirectory = workingDirectory,
      stdinText = stdinText,
      outputSink = outputSink,
    ),
    timing = AgentRunProcessTimingFields(
      timeout = timeout,
      progressIdleTimeout = progressIdleTimeout,
      fileActivityGraceTimeout = fileActivityGraceTimeout,
      statusHeartbeatInterval = statusHeartbeatInterval,
      operationDeadline = operationDeadline,
    ),
    probes = AgentRunProcessProbeFields(
      progressProbe = progressProbe,
      declaredProgressProbe = declaredProgressProbe,
      mcpStartupProbe = mcpStartupProbe,
      progressEmitter = progressEmitter,
      activityProbe = activityProbe,
      idlePolicy = idlePolicy,
    ),
    environmentFields = AgentRunProcessEnvironmentFields(
      environment = environment,
      inheritEnvironment = inheritEnvironment,
      environmentPassthroughKeys = environmentPassthroughKeys,
    ),
    review = AgentRunProcessReviewFields(
      conversationIsolation = conversationIsolation,
      reviewEvidenceBroker = reviewEvidenceBroker,
      nativeReviewOperations = nativeReviewOperations,
      reviewEvidenceEndpoint = reviewEvidenceEndpoint,
      spawnAuthorization = spawnAuthorization,
    ),
  )
}
