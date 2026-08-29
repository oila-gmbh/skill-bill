package skillbill.application.featuretask

import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.toProjectionPayload
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseCompactionDirective
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.model.ReviewFindingVerdict
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQualityGateRouting
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_NO_PROGRESS
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgressDecision
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.ReviewPassResolution
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.boundPriorGapNotes
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.validateDispositionCoverage
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.application.review.model.DiffResolutionException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.time.Instant
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.review.model.StackDetectionException
import skillbill.application.goalrunner.StructuredGoalReviewFinding
import skillbill.workflow.taskruntime.model.UNPROVEN_REPOSITORY_FINGERPRINT
import skillbill.error.UnreadableSpecIntentProjectionError
import skillbill.application.review.model.UsageValidationException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus


internal data class RemediationCheckpointCommit(val commitSha: String, val parentSha: String?)

internal data class SubtaskCommitLedgerState(val commitSha: String?, val nextSequenceNumber: Int)

internal sealed interface PhaseSettlement {
  data object Stopped : PhaseSettlement
  data class Completed(val phaseId: String, val verdict: FeatureTaskRuntimeVerdict) : PhaseSettlement

  val completedPhaseId: String? get() = (this as? Completed)?.phaseId
  val completedVerdict: FeatureTaskRuntimeVerdict? get() = (this as? Completed)?.verdict

  companion object {
    fun stop(): PhaseSettlement = Stopped
    fun completed(phaseId: String, verdict: FeatureTaskRuntimeVerdict): PhaseSettlement = Completed(phaseId, verdict)
  }
}

internal data class PendingReentry(
  val phaseId: String,
  val loopId: String,
  val edgeIteration: Int,
  val drivingVerdict: FeatureTaskRuntimeVerdict,
  val reentryGapCriteria: List<String> = emptyList(),
  val expectedRepositoryCheckpoint: String? = null,
)

internal class MissingCarriedForwardGoalReviewResultException : IllegalStateException()

internal sealed class RuntimeOwnedReviewPrep

internal data class RuntimeOwnedReviewReady(
  val run: PhaseRun,
  val launch: RuntimeOwnedReviewLaunch,
  val driverRequest: ParallelCodeReviewRequest,
) : RuntimeOwnedReviewPrep()

internal data class RuntimeOwnedReviewBlocked(val outcome: PhaseOutcome) : RuntimeOwnedReviewPrep()

internal data class RuntimeOwnedReviewLaunch(
  val iteration: Int,
  val passNumber: Int,
  val resolvedTier: CodeReviewExecutionMode,
  val reviewRunId: String,
  val checkpoint: String,
)

internal sealed class ReviewDriverAttempt

internal data class ReviewDriverReady(val result: ParallelCodeReviewResult) : ReviewDriverAttempt()

internal data class ReviewDriverFailed(
  val reason: String,
  val disposition: FeatureTaskRuntimeFailureDisposition =
    FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
) : ReviewDriverAttempt()

internal class PhaseAttemptLoopState(
  var iteration: Int,
  var malformedAttemptCount: Int,
  var outputGateFailures: Int,
  var semanticIteration: Int,
  var continuationSegmentCount: Int,
  var priorCorrection: PriorAttemptCorrection? = null,
  var priorUnaccountedFindings: Set<String>? = null,
  var priorUnresolvedFindings: Set<String> = emptySet(),
  var itemCoverageSegmentCount: Int = 0,
)

internal data class FixLoopBranchContext(
  val run: PhaseRun,
  val attempt: AttemptResult,
  val loop: PhaseAttemptLoopState,
  val observability: FeatureTaskRuntimeRunObservability,
  val agentId: String,
)

internal class ValidatedOutputCapture(
  val run: PhaseRun,
  val iteration: Int,
  val outputText: String,
  val outputBytes: ByteArray,
  val outputTruncated: Boolean,
  val outputByteSize: Long,
  val outputSha256: String,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
)

