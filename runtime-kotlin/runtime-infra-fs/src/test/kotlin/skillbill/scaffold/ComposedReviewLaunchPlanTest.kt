package skillbill.scaffold

import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.testing.repoRootFromTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposedReviewLaunchPlanTest {
  @Test
  fun `composed kmp plan resolves five kmp lanes and five kotlin baseline lanes`() {
    val plan = ReviewLaunchPlanPolicy.flatten("kmp", manifests("kmp", "kotlin", "generic"), APPROVED_CODE_REVIEW_AREAS)

    assertEquals(
      mapOf(
        "platform-correctness" to "bill-kmp-code-review-platform-correctness",
        "persistence" to "bill-kmp-code-review-persistence",
        "reliability" to "bill-kmp-code-review-reliability",
        "ui" to "bill-kmp-code-review-ui",
        "ux-accessibility" to "bill-kmp-code-review-ux-accessibility",
        "architecture" to "bill-kotlin-code-review-architecture",
        "performance" to "bill-kotlin-code-review-performance",
        "security" to "bill-kotlin-code-review-security",
        "testing" to "bill-kotlin-code-review-testing",
        "api-contracts" to "bill-kotlin-code-review-api-contracts",
      ),
      plan.lanes.associate { it.area to it.skillName },
    )
  }

  @Test
  fun `ios composed plan is unchanged by the kmp declarations`() {
    val plan = ReviewLaunchPlanPolicy.flatten("ios", manifests("ios", "generic"), APPROVED_CODE_REVIEW_AREAS)

    assertEquals(
      APPROVED_CODE_REVIEW_AREAS.associateWith { "bill-ios-code-review-$it" },
      plan.lanes.associate { it.area to it.skillName },
    )
    assertTrue(plan.lanes.none { it.skillName.startsWith("bill-kmp-") || it.packSlug == "kmp" })
  }

  private fun manifests(vararg slugs: String) =
    slugs.map { slug -> loadPlatformPack(repoRootFromTest().resolve("platform-packs/$slug")) }
}
