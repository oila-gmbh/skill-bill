package skillbill.infrastructure.fs

import skillbill.error.InvalidFallbackCapabilityError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSystemDeclaredReviewSpecialistsTest {
  @Test
  fun `no platform-packs directory yields no specialists`() {
    val repoRoot = Files.createTempDirectory("declared-specialists-empty")
    val specialists = FileSystemDeclaredReviewSpecialists().routedSpecialists(repoRoot, listOf("src/Main.kt"))
    assertEquals(emptyList(), specialists)
  }

  @Test
  fun `a pack directory without a manifest contributes no specialists`() {
    val repoRoot = Files.createTempDirectory("declared-specialists-absent")
    val packsRoot = Files.createDirectories(repoRoot.resolve("platform-packs"))
    Files.createDirectory(packsRoot.resolve("kotlin"))
    val specialists = FileSystemDeclaredReviewSpecialists().routedSpecialists(repoRoot, listOf("src/Main.kt"))
    assertEquals(emptyList(), specialists)
  }

  @Test
  fun `a malformed manifest loud-fails instead of being silently swallowed`() {
    val repoRoot = Files.createTempDirectory("declared-specialists-malformed")
    val packsRoot = Files.createDirectories(repoRoot.resolve("platform-packs"))
    val packDir = Files.createDirectory(packsRoot.resolve("broken"))
    Files.writeString(packDir.resolve("platform.yaml"), "areas: [unclosed")
    var threw = false
    try {
      FileSystemDeclaredReviewSpecialists().routedSpecialists(repoRoot, listOf("src/Main.kt"))
    } catch (_: Exception) {
      threw = true
    }
    assertTrue(threw, "a malformed manifest must loud-fail, not silently contribute zero specialists")
  }

  @Test
  fun `only packs the changed paths route to contribute specialists`() {
    val repoRoot = repoWithPacks()
    val specialists = FileSystemDeclaredReviewSpecialists()
      .routedSpecialists(repoRoot, listOf("runtime/src/main/kotlin/Runner.kt"))
    assertEquals(
      listOf("bill-kotlin-code-review-architecture", "bill-kotlin-code-review-security"),
      specialists.sorted(),
    )
  }

  @Test
  fun `a vendored pack that no changed path routes to is never required`() {
    val repoRoot = repoWithPacks()
    val specialists = FileSystemDeclaredReviewSpecialists()
      .routedSpecialists(repoRoot, listOf("runtime/src/main/kotlin/Runner.kt"))
    assertTrue(
      specialists.none { it.startsWith("bill-go-") },
      "a Kotlin-only delta must not demand the vendored Go pack's specialists: $specialists",
    )
  }

  @Test
  fun `no changed paths yields no specialists`() {
    val repoRoot = repoWithPacks()
    assertEquals(emptyList(), FileSystemDeclaredReviewSpecialists().routedSpecialists(repoRoot, emptyList()))
  }

  @Test
  fun `preflight reads changed content to break overlapping path signal ties`() {
    val repoRoot = Files.createTempDirectory("declared-specialists-content")
    val packsRoot = Files.createDirectories(repoRoot.resolve("platform-packs"))
    writePack(packsRoot, "kotlin", listOf(".kt", "*.kt"), listOf("architecture"))
    writePack(
      packsRoot,
      "kmp",
      listOf(".kt", "*.kt"),
      listOf("platform-correctness"),
      contentSignals = listOf("expect class"),
    )
    val source = repoRoot.resolve("src/commonMain/kotlin/Shared.kt")
    Files.createDirectories(source.parent)
    Files.writeString(source, "expect class Shared")

    val specialists = FileSystemDeclaredReviewSpecialists()
      .routedSpecialists(repoRoot, listOf("src/commonMain/kotlin/Shared.kt"))

    assertEquals(listOf("bill-kmp-code-review-platform-correctness"), specialists)
  }

  @Test
  fun `preflight rejects duplicate fallback owners before concrete routing`() {
    val repoRoot = Files.createTempDirectory("declared-specialists-duplicate-fallback")
    val packsRoot = Files.createDirectories(repoRoot.resolve("platform-packs"))
    writePack(packsRoot, "kotlin", listOf("*.kt"), listOf("architecture"))
    writePack(packsRoot, "first-neutral", emptyList(), listOf("architecture"), fallback = true)
    writePack(packsRoot, "second-neutral", emptyList(), listOf("security"), fallback = true)

    assertFailsWith<InvalidFallbackCapabilityError> {
      FileSystemDeclaredReviewSpecialists()
        .routedSpecialists(repoRoot, listOf("src/main/kotlin/Runner.kt"))
    }
  }

  private fun repoWithPacks(): Path {
    val repoRoot = Files.createTempDirectory("declared-specialists-routed")
    val packsRoot = Files.createDirectories(repoRoot.resolve("platform-packs"))
    writePack(packsRoot, "kotlin", listOf(".kt", "*.kt"), listOf("architecture", "security"))
    writePack(packsRoot, "go", listOf(".go", "*.go"), listOf("architecture", "api-contracts"))
    return repoRoot
  }

  private fun writePack(
    packsRoot: Path,
    slug: String,
    pathSignals: List<String>,
    areas: List<String>,
    contentSignals: List<String> = emptyList(),
    fallback: Boolean = false,
  ) {
    val packDir = Files.createDirectories(packsRoot.resolve(slug))
    Files.writeString(
      packDir.resolve("platform.yaml"),
      buildString {
        appendLine("platform: $slug")
        appendLine("contract_version: \"1.2\"")
        appendLine("display_name: $slug")
        appendLine("routing_signals:")
        appendLine("  strong:")
        pathSignals.forEach { appendLine("    - \"$it\"") }
        appendLine("  tie_breakers: []")
        appendLine("  path: [${pathSignals.joinToString(", ") { "\"$it\"" }}]")
        appendLine("  content: [${contentSignals.joinToString(", ") { "\"$it\"" }}]")
        if (fallback) appendLine("fallback_capabilities: [code-review]")
        appendLine("declared_code_review_areas:")
        areas.forEach { appendLine("  - $it") }
        appendLine("declared_files:")
        appendLine("  baseline: code-review/bill-$slug-code-review/content.md")
        appendLine("  areas:")
        areas.forEach { appendLine("    $it: code-review/bill-$slug-code-review-$it/content.md") }
      },
    )
  }
}
