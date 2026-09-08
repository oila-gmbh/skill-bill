package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.featurespec.FeatureSpecPreparationPolicy
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationIntake
import skillbill.infrastructure.fs.FileSystemFeatureSpecPathResolver
import skillbill.infrastructure.fs.FileSystemSpecScratchStore
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.workflow.specscratch.SpecScratchStore

internal interface RuntimeFeatureSpecProvides {
  @Provides @JvmSynthetic
  fun specScratchStore(store: FileSystemSpecScratchStore): SpecScratchStore = store

  @Provides @JvmSynthetic
  fun featureSpecPathResolverPort(adapter: FileSystemFeatureSpecPathResolver): FeatureSpecPathResolverPort = adapter

  @Provides @JvmSynthetic
  fun featureSpecPreparationCore(): (FeatureSpecPreparationIntake) -> FeatureSpecPreparationDecision =
    FeatureSpecPreparationPolicy::prepare
}
