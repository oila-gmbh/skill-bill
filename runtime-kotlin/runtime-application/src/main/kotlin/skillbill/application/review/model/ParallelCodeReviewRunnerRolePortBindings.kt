package skillbill.application.review.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.SpecIntentProjectionResolver
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewLaunchAgentStagingPort
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.model.ParallelReviewParseResult
import java.time.Clock

@Inject
data class DefaultParallelCodeReviewRunnerPlanningPort(
  override val diffResolver: DiffResolverPort,
  override val repoLocalConfig: RepoLocalConfigPort,
  override val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  override val reviewRubricResolver: ReviewRubricResolver,
  override val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  override val database: DatabaseSessionFactory,
  override val installedPackCatalog: InstalledPlatformPackCatalogPort,
  override val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  override val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort,
  override val specIntentProjectionResolver: SpecIntentProjectionResolver,
  override val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  override val nativeAgentPreflight: ReviewNativeAgentPreflightPort,
  override val registerParse: (String) -> ParallelReviewParseResult,
  override val diagnostics: RuntimeDiagnostics,
  override val clock: Clock,
) : ParallelCodeReviewRunnerPlanningPort

@Inject
data class DefaultParallelCodeReviewRunnerLaneLaunchPort(
  override val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  override val reviewEvidenceBrokerFactory: ReviewEvidenceBrokerFactory,
  override val governedEvidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder,
  override val reviewLaunchAgentStaging: ReviewLaunchAgentStagingPort,
  override val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort,
) : ParallelCodeReviewRunnerLaneLaunchPort
