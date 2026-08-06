package skillbill.review

import skillbill.review.model.ReviewRunLane
import skillbill.review.plan.model.ReviewLaunchPlan

/**
 * Turns a composed launch plan plus the lane names a run reported into the durable lane rows.
 *
 * The plan is the only source of a lane's pack, area, depth and required flag; reported narration
 * text never contributes identity. A reported name the plan does not contain is kept verbatim and
 * marked unresolved so a routing gap stays visible instead of being guessed into a bucket.
 *
 * A planned lane resolves only when the run actually reported it. A plan is what the composition
 * would launch, not evidence that it ran: an inline run reports no specialists at all, so marking
 * every planned lane resolved would record the pack's whole area set as launched coverage that
 * never happened.
 */
object ReviewRunLaneResolver {
  const val RESOLVED: String = "resolved"
  const val UNRESOLVED: String = "unresolved"

  fun resolve(plan: ReviewLaunchPlan, reportedLaneNames: List<String>): List<ReviewRunLane> {
    // Narration reports a lane by skill name or by bare area ("Specialist reviews: architecture"),
    // so both count as reporting a planned lane. Neither contributes identity: the plan row already
    // holds it. Only a name matching no planned lane at all is a genuine routing gap.
    val reported = reportedLaneNames.map(String::trim).filter(String::isNotEmpty).toSet()
    val planned = plan.lanes.map { lane ->
      ReviewRunLane(
        laneSkillName = lane.skillName,
        packSlug = lane.packSlug,
        area = lane.area,
        depth = lane.depth,
        required = lane.required,
        orderIndex = lane.orderIndex,
        originLayerChain = lane.originLayerChain,
        resolutionState = if (lane.skillName in reported || lane.area in reported) RESOLVED else UNRESOLVED,
      )
    }
    val plannedNames = planned.flatMap { listOf(it.laneSkillName, it.area) }.toSet()
    val unmatched = reported.filter { it !in plannedNames }
    return planned + unmatched.mapIndexed { index, reportedName ->
      ReviewRunLane(
        laneSkillName = reportedName,
        packSlug = UNRESOLVED,
        area = UNRESOLVED,
        depth = 0,
        required = false,
        orderIndex = planned.size + index,
        originLayerChain = emptyList(),
        resolutionState = UNRESOLVED,
      )
    }
  }
}
