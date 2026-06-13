package skillbill.scaffold

import skillbill.error.InvalidManifestSchemaError
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformPackCompositionTest {
  @Test
  fun `buildPack parses code review composition into typed manifest values`() {
    val pack = loadPlatformManifest(
      newTempPackRoot(
        "kmp",
        manifest(
          slug = "kmp",
          composition = kotlinBaselineComposition(),
        ),
      ),
    )

    val composition = assertNotNull(pack.codeReviewComposition)
    val layer = composition.baselineLayers.single()
    assertEquals("kotlin", layer.platform)
    assertEquals("bill-kotlin-code-review", layer.skill)
    assertEquals(CodeReviewCompositionScope.SameReviewScope, layer.scope)
    assertEquals(true, layer.required)
    assertEquals(CodeReviewCompositionMode.KmpBaseline, layer.mode)
  }

  @Test
  fun `pack without code review composition still loads`() {
    val pack = loadPlatformManifest(newTempPackRoot("kotlin", manifest(slug = "kotlin")))

    assertNull(pack.codeReviewComposition)
  }

  @Test
  fun `shipped KMP manifest declares valid Kotlin baseline composition`() {
    val repoRoot = repoRootFromTest()
    val packs = discoverPlatformPackManifests(repoRoot.resolve("platform-packs"))
    val kmp = packs.single { it.slug == "kmp" }
    val kotlin = packs.single { it.slug == "kotlin" }

    assertNull(kotlin.codeReviewComposition)
    val layer = assertNotNull(kmp.codeReviewComposition).baselineLayers.single()
    assertEquals("kotlin", layer.platform)
    assertEquals("bill-kotlin-code-review", layer.skill)
    assertEquals(CodeReviewCompositionScope.SameReviewScope, layer.scope)
    assertEquals(true, layer.required)
    assertEquals(CodeReviewCompositionMode.KmpBaseline, layer.mode)
  }

  @Test
  fun `schema rejects unknown nested composition fields`() {
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPlatformManifest(
        newTempPackRoot(
          "kmp",
          manifest(
            slug = "kmp",
            composition = """
              code_review_composition:
                baseline_layers:
                  - platform: kotlin
                    skill: bill-kotlin-code-review
                    scope: same-review-scope
                    required: true
                    mode: kmp-baseline
                    extra: nope
            """.trimIndent(),
          ),
        ),
      )
    }

    val message = error.message.orEmpty()
    assertContains(message, "code_review_composition")
    assertContains(message, "extra")
  }

  @Test
  fun `schema rejects missing explicit required on baseline layer`() {
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPlatformManifest(
        newTempPackRoot(
          "kmp",
          manifest(
            slug = "kmp",
            composition = """
              code_review_composition:
                baseline_layers:
                  - platform: kotlin
                    skill: bill-kotlin-code-review
                    scope: same-review-scope
                    mode: kmp-baseline
            """.trimIndent(),
          ),
        ),
      )
    }

    val message = error.message.orEmpty()
    assertContains(message, "required")
    assertContains(message, "code_review_composition")
  }

  @Test
  fun `schema rejects unsupported baseline layer scope`() {
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPlatformManifest(
        newTempPackRoot(
          "kmp",
          manifest(
            slug = "kmp",
            composition = """
              code_review_composition:
                baseline_layers:
                  - platform: kotlin
                    skill: bill-kotlin-code-review
                    scope: different-scope
                    required: true
                    mode: kmp-baseline
            """.trimIndent(),
          ),
        ),
      )
    }

    val message = error.message.orEmpty()
    assertContains(message, "scope")
    assertContains(message, "different-scope")
  }

  @Test
  fun `schema rejects unsupported baseline layer mode`() {
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPlatformManifest(
        newTempPackRoot(
          "kmp",
          manifest(
            slug = "kmp",
            composition = """
              code_review_composition:
                baseline_layers:
                  - platform: kotlin
                    skill: bill-kotlin-code-review
                    scope: same-review-scope
                    required: true
                    mode: mystery-mode
            """.trimIndent(),
          ),
        ),
      )
    }

    val message = error.message.orEmpty()
    assertContains(message, "mode")
    assertContains(message, "mystery-mode")
  }

  @Test
  fun `loadPlatformPack validates composition references through single pack seam`() {
    val packsRoot = newTempPacksRoot(
      "kmp" to manifest(slug = "kmp", composition = kotlinBaselineComposition()),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> { loadPlatformPack(packsRoot.resolve("kmp")) }

    val message = error.message.orEmpty()
    assertContains(message, "missing platform pack")
    assertContains(message, "kotlin")
  }

  @Test
  fun `composition rejects missing referenced platform pack`() {
    val packsRoot = newTempPacksRoot(
      "kmp" to manifest(slug = "kmp", composition = kotlinBaselineComposition()),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> { discoverPlatformPackManifests(packsRoot) }

    val message = error.message.orEmpty()
    assertContains(message, "missing platform pack")
    assertContains(message, "kotlin")
  }

  @Test
  fun `composition rejects missing referenced code review skill`() {
    val packsRoot = newTempPacksRoot(
      "kotlin" to manifest(slug = "kotlin"),
      "kmp" to manifest(
        slug = "kmp",
        composition = """
          code_review_composition:
            baseline_layers:
              - platform: kotlin
                skill: bill-kotlin-code-review-missing
                scope: same-review-scope
                required: true
                mode: kmp-baseline
        """.trimIndent(),
      ),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> { discoverPlatformPackManifests(packsRoot) }

    val message = error.message.orEmpty()
    assertContains(message, "missing code-review skill")
    assertContains(message, "bill-kotlin-code-review-missing")
  }

  @Test
  fun `composition rejects self reference`() {
    val packsRoot = newTempPacksRoot(
      "kmp" to manifest(
        slug = "kmp",
        composition = """
          code_review_composition:
            baseline_layers:
              - platform: kmp
                skill: bill-kmp-code-review
                scope: same-review-scope
                required: true
                mode: kmp-baseline
        """.trimIndent(),
      ),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> { discoverPlatformPackManifests(packsRoot) }

    val message = error.message.orEmpty()
    assertContains(message, "self-references")
    assertContains(message, "kmp/bill-kmp-code-review")
  }

  @Test
  fun `composition rejects duplicate baseline layers by target`() {
    val packsRoot = newTempPacksRoot(
      "kotlin" to manifest(slug = "kotlin"),
      "kmp" to manifest(
        slug = "kmp",
        composition = """
          code_review_composition:
            baseline_layers:
              - platform: kotlin
                skill: bill-kotlin-code-review
                scope: same-review-scope
                required: true
                mode: kmp-baseline
              - platform: kotlin
                skill: bill-kotlin-code-review
                scope: same-review-scope
                required: false
                mode: kmp-baseline
        """.trimIndent(),
      ),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> { discoverPlatformPackManifests(packsRoot) }

    val message = error.message.orEmpty()
    assertContains(message, "duplicate")
    assertContains(message, "kotlin/bill-kotlin-code-review")
  }

  @Test
  fun `composition rejects cycles`() {
    val packsRoot = newTempPacksRoot(
      "kmp" to manifest(
        slug = "kmp",
        baselinePath = "code-review/bill-kotlin-code-review/content.md",
        composition = kotlinBaselineComposition(),
      ),
      "kotlin" to manifest(
        slug = "kotlin",
        baselinePath = "code-review/bill-kotlin-code-review/content.md",
        composition = """
          code_review_composition:
            baseline_layers:
              - platform: kmp
                skill: bill-kotlin-code-review
                scope: same-review-scope
                required: true
                mode: kmp-baseline
        """.trimIndent(),
      ),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> { discoverPlatformPackManifests(packsRoot) }

    val message = error.message.orEmpty()
    assertContains(message, "composition cycle")
    assertContains(message, "kmp")
    assertContains(message, "kotlin")
  }

  @Test
  fun `composition rejects kmp baseline mode for non Kotlin baseline skill`() {
    val packsRoot = newTempPacksRoot(
      "kotlin" to manifest(
        slug = "kotlin",
        areas = mapOf("testing" to "code-review/bill-kotlin-code-review-testing/content.md"),
      ),
      "kmp" to manifest(
        slug = "kmp",
        composition = """
          code_review_composition:
            baseline_layers:
              - platform: kotlin
                skill: bill-kotlin-code-review-testing
                scope: same-review-scope
                required: true
                mode: kmp-baseline
        """.trimIndent(),
      ),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> { discoverPlatformPackManifests(packsRoot) }

    val message = error.message.orEmpty()
    assertContains(message, "unsupported referenced skill")
    assertContains(message, "bill-kotlin-code-review")
  }

  @Test
  fun `composition rejects kmp baseline mode for non Kotlin platform`() {
    val packsRoot = newTempPacksRoot(
      "other" to manifest(slug = "other", baselinePath = "code-review/bill-kotlin-code-review/content.md"),
      "kmp" to manifest(
        slug = "kmp",
        composition = """
          code_review_composition:
            baseline_layers:
              - platform: other
                skill: bill-kotlin-code-review
                scope: same-review-scope
                required: true
                mode: kmp-baseline
        """.trimIndent(),
      ),
    )

    val error = assertFailsWith<InvalidManifestSchemaError> { discoverPlatformPackManifests(packsRoot) }

    val message = error.message.orEmpty()
    assertContains(message, "unsupported referenced skill")
    assertContains(message, "kotlin/bill-kotlin-code-review")
  }

  private fun kotlinBaselineComposition(): String = """
    code_review_composition:
      baseline_layers:
        - platform: kotlin
          skill: bill-kotlin-code-review
          scope: same-review-scope
          required: true
          mode: kmp-baseline
  """.trimIndent()

  private fun manifest(
    slug: String,
    baselinePath: String = "code-review/bill-$slug-code-review/content.md",
    areas: Map<String, String> = emptyMap(),
    composition: String = "",
  ): String = buildString {
    appendLine("platform: $slug")
    appendLine("contract_version: \"1.1\"")
    appendLine("routing_signals:")
    appendLine("  strong: [\".$slug\"]")
    appendLine("declared_code_review_areas:")
    if (areas.isEmpty()) {
      appendLine("  []")
    } else {
      areas.keys.forEach { area -> appendLine("  - $area") }
    }
    appendLine("declared_files:")
    appendLine("  baseline: $baselinePath")
    if (areas.isNotEmpty()) {
      appendLine("  areas:")
      areas.forEach { (area, path) -> appendLine("    $area: $path") }
    }
    if (composition.isNotBlank()) {
      appendLine(composition)
    }
  }

  private fun newTempPacksRoot(vararg manifests: Pair<String, String>): Path {
    val root = Files.createTempDirectory("skillbill-platform-pack-composition-root-")
    manifests.forEach { (slug, manifest) ->
      val packRoot = root.resolve(slug)
      Files.createDirectories(packRoot)
      Files.writeString(packRoot.resolve("platform.yaml"), manifest)
    }
    return root
  }

  private fun newTempPackRoot(slug: String, manifest: String): Path {
    val packsRoot = newTempPacksRoot(slug to manifest)
    return packsRoot.resolve(slug)
  }
}
