package skillbill.ports.agentrun.model

import skillbill.install.model.InstallAgent
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class SkillRunRequest(
  val issueKey: String,
  val repoRoot: Path,
  val subtaskId: Int? = null,
  val dbPathOverride: String? = null,
  val timeout: Duration = DEFAULT_AGENT_RUN_TIMEOUT,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    subtaskId?.let { id -> require(id > 0) { "subtaskId must be positive when provided." } }
    require(timeout.isPositive()) { "timeout must be positive." }
  }
}

enum class AgentRunOutputStream {
  STDOUT,
  STDERR,
}

fun interface AgentRunOutputSink {
  fun write(stream: AgentRunOutputStream, text: String)

  companion object {
    val NONE: AgentRunOutputSink = AgentRunOutputSink { _, _ -> }
  }
}

data class AgentRunLaunchRequest(
  val agentId: String,
  val skillRunRequest: SkillRunRequest,
) {
  init {
    require(agentId.isNotBlank()) { "agentId is required." }
  }
}

sealed interface AgentRunLaunchOutcome {
  val agent: InstallAgent
}

data class AgentRunLaunchFacts(
  override val agent: InstallAgent,
  val exitStatus: Int?,
  val stdout: String,
  val stderr: String,
  val timedOut: Boolean,
  val spawnFailed: Boolean,
) : AgentRunLaunchOutcome {
  init {
    require(!timedOut || exitStatus == null) { "timedOut launch facts must not report an exitStatus." }
    require(!spawnFailed || exitStatus == null) { "spawnFailed launch facts must not report an exitStatus." }
  }
}

data class UnsupportedAgentRunLaunch(
  override val agent: InstallAgent,
  val reason: String,
) : AgentRunLaunchOutcome {
  init {
    require(reason.isNotBlank()) { "Unsupported-agent reason is required." }
  }
}

val DEFAULT_AGENT_RUN_TIMEOUT: Duration = 60.minutes
