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

internal interface RuntimeFeatureTaskGoalValidatorProvides {
  @Provides @JvmSynthetic
  fun featureTaskRuntimeImplementationAttemptValidator(
    adapter: FeatureTaskRuntimeImplementationAttemptValidatorAdapter,
  ) = RuntimeFeatureTaskRuntimeValidatorBindings.featureTaskRuntimeImplementationAttemptValidator(adapter)

  @Provides @JvmSynthetic
  fun goalPlanningPreparationEnvelopeValidator(adapter: GoalPlanningPreparationEnvelopeValidatorAdapter) =
    RuntimeGoalReviewValidatorBindings.goalPlanningPreparationEnvelopeValidator(adapter)

  @Provides @JvmSynthetic
  fun reviewContextEnvelopeValidator(adapter: ReviewContextEnvelopeValidatorAdapter) =
    RuntimeGoalReviewValidatorBindings.reviewContextEnvelopeValidator(adapter)

  @Provides @JvmSynthetic
  fun reviewEvidenceBrokerFactory(adapter: FileSystemReviewEvidenceBrokerFactory) =
    RuntimeGoalReviewValidatorBindings.reviewEvidenceBrokerFactory(adapter)

  @Provides @JvmSynthetic
  fun governedReviewEvidenceEndpointBinder(adapter: UnixSocketGovernedReviewEvidenceEndpointBinder) =
    RuntimeGoalReviewValidatorBindings.governedReviewEvidenceEndpointBinder(adapter)

  @Provides @JvmSynthetic
  fun reviewLaunchIsolationResolver(adapter: AgentRunReviewIsolationResolver) =
    RuntimeGoalReviewValidatorBindings.reviewLaunchIsolationResolver(adapter)

  @Provides @JvmSynthetic
  fun featureSpecPathResolverPort(adapter: FileSystemFeatureSpecPathResolver) =
    RuntimeGoalReviewValidatorBindings.featureSpecPathResolverPort(adapter)

  @Provides @JvmSynthetic
  fun goalObservabilityEventValidator(adapter: GoalObservabilityEventValidatorAdapter) =
    RuntimeGoalReviewValidatorBindings.goalObservabilityEventValidator(adapter)

  @Provides @JvmSynthetic
  fun goalProgressEventValidator(adapter: GoalProgressEventValidatorAdapter) =
    RuntimeGoalReviewValidatorBindings.goalProgressEventValidator(adapter)

  @Provides @JvmSynthetic
  fun ideStatusValidator(adapter: IdeStatusValidatorAdapter) =
    RuntimeFeatureTaskReviewIntegrationBindings.ideStatusValidator(adapter)
}
