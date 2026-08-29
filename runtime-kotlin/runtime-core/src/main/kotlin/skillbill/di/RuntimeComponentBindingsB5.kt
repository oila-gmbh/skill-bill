package skillbill.di

import skillbill.infrastructure.fs.FeatureTaskRuntimeBuildReceiptValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeImplementationAttemptValidatorAdapter
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
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQuarantineValidator

internal object RuntimeComponentBindingsB5 {
  internal fun featureTaskRuntimePlanningProjectionValidator(
    adapter: FeatureTaskRuntimePlanningProjectionValidatorAdapter,
  ): FeatureTaskRuntimePlanningProjectionValidator = adapter

  internal fun featureTaskRuntimeBuildReceiptValidator(
    adapter: FeatureTaskRuntimeBuildReceiptValidatorAdapter,
  ): FeatureTaskRuntimeBuildReceiptValidator = adapter

  internal fun featureTaskRuntimeHandoffEnvelopeValidator(
    adapter: FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter,
  ): FeatureTaskRuntimeHandoffEnvelopeValidator = adapter

  internal fun featureTaskRuntimeHandoffFoundationValidator(
    adapter: FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter,
  ): FeatureTaskRuntimeHandoffFoundationValidator = adapter

  // SKILL-140: the canonical quarantine schema gate. The recorder's append and read seams call this
  // port so a malformed private-evidence store fails loudly rather than round-tripping silently.

  internal fun featureTaskRuntimeQuarantineValidator(
    adapter: FeatureTaskRuntimeQuarantineValidatorAdapter,
  ): FeatureTaskRuntimeQuarantineValidator = adapter

  // SKILL-150: the canonical implementation-attempt schema gate. The recorder validates every
  // appended attempt through this port inside the advancing transaction, so a malformed receipt
  // never reaches the durable store the continuation projection is reconstructed from.

  internal fun featureTaskRuntimeImplementationAttemptValidator(
    adapter: FeatureTaskRuntimeImplementationAttemptValidatorAdapter,
  ): FeatureTaskRuntimeImplementationAttemptValidator = adapter

  fun rejectedOutputDiagnosticMetadataValidator(): RejectedOutputDiagnosticMetadataValidator =
    RejectedOutputDiagnosticMetadataValidatorAdapter()

  fun producerOutputEvidenceValidator(): ProducerOutputEvidenceValidator = ProducerOutputEvidenceValidatorAdapter()
}
