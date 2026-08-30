package skillbill.goalplanning

import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBodyResolutionCaps
import java.nio.file.Path

internal data class BoundaryHeadingResolutionInput(
  val canonicalRoot: Path,
  val headingId: String,
  val index: Int,
  val requested: List<String>,
  val catalogHeadingIds: Set<String>,
  val caps: GoalPlanningBoundaryBodyResolutionCaps,
  val loudFailOnCapExceeded: Boolean,
  val state: BoundaryBodyResolutionState,
)
