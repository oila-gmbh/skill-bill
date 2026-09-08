package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileSystemDiffResolver
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSharedEvidenceStore
import skillbill.infrastructure.fs.FileSystemReviewEvidenceBrokerFactory
import skillbill.infrastructure.fs.FileSystemReviewInputSource
import skillbill.infrastructure.fs.FileSystemReviewSnapshotGateway
import skillbill.infrastructure.fs.ReviewContextEnvelopeValidatorAdapter
import skillbill.launcher.review.UnixSocketGovernedReviewEvidenceEndpointBinder
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.review.ReviewSnapshotGateway
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.model.ParallelReviewParseResult

internal interface RuntimeReviewEvidenceProvides {
  @Provides @JvmSynthetic
  fun reviewContextEnvelopeValidator(adapter: ReviewContextEnvelopeValidatorAdapter): ReviewContextEnvelopeValidator =
    adapter

  @Provides @JvmSynthetic
  fun reviewEvidenceBrokerFactory(adapter: FileSystemReviewEvidenceBrokerFactory): ReviewEvidenceBrokerFactory = adapter

  @Provides @JvmSynthetic
  fun governedReviewEvidenceEndpointBinder(
    adapter: UnixSocketGovernedReviewEvidenceEndpointBinder,
  ): GovernedReviewEvidenceEndpointBinder = adapter

  @Provides @JvmSynthetic
  fun sharedEvidenceResolverPort(
    adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore,
  ): FeatureTaskRuntimeSharedEvidenceResolverPort = adapter

  @Provides @JvmSynthetic
  fun sharedEvidenceLocatorReadPort(
    adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore,
  ): FeatureTaskRuntimeSharedEvidenceLocatorReadPort = adapter

  @Provides @JvmSynthetic
  fun reviewSnapshotGateway(gateway: FileSystemReviewSnapshotGateway): ReviewSnapshotGateway = gateway

  @Provides @JvmSynthetic
  fun reviewInputSource(source: FileSystemReviewInputSource): ReviewInputSource = source

  @Provides @JvmSynthetic
  fun diffResolverPort(adapter: FileSystemDiffResolver): DiffResolverPort = adapter

  @Provides @JvmSynthetic
  fun parallelReviewParseRegister(): (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse
}
