package skillbill.application.goalrunner

import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.ValidationDepth

/**
 * Stamps validate depth from manifest array order: the last non-skipped subtask gets [FULL];
 * every earlier non-skipped entry gets [BUILD_ONLY]. Skipped ordinal-last promotes the previous
 * last non-skipped entry. Uses [DecompositionSubtask.status], never dependency.skipped.
 */
internal fun validationDepthForSubtask(
  subtasks: List<DecompositionSubtask>,
  currentSubtaskId: Int,
): ValidationDepth {
  val fullTargetId = subtasks.lastOrNull { it.status != "skipped" }?.id
  return if (fullTargetId == currentSubtaskId) ValidationDepth.FULL else ValidationDepth.BUILD_ONLY
}
