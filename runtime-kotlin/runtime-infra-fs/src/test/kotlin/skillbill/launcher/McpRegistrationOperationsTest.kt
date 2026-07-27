package skillbill.launcher

import skillbill.contracts.JsonSupport
import skillbill.launcher.mcp.McpJsonConfig
import skillbill.launcher.mcp.McpRegistrationOperations
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
      "cursor" to home.resolve(".cursor/mcp.json"),
      "zcode" to home.resolve(".zcode/cli/config.json"),
      "copilot" to home.resolve(".copilot/mcp-config.json"),
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

  @Test
  fun `cursor register creates mcp json with unrelated keys preserved`() {
    val home = Files.createTempDirectory("mcp-cursor-register")
    Files.createDirectories(home.resolve(".cursor"))
    val configPath = home.resolve(".cursor/mcp.json")
    val existingContent = """
    {
      "unrelatedKey": "unrelatedValue",
      "mcpServers": {
        "other-server": {
          "command": "other-command",
          "args": ["--arg1"]
        }
      }
    }
    """.trimIndent()
    Files.writeString(configPath, existingContent)

    val result = McpRegistrationOperations.register("cursor", runtimeMcpBin, home)

    assertEquals(configPath, result.configPath)
    assertTrue(result.changed)

    val updated = decode(configPath)
    assertEquals("unrelatedValue", updated["unrelatedKey"])
    val servers = updated["mcpServers"] as Map<*, *>
    assertTrue(servers.containsKey("other-server"))
    assertTrue(servers.containsKey("skill-bill"))
    val skillBillServer = servers["skill-bill"] as Map<*, *>
    assertEquals("stdio", skillBillServer["type"])
    assertEquals("/tmp/runtime-mcp", skillBillServer["command"])
    assertEquals(emptyList<String>(), skillBillServer["args"])
  }

  @Test
  fun `cursor register is idempotent`() {
    val home = Files.createTempDirectory("mcp-cursor-idempotent")
    Files.createDirectories(home.resolve(".cursor"))
    val configPath = home.resolve(".cursor/mcp.json")

    McpRegistrationOperations.register("cursor", runtimeMcpBin, home)
    val firstContent = Files.readString(configPath)

    val result = McpRegistrationOperations.register("cursor", runtimeMcpBin, home)
    assertFalse(result.changed)
    assertEquals(firstContent, Files.readString(configPath))
  }

  @Test
  fun `cursor unregister removes only skill bill entry`() {
    val home = Files.createTempDirectory("mcp-cursor-unregister")
    Files.createDirectories(home.resolve(".cursor"))
    val configPath = home.resolve(".cursor/mcp.json")
    val content = """
    {
      "unrelatedKey": "unrelatedValue",
      "mcpServers": {
        "other-server": {
          "command": "other-command"
        },
        "skill-bill": {
          "command": "runtime-mcp"
        }
      }
    }
    """.trimIndent()
    Files.writeString(configPath, content)

    val result = McpRegistrationOperations.unregister("cursor", home)

    assertTrue(result.changed)
    assertEquals(configPath, result.configPath)

    val updated = decode(configPath)
    assertEquals("unrelatedValue", updated["unrelatedKey"])
    val servers = updated["mcpServers"] as Map<*, *>
    assertTrue(servers.containsKey("other-server"))
    assertFalse(servers.containsKey("skill-bill"))
  }

  @Test
  fun `cursor malformed json fails loudly`() {
    val home = Files.createTempDirectory("mcp-cursor-malformed")
    Files.createDirectories(home.resolve(".cursor"))
    val configPath = home.resolve(".cursor/mcp.json")
    Files.writeString(configPath, "{ not valid json")

    val error = assertFailsWith<IllegalArgumentException> {
      McpRegistrationOperations.register("cursor", runtimeMcpBin, home)
    }
    assertTrue(error.message?.contains("mcp.json") == true)
    assertTrue(error.message?.contains("JSON") == true || error.message?.contains("json") == true)
  }

  @Test
  fun `cursor register handles absent and empty config`() {
    val home = Files.createTempDirectory("mcp-cursor-absent")
    Files.createDirectories(home.resolve(".cursor"))
    val configPath = home.resolve(".cursor/mcp.json")

    val resultAbsent = McpRegistrationOperations.register("cursor", runtimeMcpBin, home)
    assertTrue(resultAbsent.changed)
    assertEquals(configPath, resultAbsent.configPath)
    val serverAbsent = skillBillServer(configPath)
    assertEquals("stdio", serverAbsent["type"])
    assertEquals("/tmp/runtime-mcp", serverAbsent["command"])

    Files.writeString(configPath, "{}")
    val resultEmpty = McpRegistrationOperations.register("cursor", runtimeMcpBin, home)
    assertTrue(resultEmpty.changed)
    val serverEmpty = skillBillServer(configPath)
    assertEquals("stdio", serverEmpty["type"])
    assertEquals("/tmp/runtime-mcp", serverEmpty["command"])
  }

  private fun assertContains(haystack: String, needle: String) {
    assertTrue(needle in haystack, "Expected '$haystack' to contain '$needle'")
  }
}
