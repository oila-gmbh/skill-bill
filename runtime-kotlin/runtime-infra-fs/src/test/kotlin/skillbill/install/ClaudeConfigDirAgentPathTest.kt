package skillbill.install

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeConfigDirAgentPathTest {
  @Test
  fun `claude skill path falls back to dot-claude commands when CLAUDE_CONFIG_DIR is unset`() {
    val home = Files.createTempDirectory("skillbill-claude-config-default")
    assertEquals(
      home.resolve(".claude/commands"),
      InstallOperations.agentPath("claude", home, environment = emptyMap()),
    )
  }

  @Test
  fun `claude skill path honors CLAUDE_CONFIG_DIR for named profiles`() {
    val home = Files.createTempDirectory("skillbill-claude-config-work")
    val workConfig = home.resolve(".claude-work")
    val env = mapOf("CLAUDE_CONFIG_DIR" to workConfig.toString())

    assertEquals(
      workConfig.resolve("commands"),
      InstallOperations.agentPath("claude", home, environment = env),
    )
    // Native subagents follow the same profile root.
    assertEquals(
      workConfig.resolve("agents"),
      InstallOperations.claudeAgentsPath(home, environment = env),
    )
    // Non-claude agents are unaffected by CLAUDE_CONFIG_DIR.
    assertEquals(
      home.resolve(".config/opencode/skills"),
      InstallOperations.agentPath("opencode", home, environment = env),
    )
  }

  @Test
  fun `blank CLAUDE_CONFIG_DIR falls back to the default root`() {
    val home = Files.createTempDirectory("skillbill-claude-config-blank")
    assertEquals(
      home.resolve(".claude/commands"),
      InstallOperations.agentPath("claude", home, environment = mapOf("CLAUDE_CONFIG_DIR" to "  ")),
    )
  }

  @Test
  fun `detection honors CLAUDE_CONFIG_DIR when only the work profile exists`() {
    val home = Files.createTempDirectory("skillbill-claude-config-detect")
    val workConfig = home.resolve(".claude-work")
    Files.createDirectories(workConfig)
    val env = mapOf("CLAUDE_CONFIG_DIR" to workConfig.toString())

    // Multi-root detection (SKILL-74): the default ~/.claude is always first, then the explicit
    // CLAUDE_CONFIG_DIR work profile. Both are detected so skills install into each root.
    val claudeTargets = InstallOperations.detectAgentTargets(home, environment = env)
      .filter { it.name == "claude" }
    assertEquals(
      listOf(home.resolve(".claude/commands"), workConfig.resolve("commands")),
      claudeTargets.map { it.path },
    )
  }
}
