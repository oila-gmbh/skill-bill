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

internal interface RuntimeComponentProvides8 {
  @Provides @JvmSynthetic
  fun specScratchStore(store: FileSystemSpecScratchStore) = RuntimeComponentBindingsB4.specScratchStore(store)

  @Provides @JvmSynthetic
  fun installPlanWireValidator(adapter: InstallPlanWireValidatorAdapter) =
    RuntimeComponentBindingsB4.installPlanWireValidator(adapter)

  @Provides @JvmSynthetic
  fun decompositionManifestValidator(adapter: DecompositionManifestValidatorAdapter) =
    RuntimeComponentBindingsB4.decompositionManifestValidator(adapter)

  @Provides @JvmSynthetic
  fun workflowSnapshotValidator(adapter: WorkflowSnapshotValidatorInfraAdapter) =
    RuntimeComponentBindingsB4.workflowSnapshotValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimePhaseOutputValidator(adapter: FeatureTaskRuntimePhaseOutputValidatorAdapter) =
    RuntimeComponentBindingsB4.featureTaskRuntimePhaseOutputValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimePlanningProjectionValidator(adapter: FeatureTaskRuntimePlanningProjectionValidatorAdapter) =
    RuntimeComponentBindingsB5.featureTaskRuntimePlanningProjectionValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeBuildReceiptValidator(adapter: FeatureTaskRuntimeBuildReceiptValidatorAdapter) =
    RuntimeComponentBindingsB5.featureTaskRuntimeBuildReceiptValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeHandoffEnvelopeValidator(adapter: FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter) =
    RuntimeComponentBindingsB5.featureTaskRuntimeHandoffEnvelopeValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeHandoffFoundationValidator(adapter: FeatureTaskRuntimeHandoffFoundationValidatorInfraAdapter) =
    RuntimeComponentBindingsB5.featureTaskRuntimeHandoffFoundationValidator(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeQuarantineValidator(adapter: FeatureTaskRuntimeQuarantineValidatorAdapter) =
    RuntimeComponentBindingsB5.featureTaskRuntimeQuarantineValidator(adapter)
}
