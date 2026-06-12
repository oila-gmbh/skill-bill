package skillbill.model

import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import java.nio.file.Path

data class EnvironmentContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = UnspecifiedEnvironment,
  val userHome: Path = UnspecifiedUserHome,
) {
  companion object {
    val UnspecifiedEnvironment: Map<String, String> = object : AbstractMap<String, String>() {
      override val entries: Set<Map.Entry<String, String>> = emptySet()
    }
    val UnspecifiedUserHome: Path = Path.of("")
  }
}

data class TransportContext(val requester: HttpRequester = UnconfiguredHttpRequester)

data class WorkflowOpsContext(val workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations)

data class OptionalCallbacks(
  val agentRunLauncher: AgentRunLauncher? = null,
  val goalPullRequestPort: GoalPullRequestPort? = null,
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
    requester: HttpRequester = UnconfiguredHttpRequester,
    workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
    agentRunLauncher: AgentRunLauncher? = null,
    goalPullRequestPort: GoalPullRequestPort? = null,
  ) : this(
    EnvironmentContext(dbPathOverride, stdinText, environment, userHome),
    TransportContext(requester),
    WorkflowOpsContext(workflowGitOperations),
    OptionalCallbacks(agentRunLauncher, goalPullRequestPort),
  )

  companion object {
    val UnspecifiedEnvironment = EnvironmentContext.UnspecifiedEnvironment
    val UnspecifiedUserHome = EnvironmentContext.UnspecifiedUserHome
  }
}
