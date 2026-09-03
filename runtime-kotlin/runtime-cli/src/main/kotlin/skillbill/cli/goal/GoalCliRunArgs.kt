package skillbill.cli.goal

import skillbill.cli.core.CliRunInputs
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentrun.ExecutableLookup
import java.nio.file.Path

internal data class GoalRunInputValidationArgs(
  val issueKey: String?,
  val stopAfterSubtask: Int?,
  val agentAddonSlugs: List<String>,
  val agentAddonSelectionJson: String?,
  val agent: String?,
  val agentOverride: String?,
  val inputs: CliRunInputs,
  val executableLookup: ExecutableLookup,
)

internal data class GoalRunAgentAddonHydrationArgs(
  val agentAddonSlugs: List<String>,
  val agentAddonSelectionJson: String?,
  val receivingAgents: List<String>,
  val effectiveRepoRoot: Path,
  val inputs: CliRunInputs,
  val agentAddonSelectionPort: AgentAddonSelectionPort,
  val externalAgentAddonSourceConfigPort: ExternalAgentAddonSourceConfigPort,
)
