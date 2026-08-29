package skillbill.cli.model

import skillbill.cli.core.ExternalCommandRunner
import skillbill.cli.core.ProcessExternalCommandRunner
import skillbill.model.RuntimeContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.time.RuntimeTimingPort

data class CliRuntimeContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = System.getenv(),
  val externalCommandRunner: ExternalCommandRunner = ProcessExternalCommandRunner,
  val userHome: Path = Path.of(System.getProperty("user.home")),
  val requester: HttpRequester = UnconfiguredHttpRequester,
  val workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val agentRunLauncher: AgentRunLauncher? = null,
  val goalPullRequestPort: GoalPullRequestPort? = null,
  val executableLookup: ExecutableLookup? = null,
  val reviewNativeAgentPreflight: ReviewNativeAgentPreflightPort? = null,
  val runtimeTimingPort: RuntimeTimingPort? = null,
  val liveStdout: (String) -> Unit = {},
  val liveStderr: (String) -> Unit = {},
) {
  fun toRuntimeContext(): RuntimeContext = RuntimeContext(
    dbPathOverride = dbPathOverride,
    stdinText = stdinText,
    environment = environment,
    userHome = userHome,
    requester = requester,
    workflowGitOperations = workflowGitOperations,
    agentRunLauncher = agentRunLauncher,
    goalPullRequestPort = goalPullRequestPort,
    executableLookup = executableLookup,
    reviewNativeAgentPreflight = reviewNativeAgentPreflight,
    runtimeTimingPort = runtimeTimingPort,
  )
}
