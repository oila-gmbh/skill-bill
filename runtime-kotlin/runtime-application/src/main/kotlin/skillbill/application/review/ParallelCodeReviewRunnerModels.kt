package skillbill.application.review

import skillbill.application.evidence.SharedReviewEvidenceCommits
import skillbill.application.featuretask.RuntimeOwnedPersistenceBoundary
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ReviewDelegatedStageLaunch
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.review.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.NativeReviewOperationProtocol
import skillbill.ports.review.ReviewEvidenceBroker
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.review.model.ParallelReviewLaneRunResult
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.GovernedReviewLaunch
import skillbill.review.context.model.LANE_EVIDENCE_BYTES_DIMENSION
import skillbill.review.context.model.ResolvedReviewExecutionMode
import skillbill.review.context.model.ReviewAssignment
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.ReviewContextPacket
import skillbill.review.context.model.ReviewLaneAssembledBundle
import skillbill.review.context.model.ReviewLaneCompletionState
import skillbill.review.context.model.ReviewLaneReviewDisposition
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.context.model.asFailedLaneRun
import skillbill.review.context.model.withBrokerEvidenceRefusal
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewStageResumeReport
import skillbill.scaffold.model.PlatformManifest

internal data class ParallelCodeReviewRunnerPlanningDeps(
  val diffResolver: DiffResolverPort,
  val repoLocalConfig: RepoLocalConfigPort,
  val reviewContextEnvelopeValidator: ReviewContextEnvelopeValidator,
  val reviewSpecialistContractProvider: ReviewSpecialistContractProvider,
  val installedPackCatalog: InstalledPlatformPackCatalogPort,
  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort,
  val sharedEvidenceLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort,
  val specIntentProjectionResolver: SpecIntentProjectionResolver,
  val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  val rubricPlanning: ParallelCodeReviewRunnerRubricPlanning,
)

internal data class LaunchParentLaneArgs(
  val agentId: String,
  val launchRequests: List<ReviewSpecialistLaunchRequest>,
  val routedManifests: List<PlatformManifest>,
  val budget: ReviewContextBudgetPolicy,
  val request: ParallelCodeReviewRequest,
  val modelOverride: String?,
  val resolvedMode: ResolvedReviewExecutionMode,
)

internal data class LaunchedBoundParentArgs(
  val launch: ParallelCodeReviewInlineParentLaunch,
  val bound: ParallelCodeReviewGovernedEvidenceBind.Bound,
  val budget: ReviewContextBudgetPolicy,
  val request: ParallelCodeReviewRequest,
  val modelOverride: String?,
  val resolvedMode: ResolvedReviewExecutionMode,
)

internal data class ParallelResultArgs(
  val agent1Id: String,
  val outcomes: ParallelReviewLaneRunResult,
  val integration: ReviewIntegrationPassOutcome,
  val coverage: ReviewCoverageReport?,
  val packet: ReviewContextPacket?,
  val budget: ReviewContextBudgetPolicy,
  val stageResume: ReviewStageResumeReport?,
)

internal data class PlanningPrepareArgs(
  val request: ParallelCodeReviewRequest,
  val revisions: Pair<String, String>,
  val diffText: String,
  val evidence: ReviewDiffEvidence,
  val sharedSequence: SharedReviewEvidenceCommits,
  val routedManifests: List<PlatformManifest>,
  val manifests: List<PlatformManifest>,
  val ownedPathsBySlug: Map<String, Set<String>>,
  val agentIds: List<String>,
  val budget: ReviewContextBudgetPolicy,
  val evidenceStorePath: String?,
)

internal data class ParallelCodeReviewInitialRun(
  val request: ParallelCodeReviewRequest,
  val detection: ParallelCodeReviewStackDetection,
  val resolvedMode: ResolvedReviewExecutionMode,
  val agent1Id: String,
  val preparedLaunchRequests: List<ReviewSpecialistLaunchRequest>,
  val compiledLaunchRequests: List<ReviewSpecialistLaunchRequest>,
  val budget: ReviewContextBudgetPolicy,
  val specIntentResolution: SpecIntentResolution,
)

internal fun ParallelCodeReviewInitialRun.delegatedStageLaunch(): ReviewDelegatedStageLaunch =
  ReviewDelegatedStageLaunch(
    budget = budget,
    brokerId = agent1Id,
    repoRoot = request.repoRoot,
    timeout = request.timeout,
    promptSuffix = request.selectedAgentAddonsSection,
  )

internal data class ParallelCodeReviewCompiledLaunches(
  val all: List<ReviewSpecialistLaunchRequest>,
  val toRun: List<ReviewSpecialistLaunchRequest>,
  val specIntentResolution: SpecIntentResolution,
)

internal data class ParallelCodeReviewStackDetection(
  val routed: List<PlatformManifest>,
  val manifests: List<PlatformManifest>,
  val ownedPathsBySlug: Map<String, Set<String>>,
)

internal data class ParallelCodeReviewSoftRegisterAdmission(
  val findings: List<ParallelReviewRawFinding>,
  val droppedCandidateDiagnostic: String?,
  val rejectedCandidateCount: Int,
)

internal class ParallelCodeReviewInlineParentLaunch(
  val agentId: String,
  val selected: List<ReviewSpecialistLaunchRequest>,
  val prompt: String,
  val bundleState: ReviewLaneCompletionState,
) {
  val assignment: ReviewAssignment get() = selected.first().assignment
}

internal sealed class ParallelCodeReviewGovernedEvidenceBind {
  class Bound(
    val broker: ReviewEvidenceBroker,
    val protocol: NativeReviewOperationProtocol,
    val endpoint: GovernedReviewEvidenceEndpointHandle,
  ) : ParallelCodeReviewGovernedEvidenceBind()

