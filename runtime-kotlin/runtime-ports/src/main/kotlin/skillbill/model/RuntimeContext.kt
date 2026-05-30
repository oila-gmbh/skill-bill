package skillbill.model

import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import java.nio.file.Path

data class RuntimeContext(
  val dbPathOverride: String? = null,
  val stdinText: String? = null,
  val environment: Map<String, String> = UnspecifiedEnvironment,
  val userHome: Path = UnspecifiedUserHome,
  val requester: HttpRequester = UnconfiguredHttpRequester,
  val workflowGitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val agentRunLauncher: AgentRunLauncher? = null,
  val goalPullRequestPort: GoalPullRequestPort? = null,
) {
  companion object {
    val UnspecifiedEnvironment: Map<String, String> = object : AbstractMap<String, String>() {
      override val entries: Set<Map.Entry<String, String>> = emptySet()
    }
    val UnspecifiedUserHome: Path = Path.of("")
  }
}
