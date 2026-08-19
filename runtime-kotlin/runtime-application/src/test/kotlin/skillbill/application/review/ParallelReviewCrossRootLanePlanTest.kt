package skillbill.application.review

import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.model.PlatformManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelReviewCrossRootLanePlanTest {
  private val kotlinAreas = listOf("architecture", "security", "testing")
  private val kmpAreas = listOf("architecture", "platform-correctness")

  private val kotlin = reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt"))
  private val kmp = reviewPack(
    "kmp",
    kmpAreas,
    layers = listOf(reviewLayer("kotlin")),
    routingSignals = listOf("commonMain"),
    contentSignals = listOf("expect"),
  )
  private val manifests = listOf(kotlin, kmp)

  private val crossRootDiff = diffForChanges(
    "src/commonMain/kotlin/App.kt" to "expect fun platformName(): String",
    "src/main/kotlin/Repo.kt" to "class Repo",
  )

  @Test fun `a cross-root plan carries one lane per area owned by the nearest composing pack`() {
    val lanes = run(manifests, crossRootDiff).durableLanes

    assertEquals(
      lanes.map { it.area }.distinct().size,
      lanes.size,
      "A routed root brought along as a composition baseline must not add a second lane for an area.",
    )
    assertEquals(
      ReviewLaunchPlanPolicy
        .flatten("kmp", manifests, ReviewLaunchPlanPolicy.composedAreas("kmp", manifests))
        .lanes.associate { it.area to it.packSlug },
      lanes.associate { it.area to it.packSlug },
      "The composing root's own flattened plan is the oracle for cross-root area ownership.",
    )
    assertEquals(listOf(0, 1, 2, 3), lanes.sortedBy { it.orderIndex }.map { it.orderIndex })
  }

  @Test fun `dropping a cross-root duplicate leaves area coverage and lane accounting untouched`() {
    val withDuplicate = run(manifests, crossRootDiff)
    val kotlinWithoutOverlap = kotlin.copy(declaredCodeReviewAreas = listOf("security", "testing"))
    val withoutDuplicate = run(listOf(kotlinWithoutOverlap, kmp), crossRootDiff)

    assertEquals(
      (kotlinAreas + kmpAreas).toSet(),
      withDuplicate.durableLanes.map { it.area }.toSet(),
      "Reconciliation removes a duplicate lane, never the last lane for an area.",
    )
    assertEquals(
      withoutDuplicate.durableLanes.associate { it.area to it.reviewDisposition },
      withDuplicate.durableLanes.associate { it.area to it.reviewDisposition },
      "A lane dropped as a cross-root duplicate must not reduce any lane disposition.",
    )
    assertTrue(
      withDuplicate.durableLanes.all { it.unreviewedSegmentIds.isEmpty() },
      "A dropped duplicate is not unreviewed coverage.",
    )
    assertEquals(
      assertNotNull(withoutDuplicate.durableIntegrationPass).terminalOutcome,
      assertNotNull(withDuplicate.durableIntegrationPass).terminalOutcome,
    )
  }

  private fun run(packs: List<PlatformManifest>, diff: String): ReviewRecorder {
    val recorder = ReviewRecorder()
    reviewHarness(ReviewHarnessConfig(manifests = packs, diff = diff), recorder)
      .run(harnessRequest(reviewRunId = "cross-root-lane-plan"))
    return recorder
  }
}
