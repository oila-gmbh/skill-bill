package skillbill.application.decomposition

import skillbill.workflow.decomposition.model.DecompositionManifest

fun DecompositionManifest.isActiveGoalRuntime(): Boolean = status !in setOf("complete", "skipped") &&
  subtasks.any { subtask -> subtask.status !in setOf("complete", "skipped") }
