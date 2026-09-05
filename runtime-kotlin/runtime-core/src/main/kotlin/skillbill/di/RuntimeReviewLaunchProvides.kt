package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.review.model.DefaultParallelCodeReviewRunnerLaneLaunchPort
import skillbill.application.review.model.DefaultParallelCodeReviewRunnerPlanningPort
import skillbill.application.review.model.ParallelCodeReviewRunnerLaneLaunchPort
import skillbill.application.review.model.ParallelCodeReviewRunnerPlanningPort
import skillbill.infrastructure.fs.AgentRunReviewIsolationResolver
import skillbill.infrastructure.fs.ClasspathReviewSpecialistContractProvider
import skillbill.infrastructure.fs.FileSystemReviewAttribution
import skillbill.infrastructure.fs.FileSystemReviewLaunchAgentStaging
import skillbill.infrastructure.fs.FileSystemReviewNativeAgentPreflight
import skillbill.infrastructure.fs.FileSystemReviewRubricResolver
import skillbill.model.OptionalCallbacks
import skillbill.ports.review.ReviewAttributionPort
import skillbill.ports.review.ReviewLaunchAgentStagingPort
import skillbill.ports.review.ReviewLaunchIsolationResolver
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.ReviewSpecialistContractProvider

internal interface RuntimeReviewLaunchProvides {
  @Provides @JvmSynthetic
  fun reviewAttributionPort(adapter: FileSystemReviewAttribution): ReviewAttributionPort = adapter

  @Provides @JvmSynthetic
  fun reviewRubricResolver(adapter: FileSystemReviewRubricResolver): ReviewRubricResolver = adapter

  @Provides @JvmSynthetic
  fun reviewSpecialistContractProvider(
    adapter: ClasspathReviewSpecialistContractProvider,
  ): ReviewSpecialistContractProvider = adapter

  @Provides @JvmSynthetic
  fun reviewNativeAgentPreflightPort(
    callbacks: OptionalCallbacks,
    adapter: FileSystemReviewNativeAgentPreflight,
  ): ReviewNativeAgentPreflightPort = callbacks.reviewNativeAgentPreflight ?: adapter

  @Provides @JvmSynthetic
  fun reviewLaunchAgentStagingPort(adapter: FileSystemReviewLaunchAgentStaging): ReviewLaunchAgentStagingPort = adapter

  @Provides @JvmSynthetic
  fun reviewLaunchIsolationResolver(adapter: AgentRunReviewIsolationResolver): ReviewLaunchIsolationResolver = adapter

  @Provides @JvmSynthetic
  fun parallelCodeReviewRunnerPlanningPort(
    port: DefaultParallelCodeReviewRunnerPlanningPort,
  ): ParallelCodeReviewRunnerPlanningPort = port

  @Provides @JvmSynthetic
  fun parallelCodeReviewRunnerLaneLaunchPort(
    port: DefaultParallelCodeReviewRunnerLaneLaunchPort,
  ): ParallelCodeReviewRunnerLaneLaunchPort = port
}
