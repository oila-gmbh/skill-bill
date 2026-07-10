package skillbill.scaffold

import skillbill.error.InvalidManifestSchemaError
import skillbill.error.MissingContentFileError
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

class RustPlatformPackTest {
  @Test
  fun `rust platform pack declares expected manifest contract`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/rust")
    val pack = loadPlatformPack(packRoot)

    assertEquals("rust", pack.slug)
    assertEquals("Rust", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(packRoot.resolve("code-review/bill-rust-code-review/content.md"), pack.declaredFiles.baseline)
    assertEquals(packRoot.resolve("quality-check/bill-rust-code-check/content.md"), pack.declaredQualityCheckFile)
    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.areaMetadata.keys)

    listOf(
      "Cargo.toml",
      "Cargo.lock",
      ".rs",
      "*.rs",
      "build.rs",
      "rust-toolchain.toml",
      "rustfmt.toml",
      "clippy.toml",
      "deny.toml",
      ".cargo/config.toml",
    ).forEach { marker -> assertContains(pack.routingSignals.strong, marker) }
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("FFI bindings") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("wasm build artifacts") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("target/") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("vendored crates") })
  }

  @Test
  fun `rust quality-check content must remain an internal bill-code-check sidecar`() {
    val packRoot = copiedRustPack("quality-classification")
    val content = packRoot.resolve("quality-check/bill-rust-code-check/content.md")
    Files.writeString(content, Files.readString(content).replace("internal-for: bill-code-check\n", ""))

    val error = assertFailsWith<InvalidManifestSchemaError> { loadPlatformPack(packRoot) }
    assertContains(error.message.orEmpty(), "internal-for: bill-code-check")
    assertContains(error.message.orEmpty(), "quality-check")
  }

  @Test
  fun `rust manifest rejects an incoherent declared specialist path`() {
    val packRoot = copiedRustPack("specialist-path")
    val manifest = packRoot.resolve("platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        "code-review/bill-rust-code-review-security/content.md",
        "code-review/bill-rust-code-review-missing/content.md",
      ),
    )

    assertFailsWith<MissingContentFileError> { loadPlatformPack(packRoot) }
  }

  @Test
  fun `rust review and quality-check source content is authored`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/rust")
    val contentFiles = Files.walk(packRoot).use { stream ->
      stream.filter { it.fileName.toString() == "content.md" }.sorted().toList()
    }

    assertEquals(12, contentFiles.size)
    contentFiles.forEach { contentFile ->
      val text = Files.readString(contentFile)
      assertTrue(text.contains("internal-for:"), "Missing internal classification in $contentFile")
      assertFalse(text.contains("TODO"), "Rust pack source must not contain placeholders: $contentFile")
      assertTrue(text.lines().size > 12, "Rust pack source should contain substantive guidance: $contentFile")
      assertFalse(text.contains("bill-go-"), "Rust source contains a copied Go identifier: $contentFile")
      assertFalse(text.contains("bill-python-"), "Rust source contains a copied Python identifier: $contentFile")
    }
  }

  @Test
  fun `rust native agent bundle declares every specialist`() {
    val bundle = Files.readString(
      repoRootFromTest().resolve("platform-packs/rust/code-review/bill-rust-code-review/native-agents/agents.yaml"),
    )

    assertContains(bundle, "contract_version: \"0.1\"")
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertContains(bundle, "name: bill-rust-code-review-$area")
    }
    assertEquals(10, Regex("compose: governed-content").findAll(bundle).count())
  }

  @Test
  fun `install plan selects real rust pack skills from manifests`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-rust-install-plan-home-")
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
          selectedSlugs = setOf("rust"),
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

    val skillsByName = plan.skills.associateBy { it.name }
    assertContains(plan.discoveredPlatformPacks.map { it.slug }, "rust")
    assertEquals(listOf("rust"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-rust-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-rust-code-check").kind)
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-rust-code-review-$area").kind)
    }
    assertFalse(skillsByName.containsKey("bill-go-code-review"))
    assertFalse(skillsByName.containsKey("bill-python-code-review"))
  }

  private fun copiedRustPack(suffix: String): Path {
    val source = repoRootFromTest().resolve("platform-packs/rust")
    val target = Files.createTempDirectory("skillbill-rust-pack-$suffix-").resolve("rust")
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
    return target
  }
}
