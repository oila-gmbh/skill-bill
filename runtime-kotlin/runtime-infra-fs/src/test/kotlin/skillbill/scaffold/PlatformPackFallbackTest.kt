package skillbill.scaffold

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import skillbill.error.InvalidFallbackCapabilityError
import skillbill.error.InvalidManifestSchemaError
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import skillbill.scaffold.platformpack.validatePlatformPackFallbacks
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PlatformPackFallbackTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `loader exposes anchored fallback without custom field leakage`() {
    val packRoot = tempDir.resolve("custom-neutral").createDirectories()
    packRoot.resolve("platform.yaml").writeText(
      """
      platform: custom-neutral
      contract_version: "1.3"
      routing_signals:
        strong: [fallback-only]
      fallback_capabilities: [code-review]
      declared_code_review_areas: []
      declared_files:
        baseline: code-review/bill-custom-neutral-code-review/content.md
      """.trimIndent(),
    )
    Files.createDirectories(packRoot.resolve("code-review/bill-custom-neutral-code-review"))
    packRoot.resolve("code-review/bill-custom-neutral-code-review/content.md").writeText(
      """
      ---
      name: bill-custom-neutral-code-review
      description: Neutral review fallback used by the contract fixture.
      internal-for: bill-code-review
      ---

      # Neutral Review

      ## Classification Rules

      Review without stack assumptions.
      """.trimIndent(),
    )

    val loaded = discoverPlatformPackManifests(tempDir).single()

    assertEquals(setOf("code-review"), loaded.fallbackCapabilities)
    assertFalse("fallback_capabilities" in loaded.customFields)
  }

  @Test
  fun `duplicate fallback owners fail with typed contract error`() {
    assertFailsWith<InvalidFallbackCapabilityError> {
      validatePlatformPackFallbacks(listOf(pack("one", review = true), pack("two", review = true)))
    }
  }

  @Test
  fun `review fallback without baseline fails with typed contract error`() {
    assertFailsWith<InvalidFallbackCapabilityError> {
      validatePlatformPackFallbacks(listOf(pack("broken", review = false)))
    }
  }

  @Test
  fun `schema rejects unsupported fallback capability`() {
    val packRoot = tempDir.resolve("malformed").createDirectories()
    packRoot.resolve("platform.yaml").writeText(
      """
      platform: malformed
      contract_version: "1.3"
      routing_signals:
        strong: [fallback-only]
      fallback_capabilities: [quality-check]
      declared_code_review_areas: []
      """.trimIndent(),
    )

    assertFailsWith<InvalidManifestSchemaError> {
      discoverPlatformPackManifests(tempDir)
    }
  }

  private fun pack(slug: String, review: Boolean) = PlatformManifest(
    slug = slug,
    packRoot = tempDir.resolve(slug),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(listOf("fallback-only"), emptyList()),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles = DeclaredFiles(
      baseline = if (review) tempDir.resolve(slug).resolve("code-review/bill-$slug-code-review/content.md") else null,
      areas = emptyMap(),
    ),
    areaMetadata = emptyMap(),
    fallbackCapabilities = setOf("code-review"),
  )
}
