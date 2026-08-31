package skillbill.cli.goal

import com.github.ajalt.clikt.core.UsageError
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.cli.core.refuseUnavailableAgentLaunchers
import skillbill.cli.core.requireSupportedOptionalAgentId
import skillbill.cli.featuretask.parseAgentAddonSelection
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest

internal fun validateGoalRunInputs(args: GoalRunInputValidationArgs) {
  val invokedAgentId = resolveInvokedAgentId(args.agent, args.state.environment)
  requireSupportedOptionalAgentId(args.agentOverride, "--agent-override")
  refuseUnavailableAgentLaunchers(listOf(invokedAgentId, args.agentOverride), args.executableLookup)
  val usageError = when {
    args.issueKey == null -> "issue_key is required for goal run."
    args.stopAfterSubtask != null && args.stopAfterSubtask <= 0 ->
      "--stop-after-subtask must be a positive integer."
    args.agentAddonSlugs.isNotEmpty() && args.agentAddonSelectionJson != null ->
      "Use either --agent-addon or --agent-addon-selection-json, not both."
    else -> null
  }
  if (usageError != null) throw UsageError(usageError)
}

internal fun hydrateGoalRunAgentAddonSelection(args: GoalRunAgentAddonHydrationArgs): HydratedAgentAddonSelection {
  val persistedSelection = parseAgentAddonSelection(args.agentAddonSelectionJson)
  return if (args.agentAddonSlugs.isNotEmpty()) {
    args.agentAddonSelectionPort.resolveInitial(
      repoRoot = args.effectiveRepoRoot,
      requestedSlugs = args.agentAddonSlugs,
      consumer = AgentAddonConsumer.BILL_FEATURE,
      receivingAgentIds = args.receivingAgents,
      externalSourceRoots = args.externalAgentAddonSourceConfigPort.readExternalAgentAddonSources(
        ExternalAgentAddonSourceConfigRequest(args.state.userHome, args.state.environment),
      ).sources.map { it.path },
    )
  } else if (persistedSelection.entries.isEmpty()) {
    HydratedAgentAddonSelection()
  } else {
    args.agentAddonSelectionPort.verifyPersisted(
      persistedSelection,
      AgentAddonConsumer.BILL_FEATURE,
      args.receivingAgents,
    )
  }
}
