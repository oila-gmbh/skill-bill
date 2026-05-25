package skillbill.install

import skillbill.contracts.JsonSupport
import skillbill.error.MalformedInstallSelectionRecordError
import skillbill.error.MissingInstallSelectionRecordError
import skillbill.error.UnreadableInstallSelectionRecordError
import skillbill.infrastructure.fs.FileSystemInstallSelectionPersistence
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.SharedInstallSelection
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileSystemInstallSelectionPersistenceTest {
  @Test
  fun `writes and reads latest successful install selection`() {
    val home = Files.createTempDirectory("skillbill-install-selection-home")
    val store = FileSystemInstallSelectionPersistence()
    val selection = SharedInstallSelection(
      selectedAgents = setOf(InstallAgent.CODEX, InstallAgent.CLAUDE),
      platformPackSelection = PlatformPackSelection(
        mode = PlatformPackSelectionMode.SELECTED,
        selectedSlugs = setOf("kotlin", "kmp"),
      ),
      telemetryLevel = InstallTelemetryLevel.FULL,
      mcpRegistrationChoice = McpRegistrationChoice(
        register = true,
        runtimeMcpBin = Path.of("/runtime-mcp/bin/runtime-mcp"),
      ),
    )

    val writeResult = store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = selection),
    )
    val readResult = store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))

    assertEquals(home.resolve(".skill-bill/install-selection.json"), writeResult.path)
    assertEquals(selection, readResult.selection)
  }

  @Test
  fun `reads canonical v1 json record and writer preserves durable keys`() {
    val home = Files.createTempDirectory("skillbill-install-selection-canonical")
    val path = home.resolve(".skill-bill/install-selection.json")
    Files.createDirectories(path.parent)
    Files.writeString(
      path,
      """
      {
        "contract_version": "1.0",
        "selected_agents": ["claude", "codex"],
        "platform_pack_selection": {
          "mode": "selected",
          "selected_slugs": ["kmp", "kotlin"]
        },
        "telemetry_level": "full",
        "mcp_registration": {
          "register": true,
          "runtime_mcp_bin": "/runtime-mcp/bin/runtime-mcp"
        }
      }
      """.trimIndent(),
    )
    val store = FileSystemInstallSelectionPersistence()

    val selection = store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection

    assertEquals(setOf(InstallAgent.CLAUDE, InstallAgent.CODEX), selection.selectedAgents)
    assertEquals(PlatformPackSelectionMode.SELECTED, selection.platformPackSelection.mode)
    assertEquals(setOf("kmp", "kotlin"), selection.platformPackSelection.selectedSlugs)
    assertEquals(InstallTelemetryLevel.FULL, selection.telemetryLevel)
    assertEquals(true, selection.mcpRegistrationChoice.register)
    assertEquals(Path.of("/runtime-mcp/bin/runtime-mcp"), selection.mcpRegistrationChoice.runtimeMcpBin)

    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = selection),
    )
    val emittedPayload = JsonSupport.anyToStringAnyMap(
      JsonSupport.parseObjectOrNull(Files.readString(path))?.let(JsonSupport::jsonElementToValue),
    ).orEmpty()
    assertEquals(
      mapOf(
        "contract_version" to "1.0",
        "selected_agents" to listOf("claude", "codex"),
        "platform_pack_selection" to mapOf(
          "mode" to "selected",
          "selected_slugs" to listOf("kmp", "kotlin"),
        ),
        "telemetry_level" to "full",
        "mcp_registration" to mapOf(
          "register" to true,
          "runtime_mcp_bin" to "/runtime-mcp/bin/runtime-mcp",
        ),
      ),
      emittedPayload,
    )
  }

  @Test
  fun `requests select alternate install home per operation`() {
    val firstHome = Files.createTempDirectory("skillbill-install-selection-first")
    val alternateHome = Files.createTempDirectory("skillbill-install-selection-alternate")
    val store = FileSystemInstallSelectionPersistence()
    val selection = selection(
      selectedAgents = setOf(InstallAgent.OPENCODE),
      platformPackSelection = PlatformPackSelection(PlatformPackSelectionMode.ALL),
    )

    val writeResult = store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = alternateHome, selection = selection),
    )

    assertEquals(alternateHome.resolve(".skill-bill/install-selection.json"), writeResult.path)
    assertEquals(
      selection,
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(alternateHome)).selection,
    )
    assertFailsWith<MissingInstallSelectionRecordError> {
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(firstHome))
    }
  }

  @Test
  fun `latest successful selection write overwrites previous record and persists mcp opt out`() {
    val home = Files.createTempDirectory("skillbill-install-selection-overwrite")
    val store = FileSystemInstallSelectionPersistence()
    val initialSelection = selection(
      selectedAgents = setOf(InstallAgent.CLAUDE),
      telemetryLevel = InstallTelemetryLevel.FULL,
      mcpRegistrationChoice = McpRegistrationChoice(
        register = true,
        runtimeMcpBin = Path.of("/runtime-mcp/bin/runtime-mcp"),
      ),
    )
    val latestSelection = selection(
      selectedAgents = setOf(InstallAgent.CODEX),
      platformPackSelection = PlatformPackSelection(
        mode = PlatformPackSelectionMode.SELECTED,
        selectedSlugs = setOf("kotlin"),
      ),
      telemetryLevel = InstallTelemetryLevel.OFF,
      mcpRegistrationChoice = McpRegistrationChoice(register = false, runtimeMcpBin = null),
    )

    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = initialSelection),
    )
    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = latestSelection),
    )

    assertEquals(
      latestSelection,
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection,
    )
  }

  @Test
  fun `write rejects platform selections that read parser would reject`() {
    val home = Files.createTempDirectory("skillbill-install-selection-write-invariants")
    val store = FileSystemInstallSelectionPersistence()
    val validSelection = selection(selectedAgents = setOf(InstallAgent.CLAUDE))
    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = validSelection),
    )

    val invalidSelections = mapOf(
      "selected mode with empty slugs" to selection(
        platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.SELECTED),
      ),
      "all mode with selected slugs" to selection(
        platformPackSelection = PlatformPackSelection(
          mode = PlatformPackSelectionMode.ALL,
          selectedSlugs = setOf("kotlin"),
        ),
      ),
    )

    invalidSelections.forEach { (caseName, invalidSelection) ->
      assertFailsWith<MalformedInstallSelectionRecordError>(caseName) {
        store.writeLatestSuccessfulSelection(
          WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = invalidSelection),
        )
      }
    }
    assertEquals(
      validSelection,
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection,
    )
  }

  @Test
  fun `canonical record round trips manual and detected reusable selections`() {
    val cases = mapOf(
      "manual single-agent opt out" to selection(
        selectedAgents = setOf(InstallAgent.CODEX),
        telemetryLevel = InstallTelemetryLevel.OFF,
        mcpRegistrationChoice = McpRegistrationChoice(register = false, runtimeMcpBin = null),
      ),
      "detected multi-agent registration" to selection(
        selectedAgents = setOf(InstallAgent.CLAUDE, InstallAgent.OPENCODE),
        platformPackSelection = PlatformPackSelection(PlatformPackSelectionMode.ALL),
        telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
        mcpRegistrationChoice = McpRegistrationChoice(
          register = true,
          runtimeMcpBin = Path.of("/runtime-mcp/bin/runtime-mcp"),
        ),
      ),
    )
    val store = FileSystemInstallSelectionPersistence()

    cases.forEach { (caseName, expectedSelection) ->
      val home = Files.createTempDirectory("skillbill-install-selection-$caseName")

      store.writeLatestSuccessfulSelection(
        WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = expectedSelection),
      )

      assertEquals(
        expectedSelection,
        store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection,
        caseName,
      )
      val emittedPayload = JsonSupport.anyToStringAnyMap(
        JsonSupport.parseObjectOrNull(Files.readString(home.resolve(".skill-bill/install-selection.json")))
          ?.let(JsonSupport::jsonElementToValue),
      ).orEmpty()
      assertFalse("recentRepoPath" in emittedPayload, caseName)
      assertFalse("firstRun.agents" in emittedPayload, caseName)
      assertEquals("1.0", emittedPayload["contract_version"], caseName)
    }
  }

  @Test
  fun `missing install selection record fails loudly`() {
    val home = Files.createTempDirectory("skillbill-install-selection-missing")
    val store = FileSystemInstallSelectionPersistence()

    val error = assertFailsWith<MissingInstallSelectionRecordError> {
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
    }

    assertContains(error.message.orEmpty(), "install-selection.json")
  }

  @Test
  fun `malformed install selection record fails loudly`() {
    val home = Files.createTempDirectory("skillbill-install-selection-malformed")
    val path = home.resolve(".skill-bill/install-selection.json")
    Files.createDirectories(path.parent)
    Files.writeString(path, "{\"recentRepoPath\":\"/repo\"}")
    val store = FileSystemInstallSelectionPersistence()

    val error = assertFailsWith<MalformedInstallSelectionRecordError> {
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
    }

    assertContains(error.message.orEmpty(), "unknown keys")
    assertContains(error.message.orEmpty(), "recentRepoPath")
  }

  @Test
  fun `oversized install selection record fails before parsing`() {
    val home = Files.createTempDirectory("skillbill-install-selection-oversized")
    val path = home.resolve(".skill-bill/install-selection.json")
    Files.createDirectories(path.parent)
    Files.writeString(path, "x".repeat(64 * 1024 + 1))
    val store = FileSystemInstallSelectionPersistence()

    assertFailsWith<UnreadableInstallSelectionRecordError> {
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
    }
  }

  @Test
  fun `oversized install selection write fails without replacing previous record`() {
    val home = Files.createTempDirectory("skillbill-install-selection-oversized-write")
    val store = FileSystemInstallSelectionPersistence()
    val previousSelection = selection(selectedAgents = setOf(InstallAgent.CLAUDE))
    store.writeLatestSuccessfulSelection(
      WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = previousSelection),
    )
    val oversizedSelection = selection(
      platformPackSelection = PlatformPackSelection(
        mode = PlatformPackSelectionMode.SELECTED,
        selectedSlugs = (1..900).mapTo(mutableSetOf()) { index -> "slug-$index-${"a".repeat(80)}" },
      ),
    )

    assertFailsWith<UnreadableInstallSelectionRecordError> {
      store.writeLatestSuccessfulSelection(
        WriteLatestSuccessfulInstallSelectionRequest(installHome = home, selection = oversizedSelection),
      )
    }

    assertEquals(
      previousSelection,
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home)).selection,
    )
  }

  @Test
  fun `invalid install selection records fail loudly`() {
    val cases = mapOf(
      "unsupported contract version" to validPayload(contractVersion = "2.0"),
      "missing required field" to """
        {
          "contract_version": "1.0",
          "selected_agents": [],
          "platform_pack_selection": {"mode": "none", "selected_slugs": []},
          "telemetry_level": "anonymous"
        }
      """.trimIndent(),
      "bad agent id" to validPayload(selectedAgents = listOf("codex", "unknown-agent")),
      "selected platform mode with empty slugs" to validPayload(platformMode = "selected", platformSlugs = emptyList()),
      "selected platform mode with empty slug" to validPayload(platformMode = "selected", platformSlugs = listOf("")),
      "selected platform mode with whitespace slug" to validPayload(
        platformMode = "selected",
        platformSlugs = listOf("   "),
      ),
      "none platform mode with selected slugs" to validPayload(platformMode = "none", platformSlugs = listOf("kotlin")),
      "invalid nested mcp field" to validPayload(runtimeMcpBin = "\"\""),
    )
    val store = FileSystemInstallSelectionPersistence()

    cases.forEach { (caseName, payload) ->
      val home = Files.createTempDirectory("skillbill-install-selection-invalid")
      val path = home.resolve(".skill-bill/install-selection.json")
      Files.createDirectories(path.parent)
      Files.writeString(path, payload)

      assertFailsWith<MalformedInstallSelectionRecordError>(caseName) {
        store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
      }
    }
  }

  @Test
  fun `unreadable install selection record fails loudly`() {
    val home = Files.createTempDirectory("skillbill-install-selection-unreadable")
    val path = home.resolve(".skill-bill/install-selection.json")
    Files.createDirectories(path)
    val store = FileSystemInstallSelectionPersistence()

    assertFailsWith<UnreadableInstallSelectionRecordError> {
      store.readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(home))
    }
  }

  private fun selection(
    selectedAgents: Set<InstallAgent> = setOf(InstallAgent.CODEX),
    platformPackSelection: PlatformPackSelection = PlatformPackSelection(PlatformPackSelectionMode.NONE),
    telemetryLevel: InstallTelemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice: McpRegistrationChoice = McpRegistrationChoice(register = false),
  ): SharedInstallSelection = SharedInstallSelection(
    selectedAgents = selectedAgents,
    platformPackSelection = platformPackSelection,
    telemetryLevel = telemetryLevel,
    mcpRegistrationChoice = mcpRegistrationChoice,
  )

  private fun validPayload(
    contractVersion: String = "1.0",
    selectedAgents: List<String> = listOf("codex"),
    platformMode: String = "none",
    platformSlugs: List<String> = emptyList(),
    runtimeMcpBin: String = "null",
  ): String = """
    {
      "contract_version": "$contractVersion",
      "selected_agents": [${selectedAgents.joinToString { "\"$it\"" }}],
      "platform_pack_selection": {
        "mode": "$platformMode",
        "selected_slugs": [${platformSlugs.joinToString { "\"$it\"" }}]
      },
      "telemetry_level": "anonymous",
      "mcp_registration": {
        "register": false,
        "runtime_mcp_bin": $runtimeMcpBin
      }
    }
  """.trimIndent()
}
