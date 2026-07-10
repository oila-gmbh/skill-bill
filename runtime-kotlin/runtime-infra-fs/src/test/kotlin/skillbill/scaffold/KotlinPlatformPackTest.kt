package skillbill.scaffold

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
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val KOTLIN_CODE_REVIEW_AREAS = setOf(
  "api-contracts",
  "architecture",
  "performance",
  "persistence",
  "platform-correctness",
  "reliability",
  "security",
  "testing",
)

class KotlinPlatformPackTest {
  @Test
  fun `kotlin platform pack declares elevated manifest contract`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/kotlin")

    val pack = loadPlatformPack(packRoot)

    assertEquals("kotlin", pack.slug)
    assertEquals("Kotlin", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(
      packRoot.resolve("code-review/bill-kotlin-code-review/content.md"),
      pack.declaredFiles.baseline,
    )
    assertEquals(
      packRoot.resolve("quality-check/bill-kotlin-code-check/content.md"),
      pack.declaredQualityCheckFile,
    )
    assertEquals(KOTLIN_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
    assertEquals(KOTLIN_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(KOTLIN_CODE_REVIEW_AREAS, pack.areaMetadata.keys)

    listOf(
      ".kt",
      "*.kt",
      ".kts",
      "*.kts",
      "build.gradle",
      "build.gradle.kts",
      "settings.gradle.kts",
      "gradle/libs.versions.toml",
      "detekt.yml",
      "kotlin/",
    ).forEach { marker ->
      assertContains(pack.routingSignals.strong, marker)
    }
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("Kotlin/JVM") && it.contains("dominate") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("adjacent KMP") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("generated and vendored") })
    assertEquals(8, pack.areaMetadata.values.toSet().size)
    pack.areaMetadata.values.forEach { focus -> assertContains(focus, "Kotlin") }
  }

  @Test
  fun `kotlin review and quality-check source content is authored and substantive`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/kotlin")
    val contentFiles = Files.walk(packRoot).use { stream ->
      stream
        .filter { path -> path.fileName.toString() == "content.md" }
        .sorted()
        .toList()
    }

    assertEquals(10, contentFiles.size)
    contentFiles.forEach { contentFile ->
      val text = Files.readString(contentFile)
      assertTrue(text.contains("internal-for:"), "Missing internal classification in $contentFile")
      assertFalse(text.contains("TODO"), "Kotlin pack source must not contain TODO placeholders: $contentFile")
      assertTrue(text.lines().size > 20, "Kotlin pack source should contain substantive guidance: $contentFile")
    }

    val reviewRoot = packRoot.resolve("code-review")
    listOf(
      "CancellationException",
      "SupervisorJob",
      "runBlocking",
      "runTest",
      "explicitNulls",
      "newSuspendedTransaction",
    ).forEach { requiredRule ->
      assertTrue(
        Files.walk(reviewRoot).use { paths ->
          paths.filter { file -> Files.isRegularFile(file) }.anyMatch { file ->
            Files.readString(file).contains(requiredRule)
          }
        },
        "Kotlin specialists must contain $requiredRule guidance",
      )
    }
  }

  @Test
  fun `install plan selects kotlin baseline checker and all specialists`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-kotlin-install-plan-home-")
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
          selectedSlugs = setOf("kotlin"),
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

    assertContains(plan.discoveredPlatformPacks.map { pack -> pack.slug }, "kotlin")
    assertEquals(listOf("kotlin"), plan.selectedPlatformSlugs)
    assertEquals(
      InstallPlanSkillKind.PLATFORM_PACK,
      skillsByName.getValue("bill-kotlin-code-review").kind,
    )
    assertEquals(
      InstallPlanSkillKind.PLATFORM_PACK,
      skillsByName.getValue("bill-kotlin-code-check").kind,
    )
    KOTLIN_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(
        InstallPlanSkillKind.PLATFORM_PACK,
        skillsByName.getValue("bill-kotlin-code-review-$area").kind,
      )
    }
  }
}
