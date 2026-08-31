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
data class ParallelCodeReviewRunnerDeps(
  val parentReviewLauncher: GoalRunnerSubtaskLauncher,
  val diffResolver: DiffResolverPort,
  val repoLocalConfig: RepoLocalConfigPort,
  val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  val reviewRubricResolver: ReviewRubricResolver,
  val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  val database: DatabaseSessionFactory,
  val installedPackCatalog: InstalledPlatformPackCatalogPort,
  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort,
  val specIntentProjectionResolver: SpecIntentProjectionResolver,
  val reviewEvidenceBrokerFactory: ReviewEvidenceBrokerFactory,
  val governedEvidenceEndpointBinder: GovernedReviewEvidenceEndpointBinder,
  val nativeAgentPreflight: ReviewNativeAgentPreflightPort,
  val reviewLaunchAgentStaging: ReviewLaunchAgentStagingPort,
  val registerParse: (String) -> ParallelReviewParseResult,
  val diagnostics: RuntimeDiagnostics,
  val clock: Clock,
)
