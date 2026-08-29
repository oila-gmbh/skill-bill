package skillbill.launcher

import skillbill.config.model.PhaseCompactionDirective
import skillbill.error.GovernedReviewLaunchCapabilityError
import skillbill.infrastructure.fs.CursorReviewStreamError
import skillbill.infrastructure.fs.CursorReviewStreamMalformedError
import skillbill.install.model.InstallAgent
import skillbill.install.model.MODEL_DIRECTIVE_CAPABLE_AGENTS
import skillbill.launcher.agentrun.AgentRunCommand
import skillbill.launcher.agentrun.AgentRunCommandBuilder
import skillbill.launcher.agentrun.AgentRunOutputDecoder
import skillbill.launcher.agentrun.ClaudeAgentRunCommandBuilder
import skillbill.launcher.agentrun.CodexAgentRunCommandBuilder
import skillbill.launcher.agentrun.CursorAgentRunCommandBuilder
import skillbill.launcher.agentrun.GovernedReviewLaunchCapability
import skillbill.launcher.agentrun.JunieAgentRunCommandBuilder
import skillbill.launcher.mcp.McpConfigFormat
import skillbill.launcher.process.AgentRunIdlePolicy
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.model.GovernedReviewEvidenceCodec
import ReviewEvidenceBatchRequest
import ReviewToolCall
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.model.GovernedReviewEvidenceCodec.OPERATIONS

@Suppress("LargeClass") // cohesive builder-matrix suite across claude/codex/junie/cursor launches
class AgentRunCommandBuildersTest {
  @Test
  fun `a compaction directive reaches the claude launch environment`() {
    val command = ClaudeAgentRunCommandBuilder().build(
      request(compaction = PhaseCompactionDirective(windowTokens = 400_000, triggerPct = 70)),
    )

    assertEquals("400000", command.environment["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
    assertEquals("70", command.environment["CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"])
  }

  @Test
  fun `no compaction directive leaves the launch environment untouched`() {
    val command = ClaudeAgentRunCommandBuilder().build(request())

    assertFalse(command.environment.containsKey("CLAUDE_CODE_AUTO_COMPACT_WINDOW"))
    assertFalse(command.environment.containsKey("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"))
  }

  @Test
  fun `structured output decoders ignore provider usage dimensions`() {
    val claude = AgentRunOutputDecoder.CLAUDE_JSON.decode(
      """{"result":"done","usage":{"input_tokens":100,"cache_read_input_tokens":40,""" +
        """"output_tokens":20,"total_tokens":120}}""",
    )
    assertEquals("done", claude.text)
    val codex = AgentRunOutputDecoder.CODEX_JSONL.decode(
      """
      {"item":{"text":"finding"}}
      {"usage":{"input_tokens":90,"cached_input_tokens":30,"output_tokens":10,"reasoning_tokens":5,"total_tokens":100}}
      """.trimIndent(),
    )
    assertEquals("finding", codex.text)
    assertEquals("", AgentRunOutputDecoder.CLAUDE_JSON.decode("""{"usage":{"total_tokens":7}}""").text)
    assertEquals("", AgentRunOutputDecoder.CODEX_JSONL.decode("""{"usage":{"total_tokens":7}}""").text)
  }

  @Test
  fun `streamed claude output decodes identically to the buffered form`() {
    val buffered = """{"type":"result","subtype":"success","result":"PLAN-OK","usage":""" +
      """{"input_tokens":2,"cache_read_input_tokens":17931,"output_tokens":6,"total_tokens":120}}"""
    val streamed = listOf(
      """{"type":"system","subtype":"init","session_id":"s-1","cwd":"/repo"}""",
      """{"type":"assistant","message":{"model":"claude-opus-4-8"},"session_id":"s-1"}""",
      """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"},"session_id":"s-1"}""",
      buffered,
    ).joinToString("\n")

    val fromBuffered = AgentRunOutputDecoder.CLAUDE_JSON.decode(buffered)
    val fromStream = AgentRunOutputDecoder.CLAUDE_STREAM_JSON.decode(streamed)

    assertEquals(fromBuffered.text, fromStream.text)
    assertEquals("PLAN-OK", fromStream.text)
  }

  @Test
  fun `stream decoding ignores non-terminal events and tolerates a truncated stream`() {
    val noResultEvent = """{"type":"assistant","message":{"model":"claude-opus-4-8"}}"""
    val undecodable = AgentRunOutputDecoder.CLAUDE_STREAM_JSON.decode(noResultEvent)
    assertEquals("", undecodable.text, "a stream cut before its terminal event carries no answer")
    assertEquals(noResultEvent, undecodable.rawOutputPreview)

    val laterResultWins = listOf(
      """{"type":"result","subtype":"success","result":"stale"}""",
      """{"type":"assistant","message":{"content":"result"}}""",
      """{"type":"result","subtype":"success","result":"fresh"}""",
    ).joinToString("\n")
    assertEquals("fresh", AgentRunOutputDecoder.CLAUDE_STREAM_JSON.decode(laterResultWins).text)

    val partialLine = """{"type":"result","subtype":"success","result":"kept"}""" + "\n{\"type\":\"resu"
    assertEquals("kept", AgentRunOutputDecoder.CLAUDE_STREAM_JSON.decode(partialLine).text)
  }

  @Test
  fun `a liveness-streamed claude launch swaps output format decoder and idle policy`() {
    val builder = ClaudeAgentRunCommandBuilder()

    val streamed = builder.build(request().copy(streamOutputForLiveness = true))

    assertEquals(
      listOf(
        "claude",
        "--print",
        "--output-format",
        "stream-json",
        "--verbose",
        "--dangerously-skip-permissions",
        "--add-dir",
        "/tmp/skillbill-agent-run",
      ),
      streamed.command,
    )
    assertEquals(AgentRunOutputDecoder.CLAUDE_STREAM_JSON, streamed.outputDecoder)
    assertEquals(AgentRunIdlePolicy.OUTPUT_EXTENDED, streamed.idlePolicy)

    val buffered = builder.build(request())
    assertEquals(null, buffered.outputDecoder, "a non-streamed launch keeps the builder default decoder")
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, buffered.idlePolicy)
  }

  @Test
  fun `a transport-streamed claude launch preserves durable-progress-only idle policy`() {
    val streamed = ClaudeAgentRunCommandBuilder().build(request().copy(streamProviderOutput = true))

    assertContains(streamed.command, "stream-json")
    assertEquals(AgentRunOutputDecoder.CLAUDE_STREAM_JSON, streamed.outputDecoder)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, streamed.idlePolicy)
  }

