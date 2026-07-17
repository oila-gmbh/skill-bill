package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalPlanningContext
import java.nio.file.Path

fun interface GoalPlanningContextDiscovery {
  fun discover(repoRoot: Path): GoalPlanningContext
}
