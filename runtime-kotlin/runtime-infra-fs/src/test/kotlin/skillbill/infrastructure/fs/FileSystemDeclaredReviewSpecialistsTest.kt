package skillbill.infrastructure.fs

import skillbill.error.InvalidFallbackCapabilityError
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSystemDeclaredReviewSpecialistsTest {
  @Test
  fun `installed catalog routes fallback specialists when reviewed repository has no pack tree`() {
    val installedRoot = Files.createTempDirectory("installed-review-catalog")
    val packsRoot = Files.createDirectories(installedRoot.resolve("platform-packs"))
    writePack(packsRoot, "neutral-review", emptyList(), listOf("architecture", "security"))
    val specialists = FileSystemDeclaredReviewSpecialists(installedCatalog(packsRoot))
      .routedSpecialists(changed("docs/guide.md"))

    assertEquals(
      listOf(
        "bill-neutral-review-code-review-architecture",
        "bill-neutral-review-code-review-security",
      ),
      specialists,
    )
  }

  @Test
  fun `no installed packs yields no specialists`() {
    val specialists = FileSystemDeclaredReviewSpecialists().routedSpecialists(changed("src/Main.kt"))
    assertEquals(emptyList(), specialists)
  }

  @Test
  fun `a pack directory without a manifest contributes no specialists`() {
    val packsRoot = Files.createTempDirectory("declared-specialists-absent")
    Files.createDirectory(packsRoot.resolve("kotlin"))
    var threw = false
    try {
      FileSystemDeclaredReviewSpecialists(installedCatalog(packsRoot)).routedSpecialists(changed("src/Main.kt"))
    } catch (_: Exception) {
      threw = true
    }
    assertTrue(threw, "an installed pack directory without a manifest must loud-fail")
  }

  @Test
  fun `a malformed manifest loud-fails instead of being silently swallowed`() {
    val packsRoot = Files.createTempDirectory("declared-specialists-malformed")
    val packDir = Files.createDirectory(packsRoot.resolve("broken"))
    Files.writeString(packDir.resolve("platform.yaml"), "areas: [unclosed")
    var threw = false
    try {
      FileSystemDeclaredReviewSpecialists(installedCatalog(packsRoot)).routedSpecialists(changed("src/Main.kt"))
    } catch (_: Exception) {
      threw = true
    }
    assertTrue(threw, "a malformed manifest must loud-fail, not silently contribute zero specialists")
  }

  @Test
  fun `only packs the changed paths route to contribute specialists`() {
    val specialists = FileSystemDeclaredReviewSpecialists(installedCatalog(packsWithKotlinAndGo()))
      .routedSpecialists(changed("runtime/src/main/kotlin/Runner.kt"))
    assertEquals(
      listOf("bill-kotlin-code-review-architecture", "bill-kotlin-code-review-security"),
      specialists.sorted(),
    )
  }

  @Test
  fun `preflight excludes an unconditioned non-required specialist that launch does not own`() {
    val packsRoot = Files.createTempDirectory("declared-specialists-unconditioned")
    writePack(
      packsRoot,
      "kotlin",
      listOf(".kt", "*.kt"),
      listOf("architecture"),
      options = PackOptions(includeLaneConditions = false),
    )

    val specialists = FileSystemDeclaredReviewSpecialists(installedCatalog(packsRoot))
      .routedSpecialists(changed("runtime/src/main/kotlin/Runner.kt"))

    assertEquals(emptyList(), specialists)
  }

  @Test
  fun `a vendored pack that no changed path routes to is never required`() {
    val specialists = FileSystemDeclaredReviewSpecialists(installedCatalog(packsWithKotlinAndGo()))
      .routedSpecialists(changed("runtime/src/main/kotlin/Runner.kt"))
    assertTrue(
      specialists.none { it.startsWith("bill-go-") },
      "a Kotlin-only delta must not demand the vendored Go pack's specialists: $specialists",
    )
  }

  @Test
  fun `no changed paths yields no specialists`() {
    val catalog = installedCatalog(packsWithKotlinAndGo())
    assertEquals(emptyList(), FileSystemDeclaredReviewSpecialists(catalog).routedSpecialists(emptyList()))
  }

  @Test
  fun `preflight reads changed content to break overlapping path signal ties`() {
    val packsRoot = Files.createTempDirectory("declared-specialists-content")
    writePack(packsRoot, "kotlin", listOf(".kt", "*.kt"), listOf("architecture"))
    writePack(
      packsRoot,
      "kmp",
      listOf(".kt", "*.kt"),
      listOf("platform-correctness"),
      options = PackOptions(contentSignals = listOf("expect class")),
    )
    val specialists = FileSystemDeclaredReviewSpecialists(installedCatalog(packsRoot))
      .routedSpecialists(changed("src/commonMain/kotlin/Shared.kt", "expect class Shared"))

    assertEquals(listOf("bill-kmp-code-review-platform-correctness"), specialists)
  }

  @Test
  fun `preflight rejects duplicate fallback owners before concrete routing`() {
    val packsRoot = Files.createTempDirectory("declared-specialists-duplicate-fallback")
    writePack(packsRoot, "kotlin", listOf("*.kt"), listOf("architecture"))
    writePack(packsRoot, "first-neutral", emptyList(), listOf("architecture"))
    writePack(packsRoot, "second-neutral", emptyList(), listOf("security"))

    assertFailsWith<InvalidFallbackCapabilityError> {
      FileSystemDeclaredReviewSpecialists(installedCatalog(packsRoot))
        .routedSpecialists(changed("src/main/kotlin/Runner.kt"))
    }
  }

  private fun packsWithKotlinAndGo(): Path {
    val packsRoot = Files.createTempDirectory("declared-specialists-routed")
    writePack(packsRoot, "kotlin", listOf(".kt", "*.kt"), listOf("architecture", "security"))
    writePack(packsRoot, "go", listOf(".go", "*.go"), listOf("architecture", "api-contracts"))
    return packsRoot
  }

  // Mirrors FileSystemInstalledPlatformPackCatalog: the installed selection is where pack discovery
  // and its loud-fail validation now live, so these cases exercise it through the same seam.
  private fun installedCatalog(packsRoot: Path) =
    InstalledPlatformPackCatalogPort { discoverPlatformPackManifests(packsRoot) }

  private fun changed(path: String, content: String = "") = listOf(ReviewRoutingChangedFile(path, content))

  private data class PackOptions(
    val contentSignals: List<String> = emptyList(),
    val includeLaneConditions: Boolean = true,
  )

  private fun writePack(
    packsRoot: Path,
    slug: String,
    pathSignals: List<String>,
    areas: List<String>,
    options: PackOptions = PackOptions(),
  ) {
    val packDir = Files.createDirectories(packsRoot.resolve(slug))
    Files.writeString(
      packDir.resolve("platform.yaml"),
      buildString {
        val fallback = pathSignals.isEmpty()
        val strongSignals = pathSignals.ifEmpty { listOf("manifest-declared code-review fallback") }
        appendLine("platform: $slug")
        appendLine("contract_version: \"1.4\"")
        appendLine("display_name: $slug")
        appendLine("routing_signals:")
        appendLine("  strong:")
        strongSignals.forEach { appendLine("    - \"$it\"") }
        appendLine("  tie_breakers: []")
        if (pathSignals.isNotEmpty()) {
          appendLine("  path: [${pathSignals.joinToString(", ") { "\"$it\"" }}]")
        }
        appendLine("  content: [${options.contentSignals.joinToString(", ") { "\"$it\"" }}]")
        if (fallback) appendLine("fallback_capabilities: [code-review]")
        appendLine("declared_code_review_areas:")
        areas.forEach { appendLine("  - $it") }
        appendLine("declared_files:")
        appendLine("  baseline: code-review/bill-$slug-code-review/content.md")
        appendLine("  areas:")
        areas.forEach { appendLine("    $it: code-review/bill-$slug-code-review-$it/content.md") }
        if (options.includeLaneConditions) {
          appendLine("lane_conditions:")
          areas.forEach {
            appendLine("  $it:")
            appendLine("    required: true")
          }
        }
      },
    )
  }
}