  @Test
  fun `builders that cannot stream fall back to process liveness instead of a watchdog they cannot satisfy`() {
    val streaming = request().copy(streamOutputForLiveness = true)

    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, CodexAgentRunCommandBuilder().build(streaming).idlePolicy)
    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, JunieAgentRunCommandBuilder().build(streaming).idlePolicy)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, CodexAgentRunCommandBuilder().build(request()).idlePolicy)
  }

  @Test
  fun `claude renders exact commands for each directive shape`() {
    val builder = ClaudeAgentRunCommandBuilder(emptyMap())

    assertEquals(
      listOf(
        "claude",
        "--print",
        "--output-format",
        "json",
        "--model",
        "claude-opus",
        "--effort",
        "high",
        "--dangerously-skip-permissions",
        "--add-dir",
        "/tmp/skillbill-agent-run",
      ),
      builder.build(request(model = "claude-opus", effort = "high")).command,
    )
    assertEquals(
      listOf(
        "claude",
        "--print",
        "--output-format",
        "json",
        "--model",
        "claude-opus",
        "--dangerously-skip-permissions",
        "--add-dir",
        "/tmp/skillbill-agent-run",
      ),
      builder.build(request(model = "claude-opus")).command,
    )
    assertEquals(
      listOf(
        "claude",
        "--print",
        "--output-format",
        "json",
        "--dangerously-skip-permissions",
        "--add-dir",
        "/tmp/skillbill-agent-run",
      ),
      builder.build(request()).command,
    )
  }

  @Test
  fun `claude directive naming an anthropic model falls back to the parent model on a non-anthropic endpoint`() {
    val builder = ClaudeAgentRunCommandBuilder(
      mapOf(
        "ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic",
        "ANTHROPIC_MODEL" to "deepseek-v4-flash",
      ),
    )

    val command = builder.build(request(model = "claude-opus-5", effort = "high")).command

    val modelIndex = command.indexOf("--model")
    assertTrue(modelIndex >= 0)
    assertEquals("deepseek-v4-flash", command[modelIndex + 1])
  }

  @Test
  fun `claude directive naming a served deepseek model passes through unchanged`() {
    val builder = ClaudeAgentRunCommandBuilder(
      mapOf("ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic"),
    )

    val command = builder.build(request(model = "deepseek-v4-flash")).command

    val modelIndex = command.indexOf("--model")
    assertTrue(modelIndex >= 0)
    assertEquals("deepseek-v4-flash", command[modelIndex + 1])
  }

  @Test
  fun `claude directive passes through on the official anthropic endpoint`() {
    val builder = ClaudeAgentRunCommandBuilder(
      mapOf("ANTHROPIC_BASE_URL" to "https://api.anthropic.com"),
    )

    val command = builder.build(request(model = "claude-opus-5")).command

    val modelIndex = command.indexOf("--model")
    assertTrue(modelIndex >= 0)
    assertEquals("claude-opus-5", command[modelIndex + 1])
  }

  @Test
  fun `claude directive without a parent model keeps the directive on a non-anthropic endpoint`() {
    val builder = ClaudeAgentRunCommandBuilder(
      mapOf("ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic"),
    )

    val command = builder.build(request(model = "claude-opus-5")).command

    val modelIndex = command.indexOf("--model")
    assertTrue(modelIndex >= 0)
    assertEquals("claude-opus-5", command[modelIndex + 1])
  }

  @Test
  fun `claude without a model directive stays flag free so the child inherits the parent model`() {
    val builder = ClaudeAgentRunCommandBuilder(
      mapOf(
        "ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic",
        "ANTHROPIC_MODEL" to "deepseek-v4-flash",
      ),
    )

    val command = builder.build(request()).command

    assertFalse(command.contains("--model"))
  }

  @Test
  fun `codex renders exact commands for each directive shape`() {
    val builder = CodexAgentRunCommandBuilder()
    assertTrue(builder.build(request()).inheritEnvironment)

    assertEquals(
      listOf(
        "codex",
        "exec",
        "--json",
        "--cd",
        "/tmp/skillbill-agent-run",
        "--dangerously-bypass-approvals-and-sandbox",
        "--config",
        "shell_environment_policy.inherit=all",
        "--model",
        "gpt-sol",
        "--config",
        "model_reasoning_effort=xhigh",
      ),
      builder.build(request(model = "gpt-sol", effort = "xhigh")).command,
    )
    assertEquals(
      listOf(
        "codex",
        "exec",
        "--json",
        "--cd",
        "/tmp/skillbill-agent-run",
        "--dangerously-bypass-approvals-and-sandbox",
        "--config",
        "shell_environment_policy.inherit=all",
        "--model",
        "gpt-sol",
      ),
      builder.build(request(model = "gpt-sol")).command,
    )
    assertEquals(
      listOf(
        "codex",
        "exec",
        "--json",
        "--cd",
        "/tmp/skillbill-agent-run",
        "--dangerously-bypass-approvals-and-sandbox",
        "--config",
        "shell_environment_policy.inherit=all",
      ),
      builder.build(request()).command,
    )
  }

  @Test
  fun `all feature task codex phases remain writable`() {
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.forEach { phase ->
      val command = CodexAgentRunCommandBuilder().build(
        request().copy(promptOverride = "Phase: $phase"),
      ).command

      assertTrue(command.contains("--dangerously-bypass-approvals-and-sandbox"))
      assertFalse(command.contains("read-only"))
    }
  }

  @Test
  fun `directive capable agents have builders that render their directives`() {
    val builders = listOf(
      ClaudeAgentRunCommandBuilder(),
      CodexAgentRunCommandBuilder(),
      CursorAgentRunCommandBuilder(),
    )

    assertEquals(MODEL_DIRECTIVE_CAPABLE_AGENTS, builders.map { it.agent }.toSet())
    builders.forEach { builder ->
      val command = builder.build(request(model = "model", effort = "high")).command
      assertTrue(command.contains("--model"))
      assertTrue(
        command.any { arg ->
          arg == "high" ||
            arg == "model_reasoning_effort=high" ||
            arg.contains("[effort=high]")
        },
        "builder ${builder.agent.id} must render effort for a model+effort directive: $command",
      )
    }
  }

  @Test
  fun `junie rejects model and effort directives`() {
    assertFailsWith<IllegalArgumentException> {
      JunieAgentRunCommandBuilder().build(request(model = "model", effort = "high"))
    }
  }

  @Test
  fun `junie accepts a directive free request`() {
    val command = JunieAgentRunCommandBuilder().build(
      request(),
    ).command

    assertEquals(InstallAgent.JUNIE.id, command.first())
  }

  @Test
  fun `a governed claude launch is a fresh isolated process naming its worker`() {
    val isolated = request().copy(
      conversationIsolation = ConversationIsolation.NONE,
      reviewEvidenceBroker = NoOpReviewEvidenceBroker,
      nativeReviewOperations = BrokerBackedNativeReviewOperationProtocol(NoOpReviewEvidenceBroker),
      reviewEvidenceEndpoint = StubReviewEvidenceEndpoint,
      nativeReviewWorkerName = "bill-kotlin-code-review-architecture",
    )
    val builder = ClaudeAgentRunCommandBuilder()
    val command = builder.build(isolated)

    assertEquals(ReviewLaunchIsolationStrategy.FRESH_PROCESS, builder.reviewIsolation)
    assertEquals(ConversationIsolation.NONE, command.conversationIsolation)
    assertEquals(
      "bill-kotlin-code-review-architecture",
      command.command[command.command.indexOf("--agent") + 1],
    )
    assertFalse(
      CodexAgentRunCommandBuilder().build(request()).command.contains("--skip-git-repo-check"),
    )
  }

  @Test
  fun `a provider missing a governed launch capability fails with a typed error and no command`() {
    val governed = governedReviewRequest()
    val capable = GovernedReviewLaunchCapability(
      governedOnlyTooling = true,
      mcpIsolation = true,
      configFormat = McpConfigFormat.TOML,
    )
    listOf(
      capable.copy(governedOnlyTooling = false) to "governed-only tooling",
      capable.copy(mcpIsolation = false) to "MCP isolation",
    ).forEach { (capability, missing) ->
      val builder = CodexAgentRunCommandBuilder(governedReviewLaunchCapability = capability)
      val error = assertFailsWith<GovernedReviewLaunchCapabilityError> { builder.build(governed) }
      assertEquals("codex", error.provider)
      assertEquals(missing, error.capability)
    }
  }

  @Test
  fun `governed review launches carry isolated mcp config and governed-only tools`() {
    val governed = governedReviewRequest(nativeReviewWorkerName = "bill-code-review-inline")
    listOf(
      ClaudeAgentRunCommandBuilder(),
      CodexAgentRunCommandBuilder(),
      CursorAgentRunCommandBuilder(),
      JunieAgentRunCommandBuilder(),
    ).forEach { builder ->
      assertGovernedReviewLaunch(builder, governed)
    }
  }

  @Test
  fun `governed claude review names only governed operations and no raw filesystem tool`() {
    val isolated = request().copy(
      conversationIsolation = ConversationIsolation.NONE,
      reviewEvidenceBroker = NoOpReviewEvidenceBroker,
      nativeReviewOperations = BrokerBackedNativeReviewOperationProtocol(NoOpReviewEvidenceBroker),
      reviewEvidenceEndpoint = StubReviewEvidenceEndpoint,
    )
    val governedOperations = OPERATIONS

    listOf(false to emptyList<String>(), true to listOf("Agent", "Task")).forEach { (fanOut, delegation) ->
      val command = ClaudeAgentRunCommandBuilder().build(isolated.copy(reviewFanOut = fanOut)).command
      assertEquals(
        StubReviewEvidenceEndpoint.descriptor.mcpConfigPath.toString(),
        command[command.indexOf("--mcp-config") + 1],
      )
      assertTrue(command.contains("--strict-mcp-config"))
      val tools = command[command.indexOf("--tools") + 1].split(",")
      assertEquals(
        delegation + governedOperations.map { "mcp__skill-bill-review-evidence__$it" },
        tools,
      )
      assertTrue(tools.none { it in setOf("Read", "Grep", "Glob", "Bash") })
    }
  }

  @Test
  fun `claude builder forwards provider passthrough keys when review evidence broker is present`() {
    val isolated = request().copy(
      conversationIsolation = ConversationIsolation.NONE,
      reviewEvidenceBroker = NoOpReviewEvidenceBroker,
      nativeReviewOperations = BrokerBackedNativeReviewOperationProtocol(NoOpReviewEvidenceBroker),
      reviewEvidenceEndpoint = StubReviewEvidenceEndpoint,
      nativeReviewWorkerName = "bill-kotlin-code-review-architecture",
    )
    val command = ClaudeAgentRunCommandBuilder().build(isolated)
    assertTrue(
      command.environmentPassthroughKeys.contains("ANTHROPIC_API_KEY"),
      "Isolated claude review worker must forward Anthropic direct-auth key",
    )
    assertTrue(
      command.environmentPassthroughKeys.contains("CLAUDE_CODE_USE_BEDROCK"),
      "Isolated claude review worker must forward Bedrock provider selection key",
    )
    assertTrue(
      command.environmentPassthroughKeys.contains("CLAUDE_CODE_USE_VERTEX"),
      "Isolated claude review worker must forward Vertex provider selection key",
    )
    assertTrue(
      command.environmentPassthroughKeys.contains("AWS_ACCESS_KEY_ID"),
      "Isolated claude review worker must forward Bedrock credential key",
    )
    assertTrue(
      command.environmentPassthroughKeys.contains("GOOGLE_APPLICATION_CREDENTIALS"),
      "Isolated claude review worker must forward Vertex credential key",
    )
  }

  @Test
  fun `claude builder sets empty passthrough keys when review evidence broker is absent`() {
    val command = ClaudeAgentRunCommandBuilder().build(request())
    assertTrue(
      command.environmentPassthroughKeys.isEmpty(),
      "Non-isolated claude run must not passthrough provider keys",
    )
  }

  @Test
  fun `codex read-only phase requires durable progress without changing other providers`() {
    val readOnly = request().copy(readOnlyPhase = true)

    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, ClaudeAgentRunCommandBuilder().build(readOnly).idlePolicy)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, CodexAgentRunCommandBuilder().build(readOnly).idlePolicy)
    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, JunieAgentRunCommandBuilder().build(readOnly).idlePolicy)
  }

  @Test
  fun `read-only phase does not override output-extended when claude streams`() {
    val streamingReadOnly = request().copy(streamOutputForLiveness = true, readOnlyPhase = true)

    assertEquals(
      AgentRunIdlePolicy.OUTPUT_EXTENDED,
      ClaudeAgentRunCommandBuilder().build(streamingReadOnly).idlePolicy,
      "streaming takes precedence over read-only for claude",
    )
  }

  @Test
  fun `non-read-only non-streaming phases keep db-progress-only idle policy`() {
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, ClaudeAgentRunCommandBuilder().build(request()).idlePolicy)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, CodexAgentRunCommandBuilder().build(request()).idlePolicy)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, JunieAgentRunCommandBuilder().build(request()).idlePolicy)
  }

  private fun assertGovernedReviewLaunch(builder: AgentRunCommandBuilder, governed: SkillRunRequest) {
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

  private fun assertClaudeGovernedLaunch(command: List<String>) {
    val mcpJson = StubReviewEvidenceEndpoint.descriptor.mcpConfigPath.toString()
    assertEquals(mcpJson, command[command.indexOf("--mcp-config") + 1])
    assertTrue(command.contains("--strict-mcp-config"))
    val tools = command[command.indexOf("--tools") + 1].split(",")
    assertEquals(GovernedReviewEvidenceCodec.OPERATIONS.map { "mcp__skill-bill-review-evidence__$it" }, tools)
  }

  private fun assertCodexGovernedLaunch(command: List<String>) {
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

  private fun assertCursorGovernedLaunch(
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

  private fun request(
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

  private fun governedReviewRequest(nativeReviewWorkerName: String? = null): SkillRunRequest = request().copy(
    conversationIsolation = ConversationIsolation.NONE,
    reviewEvidenceBroker = NoOpReviewEvidenceBroker,
    nativeReviewOperations = BrokerBackedNativeReviewOperationProtocol(NoOpReviewEvidenceBroker),
    reviewEvidenceEndpoint = StubReviewEvidenceEndpoint,
    nativeReviewWorkerName = nativeReviewWorkerName,
  )

  private object StubReviewEvidenceEndpoint : GovernedReviewEvidenceEndpointHandle {
    override val descriptor = GovernedReviewEvidenceEndpointDescriptor(
      lane = "architecture",
      socketPath = Path.of("/tmp/skill-bill-review/evidence.sock"),
      mcpConfigPath = Path.of("/tmp/skill-bill-review/mcp.json"),
      token = "launch-token",
    )

    override fun close() = Unit
  }

  private object NoOpReviewEvidenceBroker : ReviewEvidenceBroker {
    override fun readBatch(request: ReviewEvidenceBatchRequest) = error("unused")
    override fun recordToolCall(call: ReviewToolCall) = error("unused")
    override fun recordModelTurn() = error("unused")
    override fun validateLaneResult(result: String) = error("unused")
    override fun observeLaneResultChunk(chunk: String) = error("unused")
    override fun accounting() = error("unused")
    override fun terminalOutcome() = error("unused")
  }

  @Test
  fun `cursor normal launch emits flags, workspace, prompt, timeout, environment, approvals`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request())

    assertEquals(
      listOf(
        "agent",
        "--print",
        "--force",
        "--trust",
        "--approve-mcps",
        "--workspace",
        "/tmp/skillbill-agent-run",
        "--output-format",
        "json",
      ),
      command.command,
    )
    assertEquals("agent", command.command.first())
    assertEquals("/tmp/skillbill-agent-run", command.workingDirectory.toString())
    assertEquals(3.seconds, command.timeout)
    assertEquals("Phase: implement", command.stdinText)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, command.idlePolicy)
    assertEquals("1", command.environment["SKILL_BILL_GOAL_CONTINUATION"])
    assertTrue(command.inheritEnvironment)
    assertTrue(command.environmentPassthroughKeys.isEmpty())
  }

  @Test
  fun `cursor provider-output launch adds --stream-partial-output and OUTPUT_EXTENDED idle policy`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(
      request().copy(streamProviderOutput = true, streamOutputForLiveness = true),
    )

    assertTrue(command.command.contains("--stream-partial-output"))
    assertEquals(AgentRunIdlePolicy.OUTPUT_EXTENDED, command.idlePolicy)
  }

  @Test
  fun `cursor liveness-only launch withholds --stream-partial-output and falls back to heartbeat`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request().copy(streamOutputForLiveness = true))

    assertFalse(
      command.command.contains("--stream-partial-output"),
      "partial deltas must not be requested for liveness alone; they are indistinguishable from " +
        "finished turns at harvest time",
    )
    assertTrue(command.command.contains("stream-json"), "the terminal-event transport is still required")
    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, command.idlePolicy)
  }

  @Test
  fun `cursor read-only phase uses HEARTBEAT_EXTENDED idle policy`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request().copy(readOnlyPhase = true))

    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, command.idlePolicy)
  }

  @Test
  fun `cursor model-only directive emits --model flag`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request(model = "claude-opus-4-8"))

    assertTrue(command.command.contains("--model"))
    val modelIndex = command.command.indexOf("--model")
    assertEquals("claude-opus-4-8", command.command[modelIndex + 1])
  }

  @Test
  fun `cursor model-plus-effort directive merges into bracket syntax`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request(model = "claude-opus-4-8", effort = "high"))

    assertTrue(command.command.contains("--model"))
    val modelIndex = command.command.indexOf("--model")
    assertEquals("claude-opus-4-8[effort=high]", command.command[modelIndex + 1])
  }

  @Test
  fun `cursor effort directive without model fails loudly`() {
    val builder = CursorAgentRunCommandBuilder()

    val exception = assertFailsWith<IllegalArgumentException> {
      builder.build(request(effort = "high"))
    }

    assertContains(exception.message ?: "", "effort directive requires a model directive")
  }

  @Test
  fun `cursor model already with bracket parameters accepts identical duplicate effort`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request(model = "claude-opus-4-8[effort=high]", effort = "high"))

    val modelIndex = command.command.indexOf("--model")
    assertEquals("claude-opus-4-8[effort=high]", command.command[modelIndex + 1])
  }

  @Test
  fun `cursor model already with bracket parameters rejects conflicting effort loudly`() {
    val builder = CursorAgentRunCommandBuilder()

    val exception = assertFailsWith<IllegalStateException> {
      builder.build(request(model = "claude-opus-4-8[effort=high]", effort = "medium"))
    }

    assertContains(exception.message ?: "", "Conflicting effort directive")
    assertContains(exception.message ?: "", "effort='high'")
    assertContains(exception.message ?: "", "effort='medium'")
  }

  @Test
  fun `cursor decoder extracts result and ignores usage from JSONL`() {
    val jsonl =
      """
      {"type":"partial","delta":"increment"}
      {"type":"result","result":"PLAN-OK","usage":{"input_tokens":100,"cached_input_tokens":20,"output_tokens":50,"total_tokens":150}}
      """.trimIndent()

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("PLAN-OK", decoded.text)
  }

  @Test
  fun `cursor decoder terminal result wins over partial deltas`() {
    val jsonl = """{"type":"partial","delta":"partial"}
{"type":"partial","delta":" delta"}
{"type":"result","result":"final","usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("final", decoded.text)
  }

  @Test
  fun `cursor decoder ignores camelCase usage keys`() {
    val usage = """{"inputTokens":28164,"cachedInputTokens":11,"outputTokens":0,"totalTokens":28175}"""
    val jsonl = """{"type":"result","result":"PLAN-OK","usage":$usage}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("PLAN-OK", decoded.text)
  }

  @Test
  fun `cursor decoder reports an empty successful turn with zero assistant events`() {
    val jsonl = """{"type":"system","subtype":"init"}
{"type":"user","message":{"content":[{"type":"text","text":"briefing"}]}}
{"type":"result","subtype":"success","is_error":false,"result":"","usage":{"inputTokens":33110,"outputTokens":0}}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("", decoded.text)
    assertEquals(0, decoded.assistantEventCount)
    assertNotNull(decoded.rawOutputPreview, "an empty harvest must retain bounded transport evidence")
  }

  @Test
  fun `cursor decoder falls back to the longest assistant turn when the terminal result is blank`() {
    val jsonl = """{"type":"assistant","message":{"content":[{"type":"text","text":"short"}]}}
{"type":"assistant","message":{"content":[{"type":"text","text":"{\"status\":\"completed\"}"}]}}
{"type":"result","result":"","usage":{"inputTokens":10,"outputTokens":4}}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("""{"status":"completed"}""", decoded.text)
    assertEquals(2, decoded.assistantEventCount)
    assertNull(decoded.rawOutputPreview, "a harvested turn is not empty-turn evidence")
  }

  @Test
  fun `cursor decoder declares an undecodable stream so the launcher degrades to an empty harvest`() {
    val decoder = AgentRunOutputDecoder.CURSOR_STREAM_JSON

    assertTrue(decoder.undecodable(CursorReviewStreamMalformedError("truncated", RuntimeException())))
    assertFalse(decoder.undecodable(CursorReviewStreamError("provider said no")))
    assertFalse(
      AgentRunOutputDecoder.PLAIN.undecodable(CursorReviewStreamMalformedError("truncated", RuntimeException())),
      "the default policy keeps propagating; only a decoder that owns the transport may degrade",
    )
  }

  @Test
  fun `cursor decoder on malformed line throws typed error`() {
    val jsonl = """invalid line
{"type":"result","result":"success","usage":{"input_tokens":5,"output_tokens":3,"total_tokens":8}}
more invalid"""

    assertFailsWith<CursorReviewStreamMalformedError> {
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)
    }
  }

  @Test
  fun `cursor decoder on fully malformed input throws typed error`() {
    val malformed = "not json at all"

    assertFailsWith<CursorReviewStreamMalformedError> {
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(malformed)
    }
  }

  @Test
  fun `cursor decoder on empty stream returns empty text`() {
    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode("")

    assertEquals("", decoded.text)
  }

  @Test
  fun `cursor decoder on error event throws typed error`() {
    val jsonl = """{"type":"error","error":"Provider error occurred"}"""

    assertFailsWith<CursorReviewStreamError> {
      AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)
    }
  }

  @Test
  fun `cursor decoder on decoded envelope with no terminal result returns empty text`() {
    val jsonl = """{"type":"partial","delta":"only"}
{"type":"system","message":"done"}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("", decoded.text)
  }

  @Test
  fun `cursor decoder bounds oversized input and stops processing`() {
    val hugeLine = "x".repeat(15_000_000)
    val jsonl = """{"type":"result","result":"early"}
$hugeLine
{"type":"result","result":"late","usage":{"total_tokens":1}}"""

    val decoded = AgentRunOutputDecoder.CURSOR_STREAM_JSON.decode(jsonl)

    assertEquals("early", decoded.text)
  }

  @Test
  fun `cursor builder sets empty passthrough keys when review evidence broker is absent`() {
    val builder = CursorAgentRunCommandBuilder()
    val command = builder.build(request())

    assertTrue(
      command.environmentPassthroughKeys.isEmpty(),
      "Non-isolated cursor run must not passthrough provider keys",
    )
  }
}
