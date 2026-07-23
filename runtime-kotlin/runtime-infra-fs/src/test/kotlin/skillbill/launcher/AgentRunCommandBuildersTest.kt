package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.install.model.MODEL_DIRECTIVE_CAPABLE_AGENTS
import skillbill.launcher.agentrun.AgentRunOutputDecoder
import skillbill.launcher.agentrun.ClaudeAgentRunCommandBuilder
import skillbill.launcher.agentrun.CodexAgentRunCommandBuilder
import skillbill.launcher.agentrun.JunieAgentRunCommandBuilder
import skillbill.launcher.agentrun.NativeReviewOperationBoundary
import skillbill.launcher.agentrun.NativeReviewProviderCapabilities
import skillbill.launcher.agentrun.ProviderUsageExposure
import skillbill.launcher.process.AgentRunIdlePolicy
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.review.BrokerBackedNativeReviewOperationProtocol
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewToolCall
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentRunCommandBuildersTest {
  @Test
  fun `structured output decoders preserve provider token dimensions`() {
    val claude = AgentRunOutputDecoder.CLAUDE_JSON.decode(
      """{"result":"done","usage":{"input_tokens":100,"cache_read_input_tokens":40,""" +
        """"output_tokens":20,"total_tokens":120}}""",
    )
    assertEquals("done", claude.text)
    assertEquals(100, claude.inputTokens)
    assertEquals(40, claude.cachedInputTokens)
    assertEquals(20, claude.outputTokens)
    val codex = AgentRunOutputDecoder.CODEX_JSONL.decode(
      """
      {"item":{"text":"finding"}}
      {"usage":{"input_tokens":90,"cached_input_tokens":30,"output_tokens":10,"reasoning_tokens":5,"total_tokens":100}}
      """.trimIndent(),
    )
    assertEquals("finding", codex.text)
    assertEquals(5, codex.reasoningTokens)
    assertEquals(100, codex.totalTokens)
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
    assertEquals(fromBuffered.inputTokens, fromStream.inputTokens)
    assertEquals(fromBuffered.cachedInputTokens, fromStream.cachedInputTokens)
    assertEquals(fromBuffered.outputTokens, fromStream.outputTokens)
    assertEquals(fromBuffered.totalTokens, fromStream.totalTokens)
  }

  @Test
  fun `stream decoding ignores non-terminal events and tolerates a truncated stream`() {
    val noResultEvent = """{"type":"assistant","message":{"model":"claude-opus-4-8"}}"""
    assertEquals(noResultEvent, AgentRunOutputDecoder.CLAUDE_STREAM_JSON.decode(noResultEvent).text)

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
  fun `builders that cannot stream fall back to process liveness instead of a watchdog they cannot satisfy`() {
    val streaming = request().copy(streamOutputForLiveness = true)

    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, CodexAgentRunCommandBuilder().build(streaming).idlePolicy)
    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, JunieAgentRunCommandBuilder().build(streaming).idlePolicy)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, CodexAgentRunCommandBuilder().build(request()).idlePolicy)
  }

  @Test
  fun `claude renders exact commands for each directive shape`() {
    val builder = ClaudeAgentRunCommandBuilder()

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
    val builders = listOf(ClaudeAgentRunCommandBuilder(), CodexAgentRunCommandBuilder())

    assertEquals(MODEL_DIRECTIVE_CAPABLE_AGENTS, builders.map { it.agent }.toSet())
    builders.forEach { builder ->
      val command = builder.build(request(model = "model", effort = "high")).command
      assertTrue(command.contains("--model"))
      assertTrue(command.any { it == "high" || it == "model_reasoning_effort=high" })
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
  fun `builders materialize governed specialist isolation without provider branching`() {
    val isolated = request().copy(
      conversationIsolation = ConversationIsolation.NONE,
      reviewEvidenceBroker = NoOpReviewEvidenceBroker,
      nativeReviewOperations = BrokerBackedNativeReviewOperationProtocol(NoOpReviewEvidenceBroker),
      nativeReviewWorkerName = "bill-kotlin-code-review-architecture",
    )

    val builders = listOf(
      ClaudeAgentRunCommandBuilder() to ReviewLaunchIsolationStrategy.FRESH_PROCESS,
      CodexAgentRunCommandBuilder() to ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE,
      JunieAgentRunCommandBuilder() to ReviewLaunchIsolationStrategy.FRESH_PROCESS,
    )
    builders.forEach { (builder, expectedIsolation) ->
      assertEquals(expectedIsolation, builder.reviewIsolation)
      assertEquals(ConversationIsolation.NONE, builder.build(isolated).conversationIsolation)
    }
    assertTrue(ClaudeAgentRunCommandBuilder().nativeReviewCapabilities.supportsGovernedLaunch)
    assertTrue(CodexAgentRunCommandBuilder().nativeReviewCapabilities.supportsGovernedLaunch)
    assertFalse(JunieAgentRunCommandBuilder().nativeReviewCapabilities.supportsGovernedLaunch)
    assertEquals(
      skillbill.launcher.agentrun.NativeReviewOperationBoundary.DISABLED,
      CodexAgentRunCommandBuilder().nativeReviewCapabilities.operationBoundary,
    )
    assertEquals(
      skillbill.launcher.agentrun.ProviderUsageExposure.COMPLETION_ONLY,
      CodexAgentRunCommandBuilder().nativeReviewCapabilities.providerUsageExposure,
    )
    assertFalse(
      NativeReviewProviderCapabilities(
        operationBoundary = NativeReviewOperationBoundary.SYNCHRONOUS_BROKER,
        providerUsageExposure = ProviderUsageExposure.IN_FLIGHT_ENFORCEABLE,
        lifecycleCallbacks = null,
      ).supportsGovernedLaunch,
    )
    val codexCommand = CodexAgentRunCommandBuilder().build(isolated).command
    assertTrue(codexCommand.contains("--skip-git-repo-check"))
    assertTrue(codexCommand.contains("--ignore-user-config"))
    assertTrue(codexCommand.contains("read-only"))
    assertTrue(codexCommand.contains("fork_turns=none"))
    assertTrue(codexCommand.contains("tools.web_search=false"))
    assertTrue(codexCommand.contains("tools.shell=false"))
    assertFalse(
      CodexAgentRunCommandBuilder().build(request()).command.contains("--skip-git-repo-check"),
    )
  }

  @Test fun `codex exposes one-shot governed lifecycle support`() {
    assertNotNull(CodexAgentRunCommandBuilder().nativeReviewCapabilities.lifecycleCallbacks)
  }

  private fun request(model: String? = null, effort: String? = null): SkillRunRequest = SkillRunRequest(
    issueKey = "SKILL-113",
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    promptOverride = "Phase: implement",
    modelOverride = model,
    effortOverride = effort,
  )

  private object NoOpReviewEvidenceBroker : ReviewEvidenceBroker {
    override fun readBatch(request: skillbill.ports.review.model.ReviewEvidenceBatchRequest) = error("unused")
    override fun recordToolCall(call: skillbill.ports.review.model.ReviewToolCall) = error("unused")
    override fun recordModelTurn() = error("unused")
    override fun validateLaneResult(result: String) = error("unused")
    override fun observeLaneResultChunk(chunk: String) = error("unused")
    override fun evaluateProviderUsage(
      usage: skillbill.review.context.model.ProviderTokenUsage,
      enforceable: Boolean,
    ) = error("unused")
    override fun accounting() = error("unused")
    override fun terminalOutcome() = error("unused")
  }
}
