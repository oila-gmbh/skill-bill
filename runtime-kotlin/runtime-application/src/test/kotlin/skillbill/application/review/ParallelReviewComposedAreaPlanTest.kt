package skillbill.application.review

import skillbill.infrastructure.fs.FileSystemReviewAttribution
import skillbill.ports.scaffold.InstalledPlatformPackCatalogPort
import skillbill.scaffold.model.PlatformManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelReviewComposedAreaPlanTest {
  private val genericAreas = listOf("performance", "ux-accessibility")
  private val kotlinAreas = listOf("architecture", "security", "testing")
  private val kmpAreas = listOf("platform-correctness", "ui")

  private val generic = reviewPack("generic", genericAreas, routingSignals = listOf("*.py"))
  private val kotlin = reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt"))
  private val kmp = reviewPack(
    "kmp",
    kmpAreas,
    layers = listOf(reviewLayer("kotlin")),
    routingSignals = listOf("commonMain"),
    contentSignals = listOf("expect", "actual"),
  )
  private val manifests = listOf(generic, kotlin, kmp)

  @Test fun `a routed root plans only the areas its own composition closure declares`() {
    assertEquals(
      kotlinAreas.toSet(),
      plannedAreas(kotlinDiff()),
      "The kotlin root's plan must not carry areas only an unrouted pack declares.",
    )
    assertEquals(
      (kmpAreas + kotlinAreas).toSet(),
      plannedAreas(kmpDiff()),
      "The kmp root's plan is its own areas plus its composed kotlin baseline, and nothing else.",
    )
  }

  @Test fun `launch and attribution pin the same composed area set for a routed pack`() {
    val attribution = FileSystemReviewAttribution(InstalledPlatformPackCatalogPort { manifests })

    assertEquals(
      attribution.composedLaunchPlan("kotlin").lanes.map { it.area }.toSet(),
      plannedAreas(kotlinDiff()),
      "Parity pin: a pack with no composed baseline layer resolves one area set on both sides.",
    )
    assertEquals(
      attribution.composedLaunchPlan("kmp").lanes.map { it.area }.toSet(),
      plannedAreas(kmpDiff()),
      "Parity pin: a pack composing a baseline layer resolves one area set on both sides.",
    )
  }

  @Test fun `a routed root declaring no code review area fails loudly instead of planning no lane`() {
    val bare = reviewPack("bare", emptyList(), routingSignals = listOf("*.kt"))
    val failure = assertFailsWith<IllegalArgumentException> {
      run(listOf(bare), kotlinDiff())
    }

    assertTrue(
      "bare" in failure.message.orEmpty(),
      "The empty-composition failure must name the routed pack: ${failure.message}",
    )
  }

  private fun kotlinDiff() = diffForPaths("src/Repo.kt")

  private fun kmpDiff() = diffForChanges(
    "src/commonMain/kotlin/App.kt" to "expect fun platformName(): String",
    "src/main/kotlin/App.kt" to "actual fun platformName(): String = \"jvm\"",
  )

  private fun plannedAreas(diff: String): Set<String> {
    val recorder = run(manifests, diff)
    return recorder.durableLanes.map { it.area }.toSet()
  }

  private fun run(packs: List<PlatformManifest>, diff: String): ReviewRecorder {
    val recorder = ReviewRecorder()
    reviewHarness(ReviewHarnessConfig(manifests = packs, diff = diff), recorder)
      .run(harnessRequest(reviewRunId = "composed-area-plan"))
    return recorder
  }
}
