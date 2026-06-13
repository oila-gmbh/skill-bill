package skillbill.cli.model

import skillbill.model.RuntimeContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import java.nio.file.Path

data class CliRuntimeContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = System.getenv(),
  val userHome: Path = Path.of(System.getProperty("user.home")),
  val requester: HttpRequester = UnconfiguredHttpRequester,
  val workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val agentRunLauncher: AgentRunLauncher? = null,
  val goalPullRequestPort: GoalPullRequestPort? = null,
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
  )
}
