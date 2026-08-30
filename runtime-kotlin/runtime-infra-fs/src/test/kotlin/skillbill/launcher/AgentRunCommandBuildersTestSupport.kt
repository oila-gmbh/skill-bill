package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.launcher.agentrun.AgentRunCommand
import skillbill.launcher.agentrun.AgentRunCommandBuilder
import skillbill.launcher.agentrun.ClaudeAgentRunCommandBuilder
import skillbill.launcher.agentrun.CodexAgentRunCommandBuilder
import skillbill.launcher.agentrun.CursorAgentRunCommandBuilder
import skillbill.launcher.agentrun.JunieAgentRunCommandBuilder
import skillbill.launcher.agentrun.GovernedReviewLaunchCapabilityError
import skillbill.launcher.process.AgentRunIdlePolicy
import skillbill.launcher.process.AgentRunOutputDecoder
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.PhaseCompactionDirective
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.GovernedReviewEvidenceCodec
import skillbill.ports.review.GovernedReviewEvidenceEndpointDescriptor
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.ReviewEvidenceBatchRequest
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewToolCall
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal fun assertGovernedReviewLaunch(builder: AgentRunCommandBuilder, governed: SkillRunRequest) {
    if (!builder.governedReviewLaunchCapability.governedOnlyTooling ||
      !builder.governedReviewLaunchCapability.mcpIsolation
    ) {
      val error = assertFailsWith<GovernedReviewLaunchCapabilityError> { builder.build(governed) }
      assertEquals(builder.agent.id, error.provider)
      assertTrue(
        error.capability == "governed-only tooling" || error.capability == "MCP isolation",
        "Junie must name the missing capability, got '${error.capability}'",
      )
      return
    }
    val built = builder.build(governed)
    val rawFilesystemTools = setOf("Read", "Grep", "Glob", "Bash")
    assertTrue(
      built.command.none { arg -> arg.split(',').any { it in rawFilesystemTools } },
      built.command.toString(),
    )
    when (builder.agent) {
      InstallAgent.CLAUDE -> assertClaudeGovernedLaunch(built.command)
      InstallAgent.CODEX -> assertCodexGovernedLaunch(built.command)
      InstallAgent.CURSOR -> assertCursorGovernedLaunch(built, governed, rawFilesystemTools)
      else -> error("unexpected provider ${builder.agent.id}")
    }
  }

internal fun assertClaudeGovernedLaunch(command: List<String>) {
    val mcpJson = StubReviewEvidenceEndpoint.descriptor.mcpConfigPath.toString()
    assertEquals(mcpJson, command[command.indexOf("--mcp-config") + 1])
    assertTrue(command.contains("--strict-mcp-config"))
    val tools = command[command.indexOf("--tools") + 1].split(",")
    assertEquals(GovernedReviewEvidenceCodec.OPERATIONS.map { "mcp__skill-bill-review-evidence__$it" }, tools)
  }

internal fun assertCodexGovernedLaunch(command: List<String>) {
    val mcpTomlServer = "mcp_servers.${GovernedReviewEvidenceCodec.SERVER_NAME}"
    val governedOperations = GovernedReviewEvidenceCodec.OPERATIONS
    assertTrue(command.contains("--ignore-user-config"))
    val configValues = command.filterIndexed { index, _ -> index > 0 && command[index - 1] == "--config" }
    assertTrue(configValues.any { it.startsWith(mcpTomlServer) && it.contains("enabled_tools=") })
    assertTrue(
      configValues.any { value ->
        governedOperations.all { operation -> value.contains(operation) } && value.contains("enabled_tools=")
      },
    )
  }

internal fun assertCursorGovernedLaunch(
    built: AgentRunCommand,
    governed: SkillRunRequest,
    rawFilesystemTools: Set<String>,
  ) {
    val command = built.command
    val workspace = StubReviewEvidenceEndpoint.descriptor.mcpConfigPath.parent
    assertEquals(workspace.toString(), command[command.indexOf("--workspace") + 1])
    assertEquals(workspace, built.workingDirectory)
    // A review packet runs to hundreds of KB, past the 128 KB Linux caps on one argv element, so
    // the prompt travels on stdin like every other launch rather than as the trailing argument.
    assertEquals(requireNotNull(governed.promptOverride), built.stdinText)
    assertTrue(command.none { it == governed.promptOverride })
    assertTrue(command.none { it.startsWith("/bill-code-review-inline") })
    assertTrue(command.contains("--approve-mcps"))
    // --approve-mcps admits the server; only --force admits its tool calls, and a --print launch
    // auto-rejects anything it cannot prompt for. Without it the lane lists the evidence tools and
    // is refused every read, then answers from the prompt alone.
    assertTrue(command.contains("--force"))
    // This lane carries no tool allowlist of its own: the CLI honours no workspace-scoped
    // permission file, so unlike Claude's --tools there is nothing here that can deny the agent's
    // own file and shell tools. Broker-only evidence is enforced by the unread-evidence gate on
    // the returned lane, not by a config file, and no filesystem tool is named on the command.
    assertTrue(rawFilesystemTools.none { tool -> command.any { it.contains(tool) } })
  }

internal fun request(
    model: String? = null,
    effort: String? = null,
    compaction: PhaseCompactionDirective? = null,
  ): SkillRunRequest = SkillRunRequest(
    issueKey = "SKILL-113",
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    subtaskId = 1,
    timeout = 3.seconds,
    promptOverride = "Phase: implement",
    modelOverride = model,
    effortOverride = effort,
    compaction = compaction,
  )

internal fun governedReviewRequest(nativeReviewWorkerName: String? = null): SkillRunRequest = request().copy(
    conversationIsolation = ConversationIsolation.NONE,
    reviewEvidenceBroker = NoOpReviewEvidenceBroker,
    nativeReviewOperations = BrokerBackedNativeReviewOperationProtocol(NoOpReviewEvidenceBroker),
    reviewEvidenceEndpoint = StubReviewEvidenceEndpoint,
    nativeReviewWorkerName = nativeReviewWorkerName,
  )

internal object StubReviewEvidenceEndpoint : GovernedReviewEvidenceEndpointHandle {
    override val descriptor = GovernedReviewEvidenceEndpointDescriptor(
      lane = "architecture",
      socketPath = Path.of("/tmp/skill-bill-review/evidence.sock"),
      mcpConfigPath = Path.of("/tmp/skill-bill-review/mcp.json"),
      token = "launch-token",
    )

    override fun close() = Unit
  }

internal object NoOpReviewEvidenceBroker : ReviewEvidenceBroker {
  override fun readBatch(request: ReviewEvidenceBatchRequest) = error("unused")
  override fun recordToolCall(call: ReviewToolCall) = error("unused")
  override fun recordModelTurn() = error("unused")
  override fun validateLaneResult(result: String) = error("unused")
  override fun observeLaneResultChunk(chunk: String) = error("unused")
  override fun accounting() = error("unused")
  override fun terminalOutcome() = error("unused")
}
