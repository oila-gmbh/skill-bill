package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection

object FeatureTaskRuntimeQualityGateRouting {
  fun applyAfterReview(
    currentPhaseId: String,
    transition: FeatureTaskRuntimeNextPhase,
    selection: FeatureTaskRuntimeQualityGateSelection,
  ): FeatureTaskRuntimeNextPhase {
    if (currentPhaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ||
      selection != FeatureTaskRuntimeQualityGateSelection.BUILD
    ) {
      return transition
    }
    val next = transition as? FeatureTaskRuntimeNextPhase.Next ?: return transition
    return if (next.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
      next.copy(phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD)
    } else {
      transition
    }
  }

  fun applyAfterBuild(currentPhaseId: String, transition: FeatureTaskRuntimeNextPhase): FeatureTaskRuntimeNextPhase {
    if (currentPhaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
      return transition
    }
    val next = transition as? FeatureTaskRuntimeNextPhase.Next ?: return transition
    if (next.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
      return transition
    }
    return next.copy(phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY)
  }
}
