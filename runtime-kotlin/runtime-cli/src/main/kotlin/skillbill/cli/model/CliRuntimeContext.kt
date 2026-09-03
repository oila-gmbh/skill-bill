package skillbill.cli.model

import skillbill.cli.core.ExternalCommandRunner
import skillbill.cli.core.ProcessExternalCommandRunner
import skillbill.model.EnvironmentContext
import skillbill.model.RuntimeContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

data class CliRuntimeContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = EnvironmentContext.UnspecifiedEnvironment,
  val externalCommandRunner: ExternalCommandRunner = ProcessExternalCommandRunner,
  val userHome: Path = EnvironmentContext.UnspecifiedUserHome,
  val requester: HttpRequester = UnconfiguredHttpRequester,
  val workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val agentRunLauncher: AgentRunLauncher? = null,
  val goalPullRequestPort: GoalPullRequestPort? = null,
  val executableLookup: ExecutableLookup? = null,
  val reviewNativeAgentPreflight: ReviewNativeAgentPreflightPort? = null,
  val runtimeTimingPort: RuntimeTimingPort? = null,
  val hostPlatformPort: HostPlatformPort? = null,
  val repositoryRoot: Path? = null,
  val liveStdout: (String) -> Unit = {},
  val liveStderr: (String) -> Unit = {},
) {
  fun toRuntimeContext(dbPathOverride: String? = this.dbPathOverride, userHome: Path = this.userHome): RuntimeContext =
    RuntimeContext(
      dbPathOverride = dbPathOverride,
      stdinText = stdinText,
      environment = environment,
      userHome = userHome,
      repositoryRoot = repositoryRoot?.let(::canonicalRepositoryRoot) ?: EnvironmentContext.UnspecifiedRepositoryRoot,
      requester = requester,
      workflowGitOperations = workflowGitOperations,
      agentRunLauncher = agentRunLauncher,
      goalPullRequestPort = goalPullRequestPort,
      executableLookup = executableLookup,
      reviewNativeAgentPreflight = reviewNativeAgentPreflight,
      runtimeTimingPort = runtimeTimingPort,
      hostPlatformPort = hostPlatformPort,
    )
}

internal fun canonicalRepositoryRoot(start: Path): Path {
  val resolvedStart = start.toAbsolutePath().normalize().toRealPath()
  var candidate = resolvedStart
  while (!candidate.resolve(".git").toFile().exists()) {
    candidate = candidate.parent ?: return resolvedStart
  }
  return candidate.toRealPath()
}
