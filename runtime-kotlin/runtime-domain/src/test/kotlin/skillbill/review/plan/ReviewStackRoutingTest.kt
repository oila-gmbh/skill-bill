package skillbill.review.plan

import org.junit.jupiter.api.Test
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewStackRoutingTest {
  @Test
  fun `content without path ownership does not select a concrete pack`() {
    val result = ReviewStackRouting.route(
      listOf(
        pack("go", path = listOf("*.go"), content = listOf("package ", "json")),
        pack("kotlin", path = listOf("*.kt"), content = listOf("kotlin")),
      ),
      listOf(
        ReviewRoutingChangedFile(
          "docs/review-routing.md",
          "A package may expose a JSON contract without containing Go source.",
        ),
      ),
    )

    assertTrue(result.routedSlugs.isEmpty())
    assertTrue(result.ownedPathsBySlug.isEmpty())
  }

  @Test
  fun `content only breaks ties between positive path owners`() {
    val result = ReviewStackRouting.route(
      listOf(
        pack("first", path = listOf("src/*"), content = listOf("first marker")),
        pack("second", path = listOf("src/*"), content = listOf("second marker")),
        pack("content-only", path = listOf("*.other"), content = listOf("second marker")),
      ),
      listOf(ReviewRoutingChangedFile("src/shared.txt", "second marker")),
    )

    assertEquals(setOf("second"), result.routedSlugs)
    assertEquals(mapOf("second" to setOf("src/shared.txt")), result.ownedPathsBySlug)
  }

  @Test
  fun `baseline pack wins a shared positive path tie with its composed root`() {
    val kotlin = pack("kotlin", path = listOf("*.kt"), content = emptyList())
    val kmp = pack(
      "kmp",
      path = listOf("*.kt"),
      content = emptyList(),
      baselinePlatform = "kotlin",
    )

    val result = ReviewStackRouting.route(
      listOf(kmp, kotlin),
      listOf(ReviewRoutingChangedFile("src/main/kotlin/Example.kt", "class Example")),
    )

    assertEquals(setOf("kotlin"), result.routedSlugs)
  }

  private fun pack(slug: String, path: List<String>, content: List<String>, baselinePlatform: String? = null) =
    PlatformManifest(
      slug = slug,
      packRoot = Path.of("platform-packs", slug),
      contractVersion = "1.2",
      routingSignals = RoutingSignals(strong = path, tieBreakers = emptyList(), path = path, content = content),
      declaredCodeReviewAreas = emptyList(),
      declaredFiles = DeclaredFiles(
        baseline = Path.of("platform-packs", slug, "code-review", "bill-$slug-code-review", "content.md"),
        areas = emptyMap(),
      ),
      areaMetadata = emptyMap(),
      codeReviewComposition = baselinePlatform?.let {
        CodeReviewComposition(
          listOf(
            CodeReviewBaselineLayer(
              platform = it,
              skill = "bill-$it-code-review",
              scope = CodeReviewCompositionScope.SameReviewScope,
              required = true,
              mode = CodeReviewCompositionMode.KmpBaseline,
            ),
          ),
        )
      },
    )
}
