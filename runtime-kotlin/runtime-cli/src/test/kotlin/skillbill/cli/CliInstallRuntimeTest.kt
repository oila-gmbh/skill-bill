package skillbill.cli

import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliInstallRuntimeTest {
  @Test
  fun `native subagent commands link and unlink authored agent files`() {
    val fixture = installFixture()

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents").exitCode)
    assertTrue(Files.isSymbolicLink(fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")))
    assertTrue(Files.isSymbolicLink(fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")))

    assertEquals(0, runInstall(fixture, "unlink-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "unlink-opencode-agents").exitCode)
    assertFalse(Files.exists(fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")))
    assertFalse(Files.exists(fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")))
  }

  @Test
  fun `native subagent commands only link selected platforms`() {
    val fixture = installFixture()

    assertEquals(0, runInstall(fixture, "link-codex-agents", "--platform", "kmp").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents", "--platform", "kmp").exitCode)

    assertTrue(Files.isSymbolicLink(fixture.home.resolve(".codex/agents/${fixture.baseCodexToml.fileName}")))
    assertTrue(Files.isSymbolicLink(fixture.home.resolve(".config/opencode/agents/${fixture.baseOpencodeMd.fileName}")))
    assertTrue(Files.isSymbolicLink(fixture.home.resolve(".codex/agents/${fixture.kmpCodexToml.fileName}")))
    assertTrue(Files.isSymbolicLink(fixture.home.resolve(".config/opencode/agents/${fixture.kmpOpencodeMd.fileName}")))
    assertFalse(Files.exists(fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")))
    assertFalse(Files.exists(fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")))
  }

  @Test
  fun `native subagent commands preserve existing regular target files`() {
    val fixture = installFixture()
    val codexTarget = fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")
    val opencodeTarget = fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")
    Files.createDirectories(codexTarget.parent)
    Files.createDirectories(opencodeTarget.parent)
    Files.writeString(codexTarget, "user codex file\n")
    Files.writeString(opencodeTarget, "user opencode file\n")

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents").exitCode)

    assertFalse(Files.isSymbolicLink(codexTarget))
    assertFalse(Files.isSymbolicLink(opencodeTarget))
    assertEquals("user codex file\n", Files.readString(codexTarget))
    assertEquals("user opencode file\n", Files.readString(opencodeTarget))
  }

  @Test
  fun `native subagent discovery ignores symlinked source files`() {
    val fixture = installFixture()
    val outsideCodexSource = fixture.home.resolve("outside-codex.toml")
    val outsideOpencodeSource = fixture.home.resolve("outside-opencode.md")
    Files.writeString(outsideCodexSource, "name = \"outside\"\n")
    Files.writeString(outsideOpencodeSource, "---\nname: outside\n---\n")
    val codexSymlink = fixture.codexToml.parent.resolve("bill-symlinked.toml")
    val opencodeSymlink = fixture.opencodeMd.parent.resolve("bill-symlinked.md")
    Files.createSymbolicLink(codexSymlink, outsideCodexSource)
    Files.createSymbolicLink(opencodeSymlink, outsideOpencodeSource)

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents").exitCode)

    assertFalse(Files.exists(fixture.home.resolve(".codex/agents/${codexSymlink.fileName}")))
    assertFalse(Files.exists(fixture.home.resolve(".config/opencode/agents/${opencodeSymlink.fileName}")))
  }

  @Test
  fun `mcp registration writes and removes packaged bin commands for all config formats`() {
    mcpCases().forEach { case ->
      val home = Files.createTempDirectory("skillbill-cli-mcp-${case.agent}")
      val context = CliRuntimeContext(userHome = home)
      val configPath = home.resolve(case.relativeConfigPath)
      Files.createDirectories(configPath.parent)
      Files.writeString(configPath, case.seed)

      val register = CliRuntime.run(
        listOf("install", "register-mcp", case.agent, "--runtime-mcp-bin", "/tmp/runtime-mcp"),
        context,
      )
      assertEquals(0, register.exitCode, "${case.agent}: ${register.stdout}")
      case.assertRegistered(Files.readString(configPath))

      val unregister = CliRuntime.run(listOf("install", "unregister-mcp", case.agent), context)
      assertEquals(0, unregister.exitCode, "${case.agent}: ${unregister.stdout}")
      case.assertUnregistered(Files.readString(configPath))
    }
  }

  @Test
  fun `opencode mcp registration fails loudly and preserves invalid jsonc`() {
    val home = Files.createTempDirectory("skillbill-cli-invalid-opencode")
    val context = CliRuntimeContext(userHome = home)
    val configPath = home.resolve(".config/opencode/opencode.json")
    Files.createDirectories(configPath.parent)
    val invalid = "{\n  \"theme\": \"opencode\",\n  \"mcp\": \n"
    Files.writeString(configPath, invalid)

    val register =
      CliRuntime.run(listOf("install", "register-mcp", "opencode", "--runtime-mcp-bin", "/tmp/runtime-mcp"), context)
    assertEquals(1, register.exitCode)
    assertContains(register.stdout, "Invalid OpenCode JSONC config")
    assertEquals(invalid, Files.readString(configPath))

    val unregister = CliRuntime.run(listOf("install", "unregister-mcp", "opencode"), context)
    assertEquals(1, unregister.exitCode)
    assertContains(unregister.stdout, "Invalid OpenCode JSONC config")
    assertEquals(invalid, Files.readString(configPath))
  }

  @Test
  fun `cleanup command removes skill bill links and reports user paths as skipped`() {
    val home = Files.createTempDirectory("skillbill-cli-install-cleanup")
    val targetDir = home.resolve("agent-skills")
    Files.createDirectories(targetDir)
    val skillSource = home.resolve("bill-test-skill")
    Files.createDirectories(skillSource)
    Files.createSymbolicLink(targetDir.resolve("bill-test-skill"), skillSource)
    Files.writeString(targetDir.resolve("not-skill-bill"), "user file")

    val cleanup =
      CliRuntime.run(
        listOf(
          "install",
          "cleanup-agent-target",
          "--target-dir",
          targetDir.toString(),
          "--skill-name",
          "bill-test-skill",
          "--legacy-name",
          "not-skill-bill",
        ),
        CliRuntimeContext(userHome = home),
      )

    assertEquals(0, cleanup.exitCode, cleanup.stdout)
    assertContains(cleanup.stdout, "removed\t${targetDir.resolve("bill-test-skill")}")
    assertContains(cleanup.stdout, "skipped\t${targetDir.resolve("not-skill-bill")}")
    assertFalse(Files.exists(targetDir.resolve("bill-test-skill")))
    assertTrue(Files.exists(targetDir.resolve("not-skill-bill")))
  }

  @Test
  fun `global home option supports paths with spaces`() {
    val home = Files.createTempDirectory("skillbill cli home with spaces")
    Files.createDirectories(home.resolve(".codex"))

    val result = CliRuntime.run(listOf("--home", home.toString(), "install", "agent-path", "codex"))

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(home.resolve(".codex/skills").toString(), result.stdout.trim())
  }

  private fun runInstall(fixture: InstallFixture, command: String, vararg extraArgs: String): CliExecutionResult =
    CliRuntime.run(
      listOf(
        "install",
        command,
        "--platform-packs",
        fixture.platformPacks.toString(),
        "--skills",
        fixture.skills.toString(),
      ) + extraArgs,
      CliRuntimeContext(userHome = fixture.home),
    )

  private fun installFixture(): InstallFixture {
    val home = Files.createTempDirectory("skillbill-cli-install-native")
    Files.createDirectories(home.resolve(".codex"))
    Files.createDirectories(home.resolve(".config/opencode"))
    val platformPacks = home.resolve("platform-packs")
    val skills = home.resolve("skills")
    val baseCodexAgents = skills.resolve("bill-code-review/codex-agents")
    val baseOpencodeAgents = skills.resolve("bill-code-review/opencode-agents")
    val codexAgents = platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/codex-agents")
    val opencodeAgents = platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/opencode-agents")
    val kmpCodexAgents = platformPacks.resolve("kmp/code-review/bill-kmp-code-review/codex-agents")
    val kmpOpencodeAgents = platformPacks.resolve("kmp/code-review/bill-kmp-code-review/opencode-agents")
    Files.createDirectories(baseCodexAgents)
    Files.createDirectories(baseOpencodeAgents)
    Files.createDirectories(codexAgents)
    Files.createDirectories(opencodeAgents)
    Files.createDirectories(kmpCodexAgents)
    Files.createDirectories(kmpOpencodeAgents)
    val baseCodexToml = baseCodexAgents.resolve("bill-code-review-worker.toml")
    val baseOpencodeMd = baseOpencodeAgents.resolve("bill-code-review-worker.md")
    val codexToml = codexAgents.resolve("bill-kotlin-code-review-testing.toml")
    val opencodeMd = opencodeAgents.resolve("bill-kotlin-code-review-testing.md")
    val kmpCodexToml = kmpCodexAgents.resolve("bill-kmp-code-review-ui.toml")
    val kmpOpencodeMd = kmpOpencodeAgents.resolve("bill-kmp-code-review-ui.md")
    Files.writeString(baseCodexToml, "name = \"bill-code-review-worker\"\n")
    Files.writeString(baseOpencodeMd, "---\nname: bill-code-review-worker\n---\n")
    Files.writeString(codexToml, "name = \"bill-kotlin-code-review-testing\"\n")
    Files.writeString(opencodeMd, "---\nname: bill-kotlin-code-review-testing\n---\n")
    Files.writeString(kmpCodexToml, "name = \"bill-kmp-code-review-ui\"\n")
    Files.writeString(kmpOpencodeMd, "---\nname: bill-kmp-code-review-ui\n---\n")
    return InstallFixture(
      home,
      platformPacks,
      skills,
      baseCodexToml,
      baseOpencodeMd,
      codexToml,
      opencodeMd,
      kmpCodexToml,
      kmpOpencodeMd,
    )
  }
}

private data class InstallFixture(
  val home: Path,
  val platformPacks: Path,
  val skills: Path,
  val baseCodexToml: Path,
  val baseOpencodeMd: Path,
  val codexToml: Path,
  val opencodeMd: Path,
  val kmpCodexToml: Path,
  val kmpOpencodeMd: Path,
)

private data class McpCase(
  val agent: String,
  val relativeConfigPath: String,
  val seed: String,
  val assertRegistered: (String) -> Unit,
  val assertUnregistered: (String) -> Unit,
)

private fun mcpCases(): List<McpCase> = listOf(
  mcpJsonCase(
    agent = "claude",
    relativeConfigPath = ".claude.json",
    seed = "{\n  \"theme\": \"dark\",\n  \"mcpServers\": {\"other\": {\"command\": \"other\"}}\n}\n",
    expectedKey = "theme",
    expectedValue = "dark",
  ),
  mcpJsonCase(
    agent = "copilot",
    relativeConfigPath = ".copilot/mcp-config.json",
    seed = "{\n  \"enabled\": true,\n  \"mcpServers\": {\"other\": {\"command\": \"other\"}}\n}\n",
    expectedKey = "enabled",
    expectedValue = true,
  ),
  McpCase(
    agent = "codex",
    relativeConfigPath = ".codex/config.toml",
    seed = "[profile.default]\nmodel = \"gpt-5\"\n\n[mcp_servers.other]\ncommand = \"other\"\nargs = []\n",
    assertRegistered = { raw ->
      assertContains(raw, "[profile.default]")
      assertContains(raw, "[mcp_servers.other]")
      assertContains(raw, "[mcp_servers.skill-bill]")
      assertContains(raw, "command = \"/tmp/runtime-mcp\"")
    },
    assertUnregistered = { raw ->
      assertContains(raw, "[profile.default]")
      assertContains(raw, "[mcp_servers.other]")
      assertFalse("[mcp_servers.skill-bill]" in raw)
    },
  ),
  McpCase(
    agent = "opencode",
    relativeConfigPath = ".config/opencode/opencode.json",
    seed = """
      {
        // keep user setting
        "theme": "opencode",
        "mcp": {"other": {"command": ["other"]}},
      }
    """.trimIndent() + "\n",
    assertRegistered = { raw ->
      val settings = decodeJsonObject(raw)
      assertEquals("opencode", settings["theme"])
      val servers = settings["mcp"] as Map<*, *>
      assertTrue("other" in servers)
      val skillBill = servers["skill-bill"] as Map<*, *>
      assertEquals(listOf("/tmp/runtime-mcp"), skillBill["command"])
    },
    assertUnregistered = { raw ->
      val settings = decodeJsonObject(raw)
      assertEquals("opencode", settings["theme"])
      assertTrue("other" in (settings["mcp"] as Map<*, *>))
      assertFalse("skill-bill" in (settings["mcp"] as Map<*, *>))
    },
  ),
)

private fun mcpJsonCase(
  agent: String,
  relativeConfigPath: String,
  seed: String,
  expectedKey: String,
  expectedValue: Any,
): McpCase = McpCase(
  agent = agent,
  relativeConfigPath = relativeConfigPath,
  seed = seed,
  assertRegistered = { raw ->
    val settings = decodeJsonObject(raw)
    assertEquals(expectedValue, settings[expectedKey])
    val servers = settings["mcpServers"] as Map<*, *>
    assertTrue("other" in servers)
    val skillBill = servers["skill-bill"] as Map<*, *>
    assertEquals("/tmp/runtime-mcp", skillBill["command"])
  },
  assertUnregistered = { raw ->
    val settings = decodeJsonObject(raw)
    assertEquals(expectedValue, settings[expectedKey])
    assertTrue("other" in (settings["mcpServers"] as Map<*, *>))
    assertFalse("skill-bill" in (settings["mcpServers"] as Map<*, *>))
  },
)

private fun decodeJsonObject(rawJson: String): Map<String, Any?> =
  JsonSupport.anyToStringAnyMap(JsonSupport.parseObjectOrNull(rawJson)?.let(JsonSupport::jsonElementToValue))
    ?: emptyMap()
