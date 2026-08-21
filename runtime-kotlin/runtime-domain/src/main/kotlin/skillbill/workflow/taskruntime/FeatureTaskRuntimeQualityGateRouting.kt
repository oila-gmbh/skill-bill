package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection

object FeatureTaskRuntimeQualityGateRouting {
  fun selectedGatePhase(selection: FeatureTaskRuntimeQualityGateSelection): String = when (selection) {
    FeatureTaskRuntimeQualityGateSelection.BUILD -> FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
    FeatureTaskRuntimeQualityGateSelection.VALIDATE -> FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
  }

  fun applyAfterReview(
    @Suppress("UnusedParameter") currentPhaseId: String,
    transition: FeatureTaskRuntimeNextPhase,
    selection: FeatureTaskRuntimeQualityGateSelection,
  ): FeatureTaskRuntimeNextPhase {
    if (selection != FeatureTaskRuntimeQualityGateSelection.BUILD) {
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
