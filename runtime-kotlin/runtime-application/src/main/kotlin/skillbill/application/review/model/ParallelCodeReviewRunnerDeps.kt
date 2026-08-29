package skillbill.application.review.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.SpecIntentProjectionResolver
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
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
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.model.ParallelReviewParseResult

@Inject
data class ParallelCodeReviewRunnerDeps(
  val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  val diffResolver: DiffResolverPort,
  val repoLocalConfig: RepoLocalConfigPort,
  val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  val reviewRubricResolver: ReviewRubricResolver,
  val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  val database: DatabaseSessionFactory,
  val installedPackCatalog: InstalledPlatformPackCatalogPort = InstalledPlatformPackCatalogPort.NONE,
  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    FeatureTaskRuntimeSharedEvidenceResolverPort.NONE,
  val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort =
    FeatureTaskRuntimeSharedEvidenceLocatorReadPort.NONE,
  val specIntentProjectionResolver: SpecIntentProjectionResolver,
  val reviewEvidenceBrokerFactory: ReviewEvidenceBrokerFactory,
  val governedEvidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder,
  val nativeAgentPreflight: ReviewNativeAgentPreflightPort = ReviewNativeAgentPreflightPort.NONE,
  val reviewLaunchAgentStaging: ReviewLaunchAgentStagingPort = ReviewLaunchAgentStagingPort.NONE,
  val registerParse: (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse,
  val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
)
