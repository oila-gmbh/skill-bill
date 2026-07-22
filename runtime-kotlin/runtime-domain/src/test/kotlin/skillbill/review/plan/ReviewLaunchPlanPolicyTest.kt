package skillbill.review.plan

import org.junit.jupiter.api.Test
import skillbill.error.AmbiguousLaneOwnershipError
import skillbill.error.IncompatibleCompositionContractError
import skillbill.error.MissingCompositionLayerError
import skillbill.error.ReviewCompositionCycleError
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewLaunchPlanPolicyTest {
  @Test
  fun `kmp flattens kotlin baseline into ten direct specialists`() {
    val kotlin = pack("kotlin", KOTLIN_AREAS)
    val kmp = pack("kmp", KMP_AREAS, layers = listOf(layer("kotlin")))

    val plan = ReviewLaunchPlanPolicy.flatten("kmp", listOf(kmp, kotlin), (KOTLIN_AREAS + KMP_AREAS).toSet())

    assertEquals(10, plan.lanes.size)
    assertEquals(KMP_AREAS.sorted(), plan.lanes.take(3).map { it.area })
    assertEquals(KOTLIN_AREAS.filterNot { it in KMP_AREAS }.sorted(), plan.lanes.drop(3).map { it.area })
    assertTrue(plan.lanes.none { it.skillName == "bill-kotlin-code-review" })
    assertTrue(plan.lanes.filter { it.packSlug == "kotlin" }.all { it.originLayerChain == listOf("kmp", "kotlin") })
  }

  @Test
  fun `standalone pack emits its selected direct specialists and drops empty selection`() {
    val kotlin = pack("kotlin", KOTLIN_AREAS)
    assertEquals(
      listOf("bill-kotlin-code-review-architecture", "bill-kotlin-code-review-security"),
      ReviewLaunchPlanPolicy.flatten("kotlin", listOf(kotlin), setOf("security", "architecture"))
        .lanes.map { it.skillName },
    )
    assertTrue(ReviewLaunchPlanPolicy.flatten("kotlin", listOf(kotlin), emptySet()).lanes.isEmpty())
  }

  @Test
  fun `three deep composition keeps the complete attribution chain`() {
    val base = pack("base", listOf("security"))
    val middle = pack("middle", listOf("testing"), layers = listOf(layer("base")))
    val root = pack("root", listOf("ui"), layers = listOf(layer("middle")))

    val plan = ReviewLaunchPlanPolicy.flatten("root", listOf(root, middle, base), setOf("ui", "testing", "security"))

    assertEquals(listOf("root", "middle", "base"), plan.lanes.single { it.area == "security" }.originLayerChain)
  }

  @Test
  fun `diamond composition retains every origin and required reachability`() {
    val base = pack("base", listOf("security"))
    val left = pack("left", emptyList(), layers = listOf(layer("base", required = false)))
    val right = pack("right", emptyList(), layers = listOf(layer("base")))
    val root = pack(
      "root",
      emptyList(),
      layers = listOf(layer("left", required = false), layer("right", required = false)),
    )

    val lane = ReviewLaunchPlanPolicy.flatten("root", listOf(root, left, right, base), setOf("security")).lanes.single()

    assertEquals(listOf(listOf("root", "left", "base"), listOf("root", "right", "base")), lane.originLayerChains)
    assertTrue(lane.required)
  }

  @Test
  fun `cycle missing layer and contract drift fail loudly`() {
    val a = pack("a", listOf("security"), layers = listOf(layer("b")))
    val b = pack("b", listOf("testing"), layers = listOf(layer("a")))
    assertFailsWith<ReviewCompositionCycleError> {
      ReviewLaunchPlanPolicy.flatten("a", listOf(a, b), setOf("security", "testing"))
    }
    assertFailsWith<MissingCompositionLayerError> {
      ReviewLaunchPlanPolicy.flatten("a", listOf(a), setOf("security"))
    }
    assertFailsWith<IncompatibleCompositionContractError> {
      ReviewLaunchPlanPolicy.flatten("a", listOf(a, b.copy(contractVersion = "2.0")), setOf("security"))
    }
  }

  @Test
  fun `same depth sibling ownership is rejected`() {
    val left = pack("left", listOf("security"))
    val right = pack("right", listOf("security"))
    val root = pack("root", listOf("ui"), layers = listOf(layer("left"), layer("right")))
    assertFailsWith<AmbiguousLaneOwnershipError> {
      ReviewLaunchPlanPolicy.flatten("root", listOf(root, left, right), setOf("security"))
    }
  }

  private fun pack(slug: String, areas: List<String>, layers: List<CodeReviewBaselineLayer> = emptyList()) =
    PlatformManifest(
      slug = slug,
      packRoot = Path.of("platform-packs", slug),
      contractVersion = "1.2",
      routingSignals = RoutingSignals(emptyList(), emptyList()),
      declaredCodeReviewAreas = areas,
      declaredFiles = DeclaredFiles(
        baseline = Path.of("platform-packs", slug, "code-review", "bill-$slug-code-review", "content.md"),
        areas = areas.associateWith {
          Path.of("platform-packs", slug, "code-review", "bill-$slug-code-review-$it", "content.md")
        },
      ),
      areaMetadata = emptyMap(),
      codeReviewComposition = layers.takeIf { it.isNotEmpty() }?.let(::CodeReviewComposition),
    )

  private fun layer(slug: String, required: Boolean = true) = CodeReviewBaselineLayer(
    platform = slug,
    skill = "bill-$slug-code-review",
    scope = CodeReviewCompositionScope.SameReviewScope,
    required = required,
    mode = CodeReviewCompositionMode.KmpBaseline,
  )

  private companion object {
    val KMP_AREAS = listOf("platform-correctness", "ui", "ux-accessibility")
    val KOTLIN_AREAS = listOf(
      "architecture", "performance", "platform-correctness", "security", "testing",
      "api-contracts", "persistence", "reliability", "ui", "ux-accessibility",
    )
  }
}
