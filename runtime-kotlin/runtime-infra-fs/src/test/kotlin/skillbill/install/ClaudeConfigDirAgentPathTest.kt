package skillbill.install

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    val claudeTarget = InstallOperations.detectAgentTargets(home, environment = env)
      .firstOrNull { it.name == "claude" }
    assertTrue(claudeTarget != null, "claude should be detected under its work profile")
    assertEquals(workConfig.resolve("commands"), claudeTarget.path)
  }
}
