package skillbill.launcher.agentrun

import skillbill.install.model.InstallAgent
import skillbill.launcher.mcp.McpRegistrationOperations
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.agentrun.model.SkillRunRequest
import kotlin.time.DurationUnit

class JunieAgentRunCommandBuilder(
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability = GovernedReviewLaunchCapability(
    governedOnlyTooling = false,
    mcpIsolation = false,
    configFormat = McpRegistrationOperations.configFormatFor(InstallAgent.JUNIE),
  ),
) : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.JUNIE
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    return goalContinuationCommand(request, agent) ?: AgentRunCommand(
      command = buildList {
        require(request.modelOverride == null && request.effortOverride == null) {
          "junie cannot honor a model/effort directive; remove its execution_matrix entry or --phase-model assignment."
        }
        add("junie")
        add("--project")
        add(request.repoRoot.toString())
        add("--output-format")
        add("text")
        add("--skip-update-check")
        request.timeout?.let { timeout ->
          add("--timeout")
          add(timeout.toLong(DurationUnit.MILLISECONDS).toString())
        }
        add(launchPrompt(request))
      },
      workingDirectory = request.repoRoot,
      timeout = request.timeout,
      environment = goalContinuationEnvironment(request),
      inheritEnvironment = request.reviewEvidenceBroker == null,
      conversationIsolation = request.conversationIsolation,
      idlePolicy = unstreamedLivenessPolicy(request),
      environmentPassthroughKeys =
      if (request.reviewEvidenceBroker != null) JUNIE_PROVIDER_PASSTHROUGH_KEYS else emptySet(),
    )
  }
}
