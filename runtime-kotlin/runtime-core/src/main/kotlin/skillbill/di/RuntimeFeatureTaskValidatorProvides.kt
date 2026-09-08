package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FeatureTaskRuntimeBuildReceiptValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeImplementationAttemptValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeQuarantineValidatorAdapter
import skillbill.infrastructure.fs.ProducerOutputEvidenceValidatorAdapter
import skillbill.infrastructure.fs.RejectedOutputDiagnosticMetadataValidatorAdapter
import skillbill.ports.diagnostics.ProducerOutputEvidenceValidator
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeBuildReceiptValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeImplementationAttemptValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQuarantineValidator

internal interface RuntimeFeatureTaskValidatorProvides {
  @Provides @JvmSynthetic
  fun featureTaskRuntimePhaseOutputValidator(
    adapter: FeatureTaskRuntimePhaseOutputValidatorAdapter,
  ): FeatureTaskRuntimePhaseOutputValidator = adapter

  @Provides @JvmSynthetic
  fun featureTaskRuntimePlanningProjectionValidator(
    adapter: FeatureTaskRuntimePlanningProjectionValidatorAdapter,
  ): FeatureTaskRuntimePlanningProjectionValidator = adapter

  @Provides @JvmSynthetic
  fun featureTaskRuntimeBuildReceiptValidator(
    adapter: FeatureTaskRuntimeBuildReceiptValidatorAdapter,
  ): FeatureTaskRuntimeBuildReceiptValidator = adapter

  @Provides @JvmSynthetic
  fun featureTaskRuntimeHandoffEnvelopeValidator(
    adapter: FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter,
  ): FeatureTaskRuntimeHandoffEnvelopeValidator = adapter

  @Provides @JvmSynthetic
  fun featureTaskRuntimeHandoffFoundationValidator(
    adapter: FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter,
  ): FeatureTaskRuntimeHandoffFoundationValidator = adapter

  @Provides @JvmSynthetic
  fun featureTaskRuntimeQuarantineValidator(
    adapter: FeatureTaskRuntimeQuarantineValidatorAdapter,
  ): FeatureTaskRuntimeQuarantineValidator = adapter

  @Provides @JvmSynthetic
  fun featureTaskRuntimeImplementationAttemptValidator(
    adapter: FeatureTaskRuntimeImplementationAttemptValidatorAdapter,
  ): FeatureTaskRuntimeImplementationAttemptValidator = adapter

  @Provides @JvmSynthetic
  fun rejectedOutputDiagnosticMetadataValidator(): RejectedOutputDiagnosticMetadataValidator =
    RejectedOutputDiagnosticMetadataValidatorAdapter()

  @Provides @JvmSynthetic
  fun producerOutputEvidenceValidator(): ProducerOutputEvidenceValidator = ProducerOutputEvidenceValidatorAdapter()
}
