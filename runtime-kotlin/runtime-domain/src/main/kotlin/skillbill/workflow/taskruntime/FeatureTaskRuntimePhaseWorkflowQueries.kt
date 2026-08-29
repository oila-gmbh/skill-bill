package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCeremony
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCeremonyScaling
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePreplanCeremony
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewScope

object FeatureTaskRuntimePhaseWorkflowQueries {
  fun backwardEdgeForLoop(loopId: String): FeatureTaskRuntimeBackwardEdge? =
    FeatureTaskRuntimePhaseWorkflowTransitions.backwardEdgeForLoop(
      FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
      loopId,
    )

  fun ceremonyScaling(featureSize: FeatureTaskRuntimeFeatureSize): FeatureTaskRuntimeCeremonyScaling =
    when (featureSize) {
      FeatureTaskRuntimeFeatureSize.SMALL -> FeatureTaskRuntimeCeremonyScaling(
        preplanCeremony = FeatureTaskRuntimePreplanCeremony.LIGHT,
        reviewScope = FeatureTaskRuntimeReviewScope.CURRENT_UNIT_OF_WORK,
        auditCeremony = FeatureTaskRuntimeAuditCeremony.LIGHT,
      )
      FeatureTaskRuntimeFeatureSize.MEDIUM,
      FeatureTaskRuntimeFeatureSize.LARGE,
      -> FeatureTaskRuntimeCeremonyScaling(
        preplanCeremony = FeatureTaskRuntimePreplanCeremony.FULL,
        reviewScope = FeatureTaskRuntimeReviewScope.BRANCH_DIFF,
        auditCeremony = FeatureTaskRuntimeAuditCeremony.FULL_PER_CRITERION,
      )
    }

  fun phaseDeclaration(
    phaseId: String,
    featureSize: FeatureTaskRuntimeFeatureSize,
  ): FeatureTaskRuntimePhaseDeclaration {
    val base = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations[phaseId]
      ?: error("No phase declaration for runtime phase '$phaseId'.")
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      return base
    }
    val reviewKey = when (ceremonyScaling(featureSize).reviewScope) {
      FeatureTaskRuntimeReviewScope.CURRENT_UNIT_OF_WORK -> "current_unit_of_work"
      FeatureTaskRuntimeReviewScope.BRANCH_DIFF -> "diff"
    }
    return base.copy(derivedContextKeys = listOf(reviewKey))
  }

  fun phaseDeclarationForQualityGate(
    phaseId: String,
    featureSize: FeatureTaskRuntimeFeatureSize,
    qualityGateSelection: FeatureTaskRuntimeQualityGateSelection,
  ): FeatureTaskRuntimePhaseDeclaration {
    val base = phaseDeclaration(phaseId, featureSize)
    if (
      phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY &&
      phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH
    ) {
      return base
    }
    val selectedGatePhase = when (qualityGateSelection) {
      FeatureTaskRuntimeQualityGateSelection.BUILD -> FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
      FeatureTaskRuntimeQualityGateSelection.VALIDATE -> FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
    }
    val omittedGatePhase =
      if (selectedGatePhase == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
      } else {
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
      }
    return base.copy(
      projectionDeclarations = base.projectionDeclarations.filter { declaration ->
        val source = declaration.sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput
        source?.producingPhaseId != omittedGatePhase
      },
    )
  }
}
