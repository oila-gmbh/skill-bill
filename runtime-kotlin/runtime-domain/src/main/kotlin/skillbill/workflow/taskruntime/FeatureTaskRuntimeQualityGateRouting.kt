package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection

object FeatureTaskRuntimeQualityGateRouting {
  fun selectedGatePhase(selection: FeatureTaskRuntimeQualityGateSelection): String = when (selection) {
    FeatureTaskRuntimeQualityGateSelection.BUILD -> FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
    FeatureTaskRuntimeQualityGateSelection.VALIDATE -> FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
  }

  /**
   * When the selected quality gate is build, traffic that would advance into validate is sent to
   * build instead. Do not remap when already leaving build: the forward next is still validate, and
   * [applyAfterBuild] must see that target so it can advance to write_history. Remapping here would
   * produce build → build and spin the run loop on an already-complete phase.
   */
  fun applyAfterReview(
    currentPhaseId: String,
    transition: FeatureTaskRuntimeNextPhase,
    selection: FeatureTaskRuntimeQualityGateSelection,
  ): FeatureTaskRuntimeNextPhase {
    if (selection != FeatureTaskRuntimeQualityGateSelection.BUILD) {
      return transition
    }
    if (currentPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
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
