package skillbill.application.goalrunner.planning

import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path

internal fun DefaultGoalPlanningSweep.preSweepStopped(
  request: GoalRunnerRunRequest,
  reason: String,
  currentSubtaskId: Int = 0,
): GoalPlanningSweepOutcome.Stopped = GoalPlanningSweepOutcome.Stopped(
  issueKey = request.issueKey,
  currentSubtaskId = currentSubtaskId,
  reason = GoalRunnerStopReason.BLOCKED,
  blockedReason = reason,
  lastResumableStep = GoalPlanningSweepConstants.PHASE_PREPLAN,
)

internal fun DefaultGoalPlanningSweep.canonicalRepository(repoRoot: Path): Path = runCatching { repoRoot.toRealPath() }
  .getOrElse { repoRoot.toAbsolutePath().normalize() }

internal fun DefaultGoalPlanningSweep.planningProgressMessage(phaseId: String, subtask: DecompositionSubtask?): String =
  if (phaseId == GoalPlanningSweepConstants.PHASE_PREPLAN) {
    "skill-bill: goal planning - parent goal shared preplan\n"
  } else {
    "skill-bill: goal planning - subtask ${requireNotNull(subtask).id} plan\n"
  }

internal fun DefaultGoalPlanningSweep.sharedContextReason(error: Throwable): String =
  "Goal planning shared context could not be gathered: ${error.message.orEmpty()}"

internal fun DefaultGoalPlanningSweep.projectionRejectedReason(phaseId: String, error: Throwable): String =
  "Goal planning phase '$phaseId' rejected a declared bounded projection at the launch seam: " +
    "${error.message.orEmpty()}. Migrate or delete the affected goal-planning preparation record."

internal fun DefaultGoalPlanningSweep.preparationStateReadReason(
  error: Throwable,
  issueKey: String,
  subtaskId: Int,
): String = goalPlanningPreparationStateReadStopReason(error, issueKey, subtaskId)

internal fun DefaultGoalPlanningSweep.stopped(
  shared: GoalPlanningSharedContext,
  subtaskId: Int,
  blockedReason: String,
  lastResumableStep: String = GoalPlanningSweepConstants.PHASE_PREPLAN,
  reason: GoalRunnerStopReason = GoalRunnerStopReason.BLOCKED,
): GoalPlanningSweepOutcome.Stopped = GoalPlanningSweepOutcome.Stopped(
  issueKey = shared.issueKey,
  currentSubtaskId = subtaskId,
  reason = reason,
  blockedReason = blockedReason,
  lastResumableStep = lastResumableStep,
)

internal fun noSuchSubtaskReason(subtaskId: Int): String =
  "Goal planning selected subtask '$subtaskId' which is not present in the accepted decomposition."

internal fun unresolvedSpecReason(subtask: DecompositionSubtask): String =
  "Goal planning subtask '${subtask.id}' governed spec path '${subtask.specPath}' could not be resolved " +
    "inside the repository."
