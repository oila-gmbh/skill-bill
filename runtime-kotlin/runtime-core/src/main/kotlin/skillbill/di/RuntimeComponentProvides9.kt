package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.AgentRunReviewIsolationResolver
import skillbill.infrastructure.fs.FeatureTaskRuntimeImplementationAttemptValidatorAdapter
import skillbill.infrastructure.fs.FileSystemFeatureSpecPathResolver
import skillbill.infrastructure.fs.FileSystemReviewEvidenceBrokerFactory
import skillbill.infrastructure.fs.GoalObservabilityEventValidatorAdapter
import skillbill.infrastructure.fs.GoalPlanningPreparationEnvelopeValidatorAdapter
import skillbill.infrastructure.fs.GoalProgressEventValidatorAdapter
import skillbill.infrastructure.fs.IdeStatusValidatorAdapter
import skillbill.infrastructure.fs.ReviewContextEnvelopeValidatorAdapter
import skillbill.launcher.review.UnixSocketGovernedReviewEvidenceEndpointBinder

internal interface RuntimeComponentProvides9 {
  @Provides @JvmSynthetic
  fun featureTaskRuntimeImplementationAttemptValidator(
    adapter: FeatureTaskRuntimeImplementationAttemptValidatorAdapter,
  ) = RuntimeComponentBindingsB5.featureTaskRuntimeImplementationAttemptValidator(adapter)

  @Provides @JvmSynthetic
  fun goalPlanningPreparationEnvelopeValidator(adapter: GoalPlanningPreparationEnvelopeValidatorAdapter) =
    RuntimeComponentBindingsB6.goalPlanningPreparationEnvelopeValidator(adapter)

  @Provides @JvmSynthetic
  fun reviewContextEnvelopeValidator(adapter: ReviewContextEnvelopeValidatorAdapter) =
    RuntimeComponentBindingsB6.reviewContextEnvelopeValidator(adapter)

  @Provides @JvmSynthetic
  fun reviewEvidenceBrokerFactory(adapter: FileSystemReviewEvidenceBrokerFactory) =
    RuntimeComponentBindingsB6.reviewEvidenceBrokerFactory(adapter)

  @Provides @JvmSynthetic
  fun governedReviewEvidenceEndpointBinder(adapter: UnixSocketGovernedReviewEvidenceEndpointBinder) =
    RuntimeComponentBindingsB6.governedReviewEvidenceEndpointBinder(adapter)

  @Provides @JvmSynthetic
  fun reviewLaunchIsolationResolver(adapter: AgentRunReviewIsolationResolver) =
    RuntimeComponentBindingsB6.reviewLaunchIsolationResolver(adapter)

  @Provides @JvmSynthetic
  fun featureSpecPathResolverPort(adapter: FileSystemFeatureSpecPathResolver) =
    RuntimeComponentBindingsB6.featureSpecPathResolverPort(adapter)

  @Provides @JvmSynthetic
  fun goalObservabilityEventValidator(adapter: GoalObservabilityEventValidatorAdapter) =
    RuntimeComponentBindingsB6.goalObservabilityEventValidator(adapter)

  @Provides @JvmSynthetic
  fun goalProgressEventValidator(adapter: GoalProgressEventValidatorAdapter) =
    RuntimeComponentBindingsB6.goalProgressEventValidator(adapter)

  @Provides @JvmSynthetic
  fun ideStatusValidator(adapter: IdeStatusValidatorAdapter) = RuntimeComponentBindingsB7.ideStatusValidator(adapter)
}
