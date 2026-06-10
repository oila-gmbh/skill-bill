package skillbill.launcher

import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRegistrationOperationsTest {
  private val runtimeMcpBin = Path.of("/tmp/runtime-mcp")

  private fun decode(path: Path): Map<String, Any?> = JsonSupport.anyToStringAnyMap(
    JsonSupport.parseObjectOrNull(Files.readString(path))?.let(JsonSupport::jsonElementToValue),
  ) ?: emptyMap()

  private fun skillBillServer(path: Path): Map<*, *> {
    val servers = decode(path)["mcpServers"] as Map<*, *>
    return servers["skill-bill"] as Map<*, *>
  }

  private fun markedProfile(home: Path, name: String): Path {
    val root = home.resolve(name)
    Files.createDirectories(root)
    Files.createFile(root.resolve(".claude.json"))
    return root
  }

  @Test
  fun `register fans into default and named profile config files`() {
    val home = Files.createTempDirectory("mcp-fanout")
    Files.createDirectories(home.resolve(".claude"))
    val work = markedProfile(home, ".claude-work")

    val result = McpRegistrationOperations.register("claude", runtimeMcpBin, home, environment = emptyMap())

    val defaultConfig = home.resolve(".claude.json")
    val workConfig = work.resolve(".claude.json")
    assertEquals(
      setOf(defaultConfig, workConfig),
      result.profiles.map { it.configPath }.toSet(),
    )
    assertEquals(defaultConfig, result.configPath)
    assertTrue(result.changed)

    listOf(defaultConfig, workConfig).forEach { path ->
      val server = skillBillServer(path)
      assertEquals("stdio", server["type"])
      assertEquals("/tmp/runtime-mcp", server["command"])
      assertEquals(emptyList<String>(), server["args"])
    }
  }

  @Test
  fun `unregister removes from every profile leaving other servers and config untouched`() {
    val home = Files.createTempDirectory("mcp-unregister")
    Files.createDirectories(home.resolve(".claude"))
    val work = markedProfile(home, ".claude-work")
    val defaultConfig = home.resolve(".claude.json")
    val workConfig = work.resolve(".claude.json")
    listOf(defaultConfig, workConfig).forEach { path ->
      Files.writeString(path, "{\n  \"theme\": \"dark\",\n  \"mcpServers\": {\"other\": {\"command\": \"other\"}}\n}\n")
    }

    McpRegistrationOperations.register("claude", runtimeMcpBin, home, environment = emptyMap())
    val result = McpRegistrationOperations.unregister("claude", home, environment = emptyMap())

    assertTrue(result.changed)
    assertEquals(setOf(defaultConfig, workConfig), result.profiles.map { it.configPath }.toSet())
    listOf(defaultConfig, workConfig).forEach { path ->
      val settings = decode(path)
      assertEquals("dark", settings["theme"])
      val servers = settings["mcpServers"] as Map<*, *>
      assertTrue("other" in servers)
      assertFalse("skill-bill" in servers)
    }
  }

  @Test
  fun `default root maps to home claude json and named root maps to its own claude json`() {
    val home = Files.createTempDirectory("mcp-pathmap")
    Files.createDirectories(home.resolve(".claude"))
    val work = markedProfile(home, ".claude-work")

    val result = McpRegistrationOperations.register("claude", runtimeMcpBin, home, environment = emptyMap())

    val paths = result.profiles.map { it.configPath }
    assertTrue(home.resolve(".claude.json") in paths)
    assertTrue(work.resolve(".claude.json") in paths)
    assertFalse(home.resolve(".claude/.claude.json") in paths)
  }

  @Test
  fun `register is idempotent per profile and picks up a profile created later`() {
    val home = Files.createTempDirectory("mcp-idempotent")
    Files.createDirectories(home.resolve(".claude"))

    McpRegistrationOperations.register("claude", runtimeMcpBin, home, environment = emptyMap())
    val first = Files.readString(home.resolve(".claude.json"))
    McpRegistrationOperations.register("claude", runtimeMcpBin, home, environment = emptyMap())
    assertEquals(first, Files.readString(home.resolve(".claude.json")))

    val work = markedProfile(home, ".claude-work")
    val result = McpRegistrationOperations.register("claude", runtimeMcpBin, home, environment = emptyMap())

    assertEquals(
      setOf(home.resolve(".claude.json"), work.resolve(".claude.json")),
      result.profiles.map {
        it.configPath
      }.toSet(),
    )
    assertEquals("/tmp/runtime-mcp", skillBillServer(work.resolve(".claude.json"))["command"])
  }

  @Test
  fun `default-only writes home claude json identical to single-profile baseline`() {
    val home = Files.createTempDirectory("mcp-default-only")
    Files.createDirectories(home.resolve(".claude"))

    val result = McpRegistrationOperations.register("claude", runtimeMcpBin, home, environment = emptyMap())
    val defaultConfig = home.resolve(".claude.json")

    assertEquals(defaultConfig, result.configPath)
    assertEquals(listOf(defaultConfig), result.profiles.map { it.configPath })

    val baseline = Files.createTempDirectory("mcp-baseline")
    val baselinePath = baseline.resolve(".claude.json")
    val baselineResult = McpJsonConfig.register(
      "claude",
      baselinePath,
      runtimeMcpBin.toAbsolutePath().normalize().toString(),
    )
    assertEquals(Files.readString(baselinePath), Files.readString(defaultConfig))
    assertEquals(baselineResult.changed, result.changed)
  }

  @Test
  fun `malformed profile fails loudly naming it while siblings are still written`() {
    val home = Files.createTempDirectory("mcp-malformed")
    Files.createDirectories(home.resolve(".claude"))
    val work = markedProfile(home, ".claude-work")
    val malformedConfig = work.resolve(".claude.json")
    Files.writeString(malformedConfig, "{ not valid json")

    val defaultConfig = home.resolve(".claude.json")

    val error = assertFailsWith<IllegalArgumentException> {
      McpRegistrationOperations.register("claude", runtimeMcpBin, home, environment = emptyMap())
    }
    assertContains(error.message.orEmpty(), malformedConfig.toString())

    assertEquals("/tmp/runtime-mcp", skillBillServer(defaultConfig)["command"])
    assertEquals("{ not valid json", Files.readString(malformedConfig))
  }

  @Test
  fun `register honors CLAUDE_CONFIG_DIR env root alongside the default profile`() {
    val home = Files.createTempDirectory("mcp-config-dir-env")
    Files.createDirectories(home.resolve(".claude"))
    val envRoot = Files.createTempDirectory("mcp-config-dir-target")
    Files.createFile(envRoot.resolve(".claude.json"))

    val result = McpRegistrationOperations.register(
      "claude",
      runtimeMcpBin,
      home,
      environment = mapOf("CLAUDE_CONFIG_DIR" to envRoot.toString()),
    )

    val defaultConfig = home.resolve(".claude.json")
    val envConfig = envRoot.resolve(".claude.json")
    assertEquals(setOf(defaultConfig, envConfig), result.profiles.map { it.configPath }.toSet())
    assertFalse(envRoot.resolve(".claude.json/.claude.json") in result.profiles.map { it.configPath })
    assertFalse(home.resolve("${envRoot.fileName}/.claude.json") in result.profiles.map { it.configPath })

    listOf(defaultConfig, envConfig).forEach { path ->
      assertEquals("/tmp/runtime-mcp", skillBillServer(path)["command"])
    }
  }

  @Test
  fun `non-claude agents stay single-target`() {
    val home = Files.createTempDirectory("mcp-single-target")

    val expectedPaths = mapOf(
      "codex" to home.resolve(".codex/config.toml"),
      "opencode" to home.resolve(".config/opencode/opencode.json"),
      "junie" to home.resolve(".junie/mcp/mcp.json"),
      "copilot" to home.resolve(".copilot/mcp-config.json"),
      "glm" to home.resolve(".glm/mcp-config.json"),
    )

    expectedPaths.forEach { (agent, expected) ->
      val result = McpRegistrationOperations.register(agent, runtimeMcpBin, home, environment = emptyMap())
      assertEquals(expected, result.configPath, agent)
      assertTrue(result.profiles.isEmpty(), agent)

      val unregistered = McpRegistrationOperations.unregister(agent, home, environment = emptyMap())
      assertEquals(expected, unregistered.configPath, agent)
      assertTrue(unregistered.profiles.isEmpty(), agent)
    }
  }

  private fun assertContains(haystack: String, needle: String) {
    assertTrue(needle in haystack, "Expected '$haystack' to contain '$needle'")
  }
}
