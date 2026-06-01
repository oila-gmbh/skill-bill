package skillbill.cli

import skillbill.contracts.JsonSupport
import skillbill.nativeagent.NativeAgentProvider
import skillbill.nativeagent.NativeAgentSource
import skillbill.nativeagent.parseNativeAgentSource
import skillbill.nativeagent.renderNativeAgentSource
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
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)
    assertGeneratedAgentLinked(fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}"), fixture.codexToml)
    assertGeneratedAgentLinked(
      fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}"),
      fixture.opencodeMd,
    )
    assertGeneratedAgentLinked(fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}"), fixture.junieMd)

    assertEquals(0, runInstall(fixture, "unlink-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "unlink-opencode-agents").exitCode)
    assertEquals(0, runInstall(fixture, "unlink-junie-agents").exitCode)
    assertFalse(Files.exists(fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")))
    assertFalse(Files.exists(fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")))
    assertFalse(Files.exists(fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")))
  }

  @Test
  fun `claude native subagent commands link and unlink authored agent files`() {
    val fixture = installFixture()
    val sourcePath = fixture.codexToml.parent.parent
      .resolve("native-agents/${fixture.codexToml.fileName.toString().removeSuffix(".toml")}.md")
    val source = parseNativeAgentSource(sourcePath)
    val target = fixture.home.resolve(".claude/agents/${source.name}.md")

    assertEquals(0, runInstall(fixture, "link-claude-agents").exitCode)

    assertGeneratedAgentLinked(target, expected = null)
    assertEquals(NativeAgentProvider.Claude.render(source), Files.readString(target))

    assertEquals(0, runInstall(fixture, "unlink-claude-agents").exitCode)
    assertFalse(Files.exists(target))
  }

  @Test
  fun `native subagent commands only link selected platforms`() {
    val fixture = installFixture()

    assertEquals(0, runInstall(fixture, "link-codex-agents", "--platform", "kmp").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents", "--platform", "kmp").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents", "--platform", "kmp").exitCode)

    assertGeneratedAgentLinked(
      fixture.home.resolve(".codex/agents/${fixture.baseCodexToml.fileName}"),
      fixture.baseCodexToml,
    )
    assertGeneratedAgentLinked(
      fixture.home.resolve(".config/opencode/agents/${fixture.baseOpencodeMd.fileName}"),
      fixture.baseOpencodeMd,
    )
    assertGeneratedAgentLinked(
      fixture.home.resolve(".junie/agents/${fixture.baseJunieMd.fileName}"),
      fixture.baseJunieMd,
    )
    assertGeneratedAgentLinked(
      fixture.home.resolve(".codex/agents/${fixture.kmpCodexToml.fileName}"),
      fixture.kmpCodexToml,
    )
    assertGeneratedAgentLinked(
      fixture.home.resolve(".config/opencode/agents/${fixture.kmpOpencodeMd.fileName}"),
      fixture.kmpOpencodeMd,
    )
    assertGeneratedAgentLinked(fixture.home.resolve(".junie/agents/${fixture.kmpJunieMd.fileName}"), fixture.kmpJunieMd)
    assertFalse(Files.exists(fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")))
    assertFalse(Files.exists(fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")))
    assertFalse(Files.exists(fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")))
  }

  @Test
  fun `native subagent cleanup can remove stale junie links from unselected platforms`() {
    val fixture = installFixture()

    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)
    assertGeneratedAgentLinked(
      fixture.home.resolve(".junie/agents/${fixture.baseJunieMd.fileName}"),
      fixture.baseJunieMd,
    )
    assertGeneratedAgentLinked(fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}"), fixture.junieMd)
    assertGeneratedAgentLinked(fixture.home.resolve(".junie/agents/${fixture.kmpJunieMd.fileName}"), fixture.kmpJunieMd)

    assertEquals(0, runInstall(fixture, "unlink-junie-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents", "--platform", "kmp").exitCode)

    assertGeneratedAgentLinked(
      fixture.home.resolve(".junie/agents/${fixture.baseJunieMd.fileName}"),
      fixture.baseJunieMd,
    )
    assertFalse(Files.exists(fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")))
    assertGeneratedAgentLinked(fixture.home.resolve(".junie/agents/${fixture.kmpJunieMd.fileName}"), fixture.kmpJunieMd)
  }

  @Test
  fun `native subagent commands preserve existing regular target files`() {
    val fixture = installFixture()
    val codexTarget = fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")
    val opencodeTarget = fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")
    val junieTarget = fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")
    Files.createDirectories(codexTarget.parent)
    Files.createDirectories(opencodeTarget.parent)
    Files.createDirectories(junieTarget.parent)
    Files.writeString(codexTarget, "user codex file\n")
    Files.writeString(opencodeTarget, "user opencode file\n")
    Files.writeString(junieTarget, "user junie file\n")

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertFalse(Files.isSymbolicLink(codexTarget))
    assertFalse(Files.isSymbolicLink(opencodeTarget))
    assertFalse(Files.isSymbolicLink(junieTarget))
    assertEquals("user codex file\n", Files.readString(codexTarget))
    assertEquals("user opencode file\n", Files.readString(opencodeTarget))
    assertEquals("user junie file\n", Files.readString(junieTarget))
  }

  @Test
  fun `native subagent commands replace legacy repository artifact symlinks`() {
    val fixture = installFixture()
    val codexTarget = fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")
    val opencodeTarget = fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")
    val junieTarget = fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")
    val legacyRoot = fixture.home.resolve("old-repo")
    val legacyCodex = legacyRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/codex-agents")
      .resolve(fixture.codexToml.fileName)
    val legacyOpencode = legacyRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/opencode-agents")
      .resolve(fixture.opencodeMd.fileName)
    val legacyJunie = legacyRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/junie-agents")
      .resolve(fixture.junieMd.fileName)
    listOf(legacyCodex, legacyOpencode, legacyJunie).forEach { legacy ->
      Files.createDirectories(legacy.parent)
      Files.writeString(legacy, "legacy generated artifact\n")
    }
    Files.createDirectories(codexTarget.parent)
    Files.createDirectories(opencodeTarget.parent)
    Files.createDirectories(junieTarget.parent)
    Files.createSymbolicLink(codexTarget, legacyCodex)
    Files.createSymbolicLink(opencodeTarget, legacyOpencode)
    Files.createSymbolicLink(junieTarget, legacyJunie)

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertGeneratedAgentLinked(codexTarget, fixture.codexToml)
    assertGeneratedAgentLinked(opencodeTarget, fixture.opencodeMd)
    assertGeneratedAgentLinked(junieTarget, fixture.junieMd)
    assertFalse(Files.readString(codexTarget).contains("legacy generated artifact"))
    assertFalse(Files.readString(opencodeTarget).contains("legacy generated artifact"))
    assertFalse(Files.readString(junieTarget).contains("legacy generated artifact"))
  }

  @Test
  fun `native subagent commands replace stale install cache symlinks`() {
    val fixture = installFixture()
    val codexTarget = fixture.home.resolve(".codex/agents/${fixture.codexToml.fileName}")
    val opencodeTarget = fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")
    val junieTarget = fixture.home.resolve(".junie/agents/${fixture.junieMd.fileName}")
    val oldCache = fixture.home.resolve(".skill-bill/native-agents/old-cache-key")
    val oldCodex = oldCache.resolve("codex-agents/${fixture.codexToml.fileName}")
    val oldOpencode = oldCache.resolve("opencode-agents/${fixture.opencodeMd.fileName}")
    val oldJunie = oldCache.resolve("junie-agents/${fixture.junieMd.fileName}")
    listOf(oldCodex, oldOpencode, oldJunie).forEach { stale ->
      Files.createDirectories(stale.parent)
      Files.writeString(stale, "stale cache artifact\n")
    }
    Files.createDirectories(codexTarget.parent)
    Files.createDirectories(opencodeTarget.parent)
    Files.createDirectories(junieTarget.parent)
    Files.createSymbolicLink(codexTarget, oldCodex)
    Files.createSymbolicLink(opencodeTarget, oldOpencode)
    Files.createSymbolicLink(junieTarget, oldJunie)

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertGeneratedAgentLinked(codexTarget, fixture.codexToml)
    assertGeneratedAgentLinked(opencodeTarget, fixture.opencodeMd)
    assertGeneratedAgentLinked(junieTarget, fixture.junieMd)
    assertFalse(codexTarget.toRealPath().startsWith(oldCache))
    assertFalse(opencodeTarget.toRealPath().startsWith(oldCache))
    assertFalse(junieTarget.toRealPath().startsWith(oldCache))
  }

  @Test
  fun `native subagent discovery ignores symlinked source files`() {
    val fixture = installFixture()
    val outsideSource = fixture.home.resolve("outside-native.md")
    Files.writeString(
      outsideSource,
      renderNativeAgentSource(
        NativeAgentSource(name = "bill-symlinked", description = "Outside source.", body = "# Outside\n"),
      ),
    )
    val sourceSymlink = fixture.codexToml.parent.parent.resolve("native-agents/bill-symlinked.md")
    Files.createSymbolicLink(sourceSymlink, outsideSource)

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertFalse(Files.exists(fixture.home.resolve(".codex/agents/bill-symlinked.toml")))
    assertFalse(Files.exists(fixture.home.resolve(".config/opencode/agents/bill-symlinked.md")))
    assertFalse(Files.exists(fixture.home.resolve(".junie/agents/bill-symlinked.md")))
  }

  @Test
  fun `native subagent install renders from source without rewriting stale repository artifacts`() {
    val fixture = installFixture()
    val stale = "stale generated file\n"
    Files.createDirectories(fixture.opencodeMd.parent)
    Files.writeString(fixture.opencodeMd, stale)

    assertEquals(0, runInstall(fixture, "link-opencode-agents").exitCode)

    assertEquals(stale, Files.readString(fixture.opencodeMd))
    val installed = fixture.home.resolve(".config/opencode/agents/${fixture.opencodeMd.fileName}")
    assertGeneratedAgentLinked(installed, expected = null)
    assertContains(Files.readString(installed), "mode: subagent")
    assertFalse(Files.readString(installed).contains(stale))
  }

  @Test
  fun `link claude agents fails atomically when one source has invalid frontmatter`() {
    val fixture = installFixture()
    val skillDir = fixture.codexToml.parent.parent
    val malformedSourcePath = skillDir.resolve("native-agents/bill-malformed-source.md")
    Files.writeString(
      malformedSourcePath,
      """
      ---
      name: bill-malformed-source
      ---

      # Body
      """.trimIndent(),
    )

    val result = runInstall(fixture, "link-claude-agents")

    assertEquals(1, result.exitCode, result.stdout)
    val claudeAgentsDir = fixture.home.resolve(".claude/agents")
    if (Files.exists(claudeAgentsDir)) {
      val partialFiles = Files.list(claudeAgentsDir).use { stream ->
        stream.filter { path -> path.fileName.toString().startsWith("bill-") }.toList()
      }
      assertTrue(partialFiles.isEmpty(), "Expected no partial Claude agent files but found $partialFiles")
    }
  }

  @Test
  fun `native subagent install does not modify repository source files`() {
    val fixture = installFixture()
    val before = snapshotInstallRepo(fixture)

    assertEquals(0, runInstall(fixture, "link-codex-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-opencode-agents").exitCode)
    assertEquals(0, runInstall(fixture, "link-junie-agents").exitCode)

    assertEquals(before, snapshotInstallRepo(fixture))
  }

  @Test
  fun `mcp registration writes and removes packaged bin commands for all config formats`() {
    mcpCases().forEach { case ->
      val home = Files.createTempDirectory("skillbill-cli-mcp-${case.agent}")
      val context = installCliContext(home)
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
    val context = installCliContext(home)
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
        installCliContext(home),
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

    val result = CliRuntime.run(
      listOf("--home", home.toString(), "install", "agent-path", "codex"),
      installCliContext(home),
    )

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
      installCliContext(fixture.home),
    )

  private fun installCliContext(home: Path): CliRuntimeContext = CliRuntimeContext(
    userHome = home,
    environment = emptyMap(),
  )

  private fun installFixture(): InstallFixture {
    val home = Files.createTempDirectory("skillbill-cli-install-native")
    Files.createDirectories(home.resolve(".claude"))
    Files.createDirectories(home.resolve(".codex"))
    Files.createDirectories(home.resolve(".config/opencode"))
    Files.createDirectories(home.resolve(".junie"))
    val platformPacks = home.resolve("platform-packs")
    val skills = home.resolve("skills")
    val baseCodexAgents = skills.resolve("bill-code-review/codex-agents")
    val baseOpencodeAgents = skills.resolve("bill-code-review/opencode-agents")
    val baseJunieAgents = skills.resolve("bill-code-review/junie-agents")
    val baseNativeAgents = skills.resolve("bill-code-review/native-agents")
    val codexAgents = platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/codex-agents")
    val opencodeAgents = platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/opencode-agents")
    val junieAgents = platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/junie-agents")
    val nativeAgents = platformPacks.resolve("kotlin/code-review/bill-kotlin-code-review/native-agents")
    val kmpCodexAgents = platformPacks.resolve("kmp/code-review/bill-kmp-code-review/codex-agents")
    val kmpOpencodeAgents = platformPacks.resolve("kmp/code-review/bill-kmp-code-review/opencode-agents")
    val kmpJunieAgents = platformPacks.resolve("kmp/code-review/bill-kmp-code-review/junie-agents")
    val kmpNativeAgents = platformPacks.resolve("kmp/code-review/bill-kmp-code-review/native-agents")
    Files.createDirectories(baseNativeAgents)
    Files.createDirectories(nativeAgents)
    Files.createDirectories(kmpNativeAgents)
    val baseCodexToml = baseCodexAgents.resolve("bill-code-review-worker.toml")
    val baseOpencodeMd = baseOpencodeAgents.resolve("bill-code-review-worker.md")
    val baseJunieMd = baseJunieAgents.resolve("bill-code-review-worker.md")
    val codexToml = codexAgents.resolve("bill-kotlin-code-review-testing.toml")
    val opencodeMd = opencodeAgents.resolve("bill-kotlin-code-review-testing.md")
    val junieMd = junieAgents.resolve("bill-kotlin-code-review-testing.md")
    val kmpCodexToml = kmpCodexAgents.resolve("bill-kmp-code-review-ui.toml")
    val kmpOpencodeMd = kmpOpencodeAgents.resolve("bill-kmp-code-review-ui.md")
    val kmpJunieMd = kmpJunieAgents.resolve("bill-kmp-code-review-ui.md")
    val fixture = InstallFixture(
      home,
      platformPacks,
      skills,
      baseCodexToml,
      baseOpencodeMd,
      baseJunieMd,
      codexToml,
      opencodeMd,
      junieMd,
      kmpCodexToml,
      kmpOpencodeMd,
      kmpJunieMd,
    )
    writeInstallFixtureFiles(fixture)
    return fixture
  }

  private fun writeInstallFixtureFiles(fixture: InstallFixture) {
    writeNativeAgentSet(fixture.baseCodexToml, "bill-code-review-worker", "Review changed code.")
    writeNativeAgentSet(fixture.codexToml, "bill-kotlin-code-review-testing", "Review Kotlin tests.")
    writeNativeAgentSet(fixture.kmpCodexToml, "bill-kmp-code-review-ui", "Review KMP UI.")
  }

  private fun assertGeneratedAgentLinked(path: Path, expected: Path?) {
    assertTrue(Files.isSymbolicLink(path))
    assertContains(path.toRealPath().toString(), ".skill-bill")
    if (expected != null) {
      val provider = NativeAgentProvider.entries.first { provider ->
        provider.directoryName == expected.parent.fileName.toString()
      }
      val name = expected.fileName.toString().removeSuffix(".${provider.extension}")
      val source = parseNativeAgentSource(expected.parent.parent.resolve("native-agents/$name.md"))
      assertEquals(provider.render(source), Files.readString(path))
    }
  }

  private fun writeNativeAgentSet(codexPath: Path, name: String, description: String) {
    val skillDir = codexPath.parent.parent
    val source = NativeAgentSource(name = name, description = description, body = "# $name\n\nDo the work.")
    Files.writeString(skillDir.resolve("native-agents/$name.md"), renderNativeAgentSource(source))
  }

  private fun snapshotInstallRepo(fixture: InstallFixture): Map<String, String> =
    listOf(fixture.skills, fixture.platformPacks).flatMap { root ->
      Files.walk(root).use { stream ->
        stream
          .filter(Files::isRegularFile)
          .sorted()
          .toList()
          .map { path -> root.relativize(path).toString() to Files.readString(path) }
      }
    }.toMap()
}

private data class InstallFixture(
  val home: Path,
  val platformPacks: Path,
  val skills: Path,
  val baseCodexToml: Path,
  val baseOpencodeMd: Path,
  val baseJunieMd: Path,
  val codexToml: Path,
  val opencodeMd: Path,
  val junieMd: Path,
  val kmpCodexToml: Path,
  val kmpOpencodeMd: Path,
  val kmpJunieMd: Path,
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
  junieMcpCase(),
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

private fun junieMcpCase(): McpCase = mcpJsonCase(
  agent = "junie",
  relativeConfigPath = ".junie/mcp/mcp.json",
  seed = "{\n  \"mcpServers\": {\"other\": {\"command\": \"other\"}}\n}\n",
  expectedKey = null,
  expectedValue = null,
)

private fun mcpJsonCase(
  agent: String,
  relativeConfigPath: String,
  seed: String,
  expectedKey: String?,
  expectedValue: Any?,
): McpCase = McpCase(
  agent = agent,
  relativeConfigPath = relativeConfigPath,
  seed = seed,
  assertRegistered = { raw ->
    val settings = decodeJsonObject(raw)
    if (expectedKey != null) {
      assertEquals(expectedValue, settings[expectedKey])
    }
    val servers = settings["mcpServers"] as Map<*, *>
    assertTrue("other" in servers)
    val skillBill = servers["skill-bill"] as Map<*, *>
    assertEquals("/tmp/runtime-mcp", skillBill["command"])
  },
  assertUnregistered = { raw ->
    val settings = decodeJsonObject(raw)
    if (expectedKey != null) {
      assertEquals(expectedValue, settings[expectedKey])
    }
    assertTrue("other" in (settings["mcpServers"] as Map<*, *>))
    assertFalse("skill-bill" in (settings["mcpServers"] as Map<*, *>))
  },
)

private fun decodeJsonObject(rawJson: String): Map<String, Any?> =
  JsonSupport.anyToStringAnyMap(JsonSupport.parseObjectOrNull(rawJson)?.let(JsonSupport::jsonElementToValue))
    ?: emptyMap()
