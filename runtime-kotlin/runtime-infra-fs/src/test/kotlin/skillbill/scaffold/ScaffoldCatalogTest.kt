package skillbill.scaffold

import skillbill.scaffold.catalog.ScaffoldCatalog
import skillbill.scaffold.runtime.scaffold
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaffoldCatalogTest {
  @Test
  fun `baseline review catalog projects manifest composition edges`() {
    val packsRoot = Files.createTempDirectory("skillbill-scaffold-catalog-")
    writeManifest(
      packsRoot = packsRoot,
      slug = "kotlin",
      body = manifest(slug = "kotlin"),
    )
    writeManifest(
      packsRoot = packsRoot,
      slug = "kmp",
      body = manifest(
        slug = "kmp",
        composition = """
          code_review_composition:
            baseline_layers:
              - platform: kotlin
                skill: bill-kotlin-code-review
                scope: same-review-scope
                required: true
                mode: kmp-baseline
        """.trimIndent(),
      ),
    )
    writeManifest(
      packsRoot = packsRoot,
      slug = "docs",
      body = """
        platform: docs
        contract_version: "1.2"
        display_name: Docs
        routing_signals:
          strong:
            - "docs/"
          tie_breakers: []
        declared_code_review_areas: []
        area_metadata: {}
      """.trimIndent(),
    )

    val catalog = ScaffoldCatalog.discoverBaselineReviewCatalog(packsRoot)

    assertEquals(listOf("kmp", "kotlin"), catalog.packs.map { it.platform })
    assertEquals(
      listOf(BaselineEdge("kmp", "kotlin", "bill-kotlin-code-review")),
      catalog.compositionEdges.map { edge ->
        BaselineEdge(edge.sourcePlatform, edge.targetPlatform, edge.targetSkill)
      },
    )
  }

  private data class BaselineEdge(
    val sourcePlatform: String,
    val targetPlatform: String,
    val targetSkill: String,
  )

  private fun writeManifest(packsRoot: java.nio.file.Path, slug: String, body: String) {
    val packRoot = packsRoot.resolve(slug)
    Files.createDirectories(packRoot)
    Files.writeString(packRoot.resolve("platform.yaml"), body)
  }

  private fun manifest(slug: String, composition: String = ""): String = buildString {
    appendLine("platform: $slug")
    appendLine("contract_version: \"1.2\"")
    appendLine("display_name: ${slug.replaceFirstChar { it.uppercase() }}")
    appendLine("routing_signals:")
    appendLine("  strong:")
    appendLine("    - \".$slug\"")
    appendLine("  tie_breakers: []")
    appendLine("declared_code_review_areas: []")
    appendLine("declared_files:")
    appendLine("  baseline: code-review/bill-$slug-code-review/content.md")
    appendLine("  areas: {}")
    appendLine("area_metadata: {}")
    if (composition.isNotBlank()) {
      appendLine(composition)
    }
  }
}
