package skillbill.model

import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.telemetry.RemoteTransportPort
import skillbill.ports.telemetry.UnconfiguredRemoteTransportPort
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

data class EnvironmentContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = UnspecifiedEnvironment,
  val userHome: Path = UnspecifiedUserHome,
  val repositoryRoot: Path = UnspecifiedRepositoryRoot,
) {
  companion object {
    val UnspecifiedEnvironment: Map<String, String> = object : AbstractMap<String, String>() {
      override val entries: Set<Map.Entry<String, String>> = emptySet()
    }
    val UnspecifiedUserHome: Path = Path.of(".skillbill-unspecified-user-home")
    val UnspecifiedRepositoryRoot: Path = Path.of(".skillbill-unspecified-repository-root")
  }
}

data class TransportContext(val requester: RemoteTransportPort = UnconfiguredRemoteTransportPort)

data class WorkflowOpsContext(val workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations)

data class OptionalCallbacks(
  val agentRunLauncher: AgentRunLauncher? = null,
  val goalPullRequestPort: GoalPullRequestPort? = null,
  val executableLookup: ExecutableLookup? = null,
  val reviewNativeAgentPreflight: ReviewNativeAgentPreflightPort? = null,
  val runtimeTimingPort: RuntimeTimingPort? = null,
  val hostPlatformPort: HostPlatformPort? = null,
)

data class RuntimeContext(
  val environment: EnvironmentContext,
  val transport: TransportContext,
  val workflowOps: WorkflowOpsContext,
  val callbacks: OptionalCallbacks,
) {
  constructor(
    dbPathOverride: String? = null,
    stdinText: String? = null,
    environment: Map<String, String> = EnvironmentContext.UnspecifiedEnvironment,
    userHome: Path = EnvironmentContext.UnspecifiedUserHome,
    repositoryRoot: Path = EnvironmentContext.UnspecifiedRepositoryRoot,
    requester: RemoteTransportPort = UnconfiguredRemoteTransportPort,
    workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
    agentRunLauncher: AgentRunLauncher? = null,
    goalPullRequestPort: GoalPullRequestPort? = null,
    executableLookup: ExecutableLookup? = null,
    reviewNativeAgentPreflight: ReviewNativeAgentPreflightPort? = null,
    runtimeTimingPort: RuntimeTimingPort? = null,
    hostPlatformPort: HostPlatformPort? = null,
  ) : this(
    EnvironmentContext(dbPathOverride, stdinText, environment, userHome, repositoryRoot),
    TransportContext(requester),
    WorkflowOpsContext(workflowGitOperations),
    OptionalCallbacks(
      agentRunLauncher,
      goalPullRequestPort,
      executableLookup,
      reviewNativeAgentPreflight,
      runtimeTimingPort,
      hostPlatformPort,
    ),
  )

  companion object {
    val UnspecifiedEnvironment = EnvironmentContext.UnspecifiedEnvironment
    val UnspecifiedUserHome = EnvironmentContext.UnspecifiedUserHome
    val UnspecifiedRepositoryRoot = EnvironmentContext.UnspecifiedRepositoryRoot
  }
}
