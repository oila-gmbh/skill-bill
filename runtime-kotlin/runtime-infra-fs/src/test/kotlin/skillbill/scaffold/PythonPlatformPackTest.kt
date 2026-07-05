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

class PythonPlatformPackTest {
  @Test
  fun `python platform pack declares expected manifest contract`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/python")

    val pack = loadPlatformPack(packRoot)

    assertEquals("python", pack.slug)
    assertEquals("Python", pack.displayName)
    assertEquals("1.1", pack.contractVersion)
    assertEquals(
      packRoot.resolve("code-review/bill-python-code-review/content.md"),
      pack.declaredFiles.baseline,
    )
    assertEquals(
      packRoot.resolve("quality-check/bill-python-code-check/content.md"),
      pack.declaredQualityCheckFile,
    )
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.areaMetadata.keys)

    listOf(
      "pyproject.toml",
      "requirements.txt",
      "setup.py",
      "setup.cfg",
      "Pipfile",
      "poetry.lock",
      "uv.lock",
      "tox.ini",
      "pytest.ini",
      ".py",
      "*.py",
    ).forEach { marker ->
      assertContains(pack.routingSignals.strong, marker)
    }
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("generated") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("vendored") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("Python appears only as tooling") })
  }

  @Test
  fun `python quality-check content must remain an internal bill-code-check sidecar`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-python-pack-malformed-")
    val packRoot = tempRoot.resolve("python")
    copyDirectory(repoRoot.resolve("platform-packs/python"), packRoot)

    val qualityCheckContent = packRoot.resolve("quality-check/bill-python-code-check/content.md")
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
  fun `python review and quality-check source content is authored`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/python")
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
      assertFalse(text.contains("TODO"), "Python pack source must not contain TODO placeholders: $contentFile")
      assertTrue(
        text.lines().size > 12,
        "Python pack source should contain substantive guidance: $contentFile",
      )
    }
  }

  @Test
  fun `install plan selects real python pack skills from manifests`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-python-install-plan-home-")
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
          selectedSlugs = setOf("python"),
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

    assertContains(plan.discoveredPlatformPacks.map { pack -> pack.slug }, "python")
    assertEquals(listOf("python"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-python-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-python-code-check").kind)
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(
        InstallPlanSkillKind.PLATFORM_PACK,
        skillsByName.getValue("bill-python-code-review-$area").kind,
      )
    }
    assertFalse(skillsByName.containsKey("bill-php-code-review"))
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
