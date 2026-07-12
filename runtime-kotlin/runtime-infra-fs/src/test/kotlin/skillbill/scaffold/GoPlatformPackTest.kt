package skillbill.scaffold

import skillbill.error.InvalidManifestSchemaError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.install.runtime.InstallOperations
import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoPlatformPackTest {
  @Test
  fun `go platform pack declares expected manifest contract`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/go")

    val pack = loadPlatformPack(packRoot)

    assertEquals("go", pack.slug)
    assertEquals("Go", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(
      packRoot.resolve("code-review/bill-go-code-review/content.md"),
      pack.declaredFiles.baseline,
    )
    assertEquals(
      packRoot.resolve("quality-check/bill-go-code-check/content.md"),
      pack.declaredQualityCheckFile,
    )
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.areaMetadata.keys)

    listOf(
      "go.mod",
      "go.sum",
      ".go",
      "*.go",
      "go.work",
      "go.work.sum",
      "golangci.yml",
      ".golangci.yml",
    ).forEach { marker ->
      assertContains(pack.routingSignals.strong, marker)
    }
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("module/workspace metadata") })
  }

  @Test
  fun `go quality-check content must remain an internal bill-code-check sidecar`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-go-pack-malformed-")
    val packRoot = tempRoot.resolve("go")
    copyDirectory(repoRoot.resolve("platform-packs/go"), packRoot)

    val qualityCheckContent = packRoot.resolve("quality-check/bill-go-code-check/content.md")
    Files.writeString(
      qualityCheckContent,
      Files.readString(qualityCheckContent).replace("internal-for: bill-code-check\n", ""),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPlatformPack(packRoot)
    }

    assertContains(error.message.orEmpty(), "internal-for: bill-code-check")
    assertContains(error.message.orEmpty(), "quality-check")
  }

  @Test
  fun `go review and quality-check source content is authored`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/go")
    val contentFiles = Files.walk(packRoot).use { stream ->
      stream
        .filter { path -> path.fileName.toString() == "content.md" }
        .sorted()
        .toList()
    }

    assertEquals(12, contentFiles.size)
    contentFiles.forEach { contentFile ->
      val text = Files.readString(contentFile)
      assertTrue(text.contains("internal-for:"), "Missing internal classification in $contentFile")
      assertFalse(text.contains("TODO"), "Go pack source must not contain TODO placeholders: $contentFile")
      assertTrue(
        text.lines().size > 12,
        "Go pack source should contain substantive guidance: $contentFile",
      )
    }
  }

  @Test
  fun `go review metadata routing agents and rubrics expose concrete lane responsibilities`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/go")
    val pack = loadPlatformPack(packRoot)
    val expectedFocusMarkers = mapOf(
      "api-contracts" to "JSON/protobuf evolution",
      "architecture" to "goroutine scope ownership",
      "performance" to "pprof",
      "persistence" to "database/sql",
      "platform-correctness" to "protocol-specific channel ownership",
      "reliability" to "delivery semantics",
      "security" to "connection-time SSRF",
      "testing" to "parallel capture",
      "ui" to "htmx fragment ownership",
      "ux-accessibility" to "conditional focus restoration",
    )

    expectedFocusMarkers.forEach { (area, marker) ->
      assertContains(pack.areaMetadata.getValue(area), marker)
    }

    val baseline = Files.readString(pack.declaredFiles.baseline)
    mapOf(
      "errors.Is" to "platform-correctness",
      "http.Server.Shutdown" to "reliability",
      "Bubble Tea" to "ui",
      "color-independent status" to "ux-accessibility",
    ).forEach { (signal, area) ->
      val routingRule = baseline.lines().single { line -> signal in line }
      assertContains(routingRule, "-> `$area` specialist.")
    }

    val agents = parseNativeAgentBundle(
      packRoot.resolve("code-review/bill-go-code-review/native-agents/agents.yaml"),
    ).associateBy { agent -> agent.name.removePrefix("bill-go-code-review-") }
    assertEquals(APPROVED_CODE_REVIEW_AREAS, agents.keys)
    agents.forEach { (area, agent) ->
      assertEquals(
        "${pack.displayName} ${area.replace('-', ' ')} specialist code reviewer. " +
          "Runs against ${pack.areaMetadata.getValue(area)}. " +
          "Returns a Risk Register in the F-XXX bullet format.",
        agent.description,
      )
    }

    val rubricMarkers = mapOf(
      "api-contracts" to listOf("http.MaxBytesReader", "schema evolution", "protobuf presence"),
      "architecture" to listOf("cmd/<name>/main.go", "goroutines do not need invented names"),
      "performance" to listOf("go tool pprof", "mere absence as a defect"),
      "persistence" to listOf("sql.ErrNoRows", "expand-and-contract migrations"),
      "platform-correctness" to listOf("channels need not always close", "confinement"),
      "reliability" to listOf("http.Server.Close", "hijacked connections", "at-most-once"),
      "security" to listOf("OAuth 2.0", "O_NOFOLLOW", "DialContext", "option injection"),
      "testing" to listOf("go test -race", "immediately after successful resource acquisition"),
      "ui" to listOf("HX-Retarget", "HX-Trigger", "universal `RenderState`"),
      "ux-accessibility" to listOf("accessible name", "cannot by itself prevent focus loss"),
    )
    rubricMarkers.forEach { (area, markers) ->
      val rubric = Files.readString(pack.declaredFiles.areas.getValue(area))
      markers.forEach { marker -> assertContains(rubric, marker) }
    }
  }

  @Test
  fun `install plan selects real go pack skills from manifests`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-go-install-plan-home-")
    val runtimeInstallRoot = home.resolve(".skill-bill/runtime")

    val plan = InstallOperations.planInstall(
      InstallPlanRequest(
        repoRoot = repoRoot,
        home = home,
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = setOf(InstallAgent.CODEX),
        ),
        platformPackSelection = PlatformPackSelection(
          mode = PlatformPackSelectionMode.SELECTED,
          selectedSlugs = setOf("go"),
        ),
        telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
        mcpRegistrationChoice = McpRegistrationChoice(
          register = false,
          runtimeMcpBin = runtimeInstallRoot.resolve("runtime-mcp/bin/runtime-mcp"),
        ),
        runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = runtimeInstallRoot),
        targetPaths = InstallationTargetPaths(
          skillsRoot = repoRoot.resolve("skills"),
          platformPacksRoot = repoRoot.resolve("platform-packs"),
          agentTargets = emptyList(),
        ),
        windowsSymlinkPreflight = WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
      ),
    )

    val skillsByName = plan.skills.associateBy { skill -> skill.name }

    assertContains(plan.discoveredPlatformPacks.map { pack -> pack.slug }, "go")
    assertEquals(listOf("go"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-go-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-go-code-check").kind)
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(
        InstallPlanSkillKind.PLATFORM_PACK,
        skillsByName.getValue("bill-go-code-review-$area").kind,
      )
    }
    assertFalse(skillsByName.containsKey("bill-ios-code-review"))
    assertFalse(skillsByName.containsKey("bill-kotlin-code-review"))
  }

  private fun copyDirectory(source: Path, target: Path) {
    Files.walk(source).use { stream ->
      stream.forEach { path ->
        val destination = target.resolve(source.relativize(path))
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination)
        } else {
          Files.createDirectories(destination.parent)
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }
}
