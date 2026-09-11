package skillbill.mcp.shared

import skillbill.model.EnvironmentContext
import skillbill.model.RuntimeContext
import skillbill.ports.telemetry.RemoteTransportPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

data class McpRuntimeContext(
  val requester: RemoteTransportPort? = null,
  val environment: Map<String, String> = EnvironmentContext.UnspecifiedEnvironment,
  val userHome: Path = EnvironmentContext.UnspecifiedUserHome,
  val workflowGitOperations: WorkflowGitOperations? = null,
  val repositoryRoot: Path? = null,
) {
  fun toRuntimeContext(stdinText: String? = null): RuntimeContext = RuntimeContext(
    stdinText = stdinText,
    environment = environment,
    userHome = userHome,
    repositoryRoot = repositoryRoot ?: EnvironmentContext.UnspecifiedRepositoryRoot,
    requester = requester,
    workflowGitOperations = workflowGitOperations,
  )
}
