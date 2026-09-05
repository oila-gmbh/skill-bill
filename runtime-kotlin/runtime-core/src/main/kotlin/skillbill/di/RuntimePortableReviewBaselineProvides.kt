package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.featuretask.model.PortableUnreachableReviewBaseRecovery
import skillbill.application.goalrunner.PortableUnreachableReviewBaseRecoveryAdapter
import skillbill.infrastructure.fs.FileSystemPortableReviewBaselinePersistence
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence

internal interface RuntimePortableReviewBaselineProvides {
  @Provides @JvmSynthetic
  fun portableReviewBaselinePersistence(
    adapter: FileSystemPortableReviewBaselinePersistence,
  ): PortableReviewBaselinePersistence = adapter

  @Provides @JvmSynthetic
  fun portableUnreachableReviewBaseRecovery(
    adapter: PortableUnreachableReviewBaseRecoveryAdapter,
  ): PortableUnreachableReviewBaseRecovery = adapter
}
