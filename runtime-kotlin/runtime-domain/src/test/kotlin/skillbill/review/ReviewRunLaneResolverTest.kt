package skillbill.review

import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewLaunchPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewRunLaneResolverTest {
  @Test
  fun `lane identity comes from the composed plan even when reported narration disagrees`() {
    val plan = ReviewLaunchPlan(
      routedPackSlug = "kmp",
      lanes = listOf(
        lane(skillName = "bill-kmp-code-review-architecture", packSlug = "kmp", area = "architecture"),
        lane(
          skillName = "bill-kotlin-code-review-testing",
          packSlug = "kotlin",
          area = "testing",
          depth = 1,
          orderIndex = 1,
          originLayerChain = listOf("kmp", "kotlin"),
          required = true,
        ),
      ),
    )

    val resolved = ReviewRunLaneResolver.resolve(
      plan,
      reportedLaneNames = listOf("bill-kmp-code-review-architecture", "bill-kotlin-code-review-testing"),
    )

    assertEquals(
      listOf("kmp" to "architecture", "kotlin" to "testing"),
      resolved.map { it.packSlug to it.area },
    )
    assertEquals(listOf(0, 1), resolved.map { it.depth })
    assertEquals(listOf(false, true), resolved.map { it.required })
    assertEquals(listOf("resolved", "resolved"), resolved.map { it.resolutionState })
    assertEquals(listOf(listOf("kmp"), listOf("kmp", "kotlin")), resolved.map { it.originLayerChain })
  }

  @Test
  fun `a reported lane the plan does not contain is retained verbatim and marked unresolved`() {
    val plan = ReviewLaunchPlan(
      routedPackSlug = "kmp",
      lanes = listOf(lane(skillName = "bill-kmp-code-review-architecture", packSlug = "kmp", area = "architecture")),
    )

    val resolved = ReviewRunLaneResolver.resolve(
      plan,
      reportedLaneNames = listOf("bill-kmp-code-review-architecture", " narrated-only-lane ", "narrated-only-lane", ""),
    )

    assertEquals(2, resolved.size, "The unknown lane must be retained, and retained only once.")
    val unresolved = resolved.last()
    assertEquals("narrated-only-lane", unresolved.laneSkillName)
    assertEquals(ReviewRunLaneResolver.UNRESOLVED, unresolved.resolutionState)
    assertEquals(ReviewRunLaneResolver.UNRESOLVED, unresolved.packSlug)
    assertEquals(ReviewRunLaneResolver.UNRESOLVED, unresolved.area)
    assertEquals(1, unresolved.orderIndex)
  }

  @Test
  fun `a lane reported by bare area name is not a routing gap`() {
    val plan = ReviewLaunchPlan(
      routedPackSlug = "kmp",
      lanes = listOf(lane(skillName = "bill-kmp-code-review-architecture", packSlug = "kmp", area = "architecture")),
    )

    val resolved = ReviewRunLaneResolver.resolve(plan, reportedLaneNames = listOf("architecture"))

    assertEquals(listOf("bill-kmp-code-review-architecture"), resolved.map { it.laneSkillName })
    assertEquals(listOf("resolved"), resolved.map { it.resolutionState })
  }

  @Test
  fun `a plan lane the run never reported is still recorded`() {
    val plan = ReviewLaunchPlan(
      routedPackSlug = "kmp",
      lanes = listOf(lane(skillName = "bill-kmp-code-review-architecture", packSlug = "kmp", area = "architecture")),
    )

    val resolved = ReviewRunLaneResolver.resolve(plan, reportedLaneNames = emptyList())

    assertEquals(listOf("bill-kmp-code-review-architecture"), resolved.map { it.laneSkillName })
    assertEquals(listOf("resolved"), resolved.map { it.resolutionState })
  }

  private fun lane(
    skillName: String,
    packSlug: String,
    area: String,
    depth: Int = 0,
    orderIndex: Int = 0,
    originLayerChain: List<String> = listOf(packSlug),
    required: Boolean = false,
  ) = ReviewLaunchLane(
    skillName = skillName,
    packSlug = packSlug,
    area = area,
    depth = depth,
    originLayerChain = originLayerChain,
    required = required,
    addOns = emptyList(),
    orderIndex = orderIndex,
    inclusionReason = "routed-pack override",
  )
}
