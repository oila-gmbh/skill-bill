package skillbill.workflow.decomposition.runtime
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

fun DecompositionManifest.isActiveGoalRuntime(): Boolean = status.decompositionStatus() !in
  setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED) &&
  subtasks.any { subtask ->
    subtask.status.decompositionStatus() !in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
  }
