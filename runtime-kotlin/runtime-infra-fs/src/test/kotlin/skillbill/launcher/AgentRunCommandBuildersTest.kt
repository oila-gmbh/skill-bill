package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.install.model.MODEL_DIRECTIVE_CAPABLE_AGENTS
import skillbill.launcher.agentrun.AgentRunOutputDecoder
import skillbill.launcher.agentrun.ClaudeAgentRunCommandBuilder
import skillbill.launcher.agentrun.CodexAgentRunCommandBuilder
import skillbill.launcher.agentrun.JunieAgentRunCommandBuilder
import skillbill.ports.agentrun.model.ConversationIsolation
import skillbill.ports.agentrun.model.ReviewLaunchIsolationStrategy
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    assertFalse(builder.build(request()).inheritEnvironment)

    assertEquals(
      listOf(
        "codex",
        "exec",
        "--json",
        "--cd",
        "/tmp/skillbill-agent-run",
        "--sandbox",
        "read-only",
        "--config",
        "shell_environment_policy.inherit=none",
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
        "--sandbox",
        "read-only",
        "--config",
        "shell_environment_policy.inherit=none",
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
        "--sandbox",
        "read-only",
        "--config",
        "shell_environment_policy.inherit=none",
      ),
      builder.build(request()).command,
    )
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
    val isolated = request().copy(conversationIsolation = ConversationIsolation.NONE)

    val builders = listOf(
      ClaudeAgentRunCommandBuilder() to ReviewLaunchIsolationStrategy.FRESH_PROCESS,
      CodexAgentRunCommandBuilder() to ReviewLaunchIsolationStrategy.CODEX_NATIVE_FORK_TURNS_NONE,
      JunieAgentRunCommandBuilder() to ReviewLaunchIsolationStrategy.FRESH_PROCESS,
    )
    builders.forEach { (builder, expectedIsolation) ->
      assertEquals(expectedIsolation, builder.reviewIsolation)
      assertEquals(ConversationIsolation.NONE, builder.build(isolated).conversationIsolation)
    }
  }

  private fun request(model: String? = null, effort: String? = null): SkillRunRequest = SkillRunRequest(
    issueKey = "SKILL-113",
    repoRoot = Path.of("/tmp/skillbill-agent-run"),
    promptOverride = "Phase: implement",
    modelOverride = model,
    effortOverride = effort,
  )
}
