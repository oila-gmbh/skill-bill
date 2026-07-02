@file:Suppress("MaxLineLength")

package skillbill.install

import skillbill.infrastructure.fs.FileSystemExternalAddonOverlay
import skillbill.install.model.ExternalAddonSource
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyStatus
import skillbill.install.runtime.InstallOperations
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallExternalAddonOverlayIntegrationTest : InstallApplyTestSupport() {

  @Test
  fun `overlay then staging inlines external addon content into installed skills cache`() {
    val fixture = setupIosFixture()
    val external = seedExternalSource(fixture, "acme", listOf("acme-review.md"))
    runOverlay(fixture, listOf(external))

    val plan = InstallOperations.planInstall(fixture.request(selectedPlatforms = setOf("ios")))
    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status, "apply failures: ${result.failures}")
    val iosSkill = result.skills.first { it.skillName == "bill-ios-code-review" }
    val staging = iosSkill.staging
    val stagingDir = staging.stagingDir
      ?: error("ios code-review skill was not staged")
    val acmeRendered = staging.renderedPointerFiles.firstOrNull { it.fileName.toString() == "acme-review.md" }
      ?: error(
        "acme-review.md must appear among the RENDERED pointer files in $stagingDir; " +
          "got: ${staging.renderedPointerFiles}",
      )
    assertTrue(
      Files.isRegularFile(acmeRendered),
      "external addon must be inlined as a rendered pointer file under the install cache",
    )
    assertTrue(
      acmeRendered.toAbsolutePath().normalize().startsWith(fixture.home.resolve(".skill-bill/installed-skills")),
      "rendered addon pointer must live under the install cache, not the source tree ($acmeRendered)",
    )
    assertTrue(Files.isRegularFile(stagingDir.resolve("offline-review.md")), "pack-owned addon must remain inlined")
    val renderedAddonBody = Files.readString(acmeRendered)
    assertContains(
      renderedAddonBody,
      "acme review body",
      "external addon content must appear in the RENDERED pointer file, not just the source .md",
    )
  }

  @Test
  fun `clean analog wipes addons then overlay reconstitutes before staging`() {
    val fixture = setupIosFixture()
    val external = seedExternalSource(fixture, "acme", listOf("acme-review.md"))
    runOverlay(fixture, listOf(external))

    val addonsDir = fixture.repoRoot.resolve("platform-packs/ios/addons")
    Files.walk(addonsDir).use { stream ->
      stream.filter { it != addonsDir && Files.isRegularFile(it) }
        .filter { path -> path.fileName.toString().startsWith("acme-") }
        .forEach(Files::deleteIfExists)
    }
    assertFalse(Files.exists(addonsDir.resolve("acme-review.md")))

    runOverlay(fixture, listOf(external))
    assertTrue(Files.isRegularFile(addonsDir.resolve("acme-review.md")), "overlay must reconstitute wiped addon files")

    val plan = InstallOperations.planInstall(fixture.request(selectedPlatforms = setOf("ios")))
    val result = InstallOperations.applyInstall(plan)
    assertEquals(InstallApplyStatus.SUCCESS, result.status, "apply failures: ${result.failures}")
  }

  @Test
  fun `zero config parity omits overlay and stages identically to baseline`() {
    val baseline = setupIosFixture()
    val baselinePlan = InstallOperations.planInstall(baseline.request(selectedPlatforms = setOf("ios")))
    val baselineResult = InstallOperations.applyInstall(baselinePlan)
    assertEquals(InstallApplyStatus.SUCCESS, baselineResult.status)

    val overlayed = setupIosFixture()
    runOverlay(overlayed, emptyList())
    val overlayPlan = InstallOperations.planInstall(overlayed.request(selectedPlatforms = setOf("ios")))
    val overlayResult = InstallOperations.applyInstall(overlayPlan)
    assertEquals(InstallApplyStatus.SUCCESS, overlayResult.status)

    val baselineContent = stagedSkillBody(baselineResult, "bill-ios-code-review")
    val overlayContent = stagedSkillBody(overlayResult, "bill-ios-code-review")
    assertEquals(baselineContent, overlayContent, "empty external config must be a byte-identical no-op")
  }

  private fun stagedSkillBody(result: skillbill.install.model.InstallApplyResult, name: String): String {
    val skill = result.skills.first { it.skillName == name }
    val stagingDir = skill.staging.stagingDir ?: error("$name was not staged")
    return Files.readString(stagingDir.resolve("SKILL.md"))
  }

  private fun setupIosFixture(): ApplyFixture {
    val fixture = setupApplyFixture()
    val repoRoot = fixture.repoRoot
    val packRoot = repoRoot.resolve("platform-packs/ios")
    val codeReviewDir = packRoot.resolve("code-review/bill-ios-code-review")
    Files.createDirectories(codeReviewDir)
    Files.writeString(codeReviewDir.resolve("content.md"), content("bill-ios-code-review"))
    val addonsDir = Files.createDirectories(packRoot.resolve("addons"))
    Files.writeString(addonsDir.resolve("offline-review.md"), "# offline review body\n")
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: "ios"
      contract_version: "1.1"
      routing_signals:
        strong:
          - ".swift"
        tie_breakers: []
      declared_code_review_areas: []
      declared_files:
        baseline: "code-review/bill-ios-code-review/content.md"
        areas: {}
      area_metadata: {}
      display_name: "iOS"
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: offline
            entrypoint: offline-review.md
      pointers:
        code-review/bill-ios-code-review:
          - name: offline-review.md
            target: platform-packs/ios/addons/offline-review.md
      """.trimIndent() + "\n",
    )
    return fixture
  }

  private fun seedExternalSource(fixture: ApplyFixture, slug: String, mdFiles: List<String>): ExternalAddonSource {
    val sourceDir = Files.createDirectories(fixture.home.resolve("private/ios-$slug"))
    mdFiles.forEach { name ->
      Files.writeString(sourceDir.resolve(name), "# $slug review body\n")
    }
    val entrypoint = mdFiles.first()
    Files.writeString(
      sourceDir.resolve("addon-manifest.yaml"),
      """
      addon_usage:
        code-review/bill-ios-code-review:
          - slug: $slug
            entrypoint: $entrypoint
      pointers:
        code-review/bill-ios-code-review:
          - name: $entrypoint
            target: $entrypoint
      """.trimIndent() + "\n",
    )
    return ExternalAddonSource(sourceDir, "ios")
  }

  private fun runOverlay(fixture: ApplyFixture, sources: List<ExternalAddonSource>) {
    val port: ExternalAddonOverlayPort = FileSystemExternalAddonOverlay()
    port.applyOverlay(
      ExternalAddonOverlayRequest(
        platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
        sources = sources,
      ),
    )
  }

  private fun assertContains(actual: String, expected: String, message: String? = null) {
    assertTrue(
      actual.contains(expected),
      (message ?: "Expected staged content to contain '$expected'.") + " Got: $actual",
    )
  }

  private fun ApplyFixture.request(selectedPlatforms: Set<String>): skillbill.install.model.InstallPlanRequest =
    request(
      selectedPlatforms = selectedPlatforms,
      agents = setOf(InstallAgent.CODEX),
    )
}