  class Unbound(
    val seam: String,
    val fault: ParallelCodeReviewGovernedEvidenceBindFault,
  ) : ParallelCodeReviewGovernedEvidenceBind()
}

internal enum class ParallelCodeReviewGovernedEvidenceBindFault(val wireValue: String) {
  CONSTRUCTION("construction"),
  PROTOCOL("protocol"),
  ENDPOINT("endpoint"),
}

internal fun parallelCodeReviewGovernedLaunchFor(request: ReviewSpecialistLaunchRequest): GovernedReviewLaunch =
  GovernedReviewLaunch(
    assignment = request.assignment,
    packet = request.packet,
    specialistContract = request.specialistContract,
    rubric = request.rubrics.first().body,
    brokerId = request.brokerId,
    budget = request.budget,
  )

internal fun parallelCodeReviewEffectiveCompletionState(
  launch: ReviewSpecialistLaunchRequest,
  outcomes: ParallelReviewLaneRunResult,
): ReviewLaneCompletionState {
  val governed = parallelCodeReviewGovernedLaunchFor(launch)
  val runCompletion = if (outcomes.lane1.success) {
    governed.completionState
  } else {
    governed.completionState.asFailedLaneRun(
      governed.assembledBundle.entries.map { "${it.commitSha}@${it.hunk.path}" },
    )
  }
  val assignedUnits = governed.assembledBundle.entries
    .map { "${it.commitSha}@${it.hunk.path}" }
    .toSet()
  val deniedUnits = listOf(outcomes.lane1)
    .flatMap { outcome ->
      val fromAccounting = (outcome.specialistAccounting + listOfNotNull(outcome.accounting))
        .filter { it.budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION }
        .flatMap { it.unreviewedUnits }
      val fromOutcome = outcome.takeIf { it.budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION }
        ?.unreviewedUnits
        .orEmpty()
      fromAccounting + fromOutcome
    }
    .filter { it in assignedUnits }
    .distinct()
  return if (deniedUnits.isEmpty()) {
    runCompletion
  } else {
    runCompletion.withBrokerEvidenceRefusal(deniedUnits)
  }
}

internal fun parallelCodeReviewBrokerEvidenceCompletionState(
  completion: ReviewLaneCompletionState,
  accounting: ReviewLaneAccounting,
): ReviewLaneCompletionState = if (accounting.budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION) {
  completion.withBrokerEvidenceRefusal(accounting.unreviewedUnits)
} else {
  completion
}

internal fun parallelCodeReviewAggregateBundleCompletion(
  states: List<ReviewLaneCompletionState>,
): ReviewLaneCompletionState {
  if (states.isEmpty()) {
    return ReviewLaneCompletionState(
      disposition = ReviewLaneReviewDisposition.COMPLETE,
      bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
      segments = emptyList(),
    )
  }
  val incomplete = states.filter { it.disposition == ReviewLaneReviewDisposition.INCOMPLETE }
  if (incomplete.isEmpty()) {
    return states.first()
  }
  return ReviewLaneCompletionState(
    disposition = ReviewLaneReviewDisposition.INCOMPLETE,
    bundleCompositionDigest = incomplete.first().bundleCompositionDigest,
    segments = incomplete.flatMap { it.segments }.distinctBy { it.segmentId },
    unreviewedSegmentIds = incomplete.flatMap { it.unreviewedSegmentIds }.distinct(),
    unreviewedUnits = incomplete.flatMap { it.unreviewedUnits }.distinct(),
    budgetDimension = incomplete.first().budgetDimension,
  )
}

internal const val PARALLEL_REVIEW_STDERR_EXCERPT_MAX_LENGTH = 120
internal const val PARALLEL_REVIEW_REGISTER_ABSENCE_EXCERPT_MAX_LENGTH = 800
internal const val PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES = 1_000_000L
internal const val PARALLEL_REVIEW_FIRST_SOURCE_LINE = 1
internal const val PARALLEL_REVIEW_HEAD_REVISION = "HEAD"
internal const val PARALLEL_REVIEW_SHARED_EVIDENCE_WORKFLOW_ID = "code-review"
internal const val PARALLEL_REVIEW_INLINE_NATIVE_WORKER = "bill-code-review-inline"
internal const val PARALLEL_REVIEW_NO_SEQUENCE_DIGEST = "no-commit-sequence"
internal const val PARALLEL_REVIEW_NO_FINDINGS_TOKEN = "NO_FINDINGS"

internal const val NO_OP_RESUME_TERMINAL_STATUS: String = "no_op_resume"
internal const val UNSUPPORTED_PROVIDER_TERMINAL_STATUS: String = "unsupported_provider"
internal const val INLINE_FINDING_PARSE_SEAM: String = "attributeInlineFindings"

internal const val PARALLEL_REVIEW_INLINE_DEPTH_DIRECTIVE: String =
  "Merge every routed rubric above into one combined checklist, then traverse the diff exactly " +
    "once against it at reduced depth in this agent context, holding all rubrics in mind " +
    "simultaneously, and do not launch specialists. Never re-walk the diff once per rubric. " +
    "Write free-form prose findings. Optional register lines are best-effort verification hints."

internal const val PARALLEL_REVIEW_DELEGATED_DEPTH_DIRECTIVE: String =
  "Assign each routed rubric above to its own specialist worker over that rubric's owned paths. " +
    "Accept each specialist's raw return as-is with no shape check. Synthesize the final review " +
    "prose and verdict yourself from those returns."
