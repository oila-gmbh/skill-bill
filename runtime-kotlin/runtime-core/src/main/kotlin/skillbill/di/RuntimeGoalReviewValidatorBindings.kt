package skillbill.di

import skillbill.infrastructure.fs.AgentRunReviewIsolationResolver
import skillbill.infrastructure.fs.FileSystemFeatureSpecPathResolver
import skillbill.infrastructure.fs.FileSystemReviewEvidenceBrokerFactory
import skillbill.infrastructure.fs.GoalObservabilityEventValidatorAdapter
import skillbill.infrastructure.fs.GoalPlanningPreparationEnvelopeValidatorAdapter
import skillbill.infrastructure.fs.GoalProgressEventValidatorAdapter
import skillbill.infrastructure.fs.ReviewContextEnvelopeValidatorAdapter
import skillbill.launcher.review.UnixSocketGovernedReviewEvidenceEndpointBinder
import skillbill.ports.featurespec.FeatureSpecPathResolverPort
import skillbill.ports.review.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.ReviewEvidenceBrokerFactory
import skillbill.ports.review.ReviewLaunchIsolationResolver
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.goal.GoalProgressEventValidator

internal object RuntimeGoalReviewValidatorBindings {
  internal fun goalPlanningPreparationEnvelopeValidator(
    adapter: GoalPlanningPreparationEnvelopeValidatorAdapter,
  ): GoalPlanningPreparationEnvelopeValidator = adapter

  internal fun reviewContextEnvelopeValidator(
    adapter: ReviewContextEnvelopeValidatorAdapter,
  ): ReviewContextEnvelopeValidator = adapter

  internal fun reviewEvidenceBrokerFactory(
    adapter: FileSystemReviewEvidenceBrokerFactory,
  ): ReviewEvidenceBrokerFactory = adapter

  internal fun governedReviewEvidenceEndpointBinder(
    adapter: UnixSocketGovernedReviewEvidenceEndpointBinder,
  ): GovernedReviewEvidenceEndpointBinder = adapter

  internal fun reviewLaunchIsolationResolver(adapter: AgentRunReviewIsolationResolver): ReviewLaunchIsolationResolver =
    adapter

  internal fun featureSpecPathResolverPort(adapter: FileSystemFeatureSpecPathResolver): FeatureSpecPathResolverPort =
    adapter

  internal fun goalObservabilityEventValidator(
    adapter: GoalObservabilityEventValidatorAdapter,
  ): GoalObservabilityEventValidator = adapter

  // SKILL-64 Subtask 3: declared goal-progress event schema validator port,
  // bound to the infra-fs adapter that owns the networknt JSON-Schema check.
  // The goal-runner outcome store calls this port at the durable
  // declared-progress write seam, mirroring goalObservabilityEventValidator.

  internal fun goalProgressEventValidator(adapter: GoalProgressEventValidatorAdapter): GoalProgressEventValidator =
    adapter
}