internal sealed interface CommitPushFinalisation

internal data object CommitPushNotApplicable : CommitPushFinalisation

internal data class CommitPushSettled(
  val output: NormalizedFeatureTaskRuntimePhaseOutput,
) : CommitPushFinalisation

internal data class CommitPushBlocked(val reason: String) : CommitPushFinalisation

internal data class CheckpointRevisions(
  val base: String?,
  val head: String,
)

internal sealed interface BoundaryBodyDeliveryDecision {
  data object NotApplicable : BoundaryBodyDeliveryDecision
  class ContinueDecision private constructor(val reason: String) : BoundaryBodyDeliveryDecision {
    companion object {
      fun of(reason: String) = ContinueDecision(reason)
    }
  }

  class RejectDecision private constructor(val reason: String) : BoundaryBodyDeliveryDecision {
    companion object {
      fun of(reason: String) = RejectDecision(reason)
    }
  }
}

internal data class LaunchedModelDirective(
  val modelOverride: String?,
  val effortOverride: String?,
  val persistedEffort: String?,
)

internal data class LaunchRejectionMeasurementContext(
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
)

internal sealed interface LaunchPreparation

internal data class PreparedLaunchReady(val value: PreparedLaunch) : LaunchPreparation

internal data class LaunchMeasurementContextReady(
  val value: LaunchRejectionMeasurementContext,
) : LaunchPreparation

internal data class ClosedCriterionRefsReady(val value: List<String>) : LaunchPreparation

internal data class LaunchPreparationRejected(val result: LaunchResult) : LaunchPreparation

internal data class PhaseRun(
  val phaseId: String,
  val declaration: FeatureTaskRuntimePhaseDeclaration,
  val resolvedAgent: FeatureTaskRuntimeResolvedPhaseAgent,
  val modelDirective: PhaseModelDirective?,
  val compaction: PhaseCompactionDirective?,
  val request: FeatureTaskRuntimeRunRequest,
  val specSource: SpecSource,
  val reentry: PendingReentry? = null,
  val goalReviewInput: GoalSubtaskReviewInput? = null,
  val validationGateFindings: ValidationFindingSetProjection? = null,
  val validationGateTriagePlan: String? = null,
  val validationGateRepair: Boolean = false,
  val validationGateTriage: Boolean = false,
  /** True only when validate falls back because the pack declares no validation_gate. */
  val agentRunValidateFallback: Boolean = false,
  /**
   * 1-based ordinal of the validation-gate repair turn this launch is, zero outside a repair cycle.
   * A repair cycle deliberately re-runs an agent under one unchanged phase attempt, so this is the
   * only thing separating one turn's retained evidence and diagnostics from the next turn's.
   */
  val validationGateRepairTurn: Int = 0,
)

internal data class PreLaunchBlock(
  val attemptCount: Int,
  val reason: String,
  val durableRecord: FeatureTaskRuntimePhaseRecord? = null,
)

internal data class PreparedLaunch(
  val briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  val prompt: String,
)

internal data class RecordRejection(val rejectionClass: String, val rejectionDetail: String)

internal data class RepairReceiptAnchor(val baseSha: String, val roundNumber: Int)

internal enum class FindingsOwedKind { OMITTED, UNRESOLVED }

internal sealed interface RepairReceiptSettlement {
  data class Rejected(val detail: String) : RepairReceiptSettlement
  data class WriteFailed(val reason: String) : RepairReceiptSettlement

  data object None : RepairReceiptSettlement

  val rejectionDetail: String? get() = (this as? Rejected)?.detail
  val writeFailureReason: String? get() = (this as? WriteFailed)?.reason

  companion object {
    fun rejected(detail: String): RepairReceiptSettlement = Rejected(detail)
    fun writeFailed(reason: String): RepairReceiptSettlement = WriteFailed(reason)
  }
}

