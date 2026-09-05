package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerAppliedRepair
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import java.time.Clock

internal fun recordChildRepairWedge(
  state: GoalRunnerChildRepairWedgeApplyLoop.ApplyState,
  wedgeClass: GoalRunnerWedgeClass,
  priorValue: String?,
  newValue: String?,
) {
  val repair = GoalRunnerAppliedRepair(
    subtaskId = state.request.subtaskId,
    workflowId = state.request.workflowId,
    wedgeClass = wedgeClass,
    field = wedgeClass.durableField,
    priorValue = priorValue,
    newValue = newValue,
  )
  state.applied += repair
  state.evidenceEntries += childRepairWedgeEvidenceMap(repair, state.clock)
}

fun childRepairWedgeEvidenceMap(repair: GoalRunnerAppliedRepair, clock: Clock): Map<String, Any?> = linkedMapOf(
  "wedge_class" to repair.wedgeClass.wireValue,
  "field" to repair.field,
  "prior_value" to repair.priorValue,
  "new_value" to repair.newValue,
  "subtask_id" to repair.subtaskId,
  "workflow_id" to repair.workflowId,
  "repaired_at" to clock.instant().toString(),
)
