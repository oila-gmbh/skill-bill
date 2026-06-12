package skillbill.install

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeConfigRootsTest {
  private fun markedProfile(home: Path, name: String): Path {
    val root = home.resolve(name)
    Files.createDirectories(root)
    Files.createFile(root.resolve(".claude.json"))
    return root
  }

  @Test
  fun `only default root when no named profiles exist`() {
    val home = Files.createTempDirectory("skillbill-roots-default")
    assertEquals(
      listOf(home.resolve(".claude").toAbsolutePath().normalize()),
      claudeConfigRoots(home, environment = emptyMap()),
    )
  }

  @Test
  fun `discovers and orders multiple named profiles after default`() {
    val home = Files.createTempDirectory("skillbill-roots-multi")
    Files.createDirectories(home.resolve(".claude"))
    val work = markedProfile(home, ".claude-work")
    val personal = markedProfile(home, ".claude-personal")

    val roots = claudeConfigRoots(home, environment = emptyMap())

    assertEquals(
      listOf(
        home.resolve(".claude"),
        personal,
        work,
      ).map { it.toAbsolutePath().normalize() },
      roots,
    )
  }

  @Test
  fun `excludes files and unmarked profile directories`() {
    val home = Files.createTempDirectory("skillbill-roots-filter")
    Files.createDirectories(home.resolve(".claude"))
    Files.createFile(home.resolve(".claude.json"))
    Files.createDirectories(home.resolve(".claude-empty"))
    val marked = markedProfile(home, ".claude-work")

    val roots = claudeConfigRoots(home, environment = emptyMap())

    assertEquals(
      listOf(home.resolve(".claude"), marked).map { it.toAbsolutePath().normalize() },
      roots,
    )
  }

  @Test
  fun `each marker kind qualifies a profile`() {
    val home = Files.createTempDirectory("skillbill-roots-markers")
    val default = home.resolve(".claude").also { Files.createDirectories(it) }
    val qualified = listOf(".claude.json", ".credentials.json", "commands", "agents", "history.jsonl")
      .mapIndexed { index, marker ->
        val root = home.resolve(".claude-m$index")
        Files.createDirectories(root)
        if (marker == "commands" || marker == "agents") {
          Files.createDirectories(root.resolve(marker))
        } else {
          Files.createFile(root.resolve(marker))
        }
        root
      }

    val roots = claudeConfigRoots(home, environment = emptyMap())

    assertEquals(
      (listOf(default) + qualified).map { it.toAbsolutePath().normalize() }.toSet(),
      roots.toSet(),
    )
  }

  @Test
  fun `unions distinct CLAUDE_CONFIG_DIR outside home and dedups when already present`() {
    val home = Files.createTempDirectory("skillbill-roots-env")
    Files.createDirectories(home.resolve(".claude"))
    val outside = Files.createTempDirectory("skillbill-roots-outside")

    val roots = claudeConfigRoots(home, environment = mapOf("CLAUDE_CONFIG_DIR" to outside.toString()))

    assertEquals(
      listOf(home.resolve(".claude").toAbsolutePath().normalize(), outside.toAbsolutePath().normalize()),
      roots,
    )
  }

  @Test
  fun `CLAUDE_CONFIG_DIR pointing at default root does not duplicate it`() {
    val home = Files.createTempDirectory("skillbill-roots-env-default")
    val default = home.resolve(".claude")
    Files.createDirectories(default)

    val roots = claudeConfigRoots(home, environment = mapOf("CLAUDE_CONFIG_DIR" to default.toString()))

    assertEquals(listOf(default.toAbsolutePath().normalize()), roots)
  }

  @Test
  fun `blank or unset CLAUDE_CONFIG_DIR contributes nothing`() {
    val home = Files.createTempDirectory("skillbill-roots-blank")
    Files.createDirectories(home.resolve(".claude"))

    assertEquals(
      listOf(home.resolve(".claude").toAbsolutePath().normalize()),
      claudeConfigRoots(home, environment = mapOf("CLAUDE_CONFIG_DIR" to "   ")),
    )
    assertEquals(
      listOf(home.resolve(".claude").toAbsolutePath().normalize()),
      claudeConfigRoots(home, environment = emptyMap()),
    )
  }

  @Test
  fun `default root is included even when it does not yet exist`() {
    val home = Files.createTempDirectory("skillbill-roots-missing-default")
    assertTrue(claudeConfigRoots(home, environment = emptyMap()).contains(home.resolve(".claude")))
  }

  @Test
  fun `detection emits one claude target per resolved root and detects when any exists`() {
    val home = Files.createTempDirectory("skillbill-roots-detect")
    Files.createDirectories(home.resolve(".claude"))
    val work = markedProfile(home, ".claude-work")

    val claudeTargets = detectAgents(home, environment = emptyMap()).filter { it.name == "claude" }

    assertEquals(
      listOf(home.resolve(".claude/skills"), work.resolve("skills")).map { it.toAbsolutePath().normalize() },
      claudeTargets.map { it.path.toAbsolutePath().normalize() },
    )
  }

  @Test
  fun `single-profile detection matches baseline single skills target`() {
    val home = Files.createTempDirectory("skillbill-roots-baseline")
    Files.createDirectories(home.resolve(".claude"))

    val claudeTargets = detectAgents(home, environment = emptyMap()).filter { it.name == "claude" }

    assertEquals(1, claudeTargets.size)
    assertEquals(home.resolve(".claude/skills").toAbsolutePath().normalize(), claudeTargets.single().path)
  }

  @Test
  fun `agent-path stays single active root for backward compat`() {
    val home = Files.createTempDirectory("skillbill-roots-agentpath")
    Files.createDirectories(home.resolve(".claude"))
    markedProfile(home, ".claude-work")

    assertEquals(
      home.resolve(".claude/skills"),
      InstallOperations.agentPath("claude", home, environment = emptyMap()),
    )
    assertEquals(
      home.resolve(".claude/agents"),
      InstallOperations.claudeAgentsPath(home, environment = emptyMap()),
    )
  }
}
