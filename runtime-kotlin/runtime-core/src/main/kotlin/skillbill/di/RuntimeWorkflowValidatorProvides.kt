package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.GoalObservabilityEventValidatorAdapter
import skillbill.infrastructure.fs.GoalPlanningPreparationEnvelopeValidatorAdapter
import skillbill.infrastructure.fs.GoalProgressEventValidatorAdapter
import skillbill.infrastructure.fs.IdeStatusValidatorAdapter
import skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.goal.GoalProgressEventValidator

internal interface RuntimeWorkflowValidatorProvides {
  @Provides @JvmSynthetic
  fun decompositionManifestValidator(adapter: DecompositionManifestValidatorAdapter): DecompositionManifestValidator =
    adapter

  @Provides @JvmSynthetic
  fun workflowSnapshotValidator(adapter: WorkflowSnapshotValidatorInfraAdapter): WorkflowSnapshotValidator = adapter

  @Provides @JvmSynthetic
  fun goalPlanningPreparationEnvelopeValidator(
    adapter: GoalPlanningPreparationEnvelopeValidatorAdapter,
  ): GoalPlanningPreparationEnvelopeValidator = adapter

  @Provides @JvmSynthetic
  fun goalObservabilityEventValidator(
    adapter: GoalObservabilityEventValidatorAdapter,
  ): GoalObservabilityEventValidator = adapter

  @Provides @JvmSynthetic
  fun goalProgressEventValidator(adapter: GoalProgressEventValidatorAdapter): GoalProgressEventValidator = adapter

  @Provides @JvmSynthetic
  fun ideStatusValidator(adapter: IdeStatusValidatorAdapter): IdeStatusValidator = adapter
}
