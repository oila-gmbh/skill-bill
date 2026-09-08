package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeBuildReceiptValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimeQuarantineValidatorAdapter
import skillbill.infrastructure.fs.FileSystemSpecScratchStore
import skillbill.infrastructure.fs.InstallPlanWireValidatorAdapter
import skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter

internal interface RuntimeWorkflowValidatorProvides {
  @Provides @JvmSynthetic
  fun specScratchStore(store: FileSystemSpecScratchStore) = RuntimeWorkflowInstallStoreBindings.specScratchStore(store)

  @Provides @JvmSynthetic
  fun installPlanWireValidator(adapter: InstallPlanWireValidatorAdapter) =
    RuntimeWorkflowInstallStoreBindings.installPlanWireValidator(adapter)

  @Provides @JvmSynthetic
  fun decompositionManifestValidator(adapter: DecompositionManifestValidatorAdapter) =
    RuntimeWorkflowInstallStoreBindings.decompositionManifestValidator(adapter)

  @Provides @JvmSynthetic
  fun workflowSnapshotValidator(adapter: WorkflowSnapshotValidatorInfraAdapter) =
    RuntimeWorkflowInstallStoreBindings.workflowSnapshotValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimePhaseOutputValidator(adapter: FeatureTaskRuntimePhaseOutputValidatorAdapter) =
    RuntimeWorkflowInstallStoreBindings.featureTaskRuntimePhaseOutputValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimePlanningProjectionValidator(adapter: FeatureTaskRuntimePlanningProjectionValidatorAdapter) =
    RuntimeFeatureTaskRuntimeValidatorBindings.featureTaskRuntimePlanningProjectionValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeBuildReceiptValidator(adapter: FeatureTaskRuntimeBuildReceiptValidatorAdapter) =
    RuntimeFeatureTaskRuntimeValidatorBindings.featureTaskRuntimeBuildReceiptValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeHandoffEnvelopeValidator(adapter: FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter) =
    RuntimeFeatureTaskRuntimeValidatorBindings.featureTaskRuntimeHandoffEnvelopeValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeHandoffFoundationValidator(adapter: FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter) =
    RuntimeFeatureTaskRuntimeValidatorBindings.featureTaskRuntimeHandoffFoundationValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeQuarantineValidator(adapter: FeatureTaskRuntimeQuarantineValidatorAdapter) =
    RuntimeFeatureTaskRuntimeValidatorBindings.featureTaskRuntimeQuarantineValidator(adapter)
}
