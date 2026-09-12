package skillbill.infrastructure.fs.launcher.agentrun

import skillbill.infrastructure.fs.launcher.mcp.McpRegistrationOperations
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import java.nio.file.Path
import kotlin.time.DurationUnit

class JunieAgentRunCommandBuilder(
  override val governedReviewLaunchCapability: GovernedReviewLaunchCapability = GovernedReviewLaunchCapability(
    governedOnlyTooling = false,
    mcpIsolation = false,
    configFormat = McpRegistrationOperations.configFormatFor(InstallAgent.JUNIE),
  ),
  private val databasePath: Path? = null,
) : AgentRunCommandBuilder {
  override val agent: InstallAgent = InstallAgent.JUNIE
  override val reviewIsolation: ReviewLaunchIsolationStrategy = ReviewLaunchIsolationStrategy.FRESH_PROCESS

  override fun build(request: SkillRunRequest): AgentRunCommand {
    requireProcessLaunch(request, reviewIsolation)
    requireGovernedReviewLaunch(request, agent, governedReviewLaunchCapability)
    require(request.auditRepairExecutionId == null) {
      "Junie cannot provide the durable session identity required for audit repair."
    }
    return goalContinuationCommand(request, agent, databasePath) ?: AgentRunCommand(
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
