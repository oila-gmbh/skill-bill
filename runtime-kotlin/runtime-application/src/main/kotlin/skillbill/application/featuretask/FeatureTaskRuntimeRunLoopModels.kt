package skillbill.application.featuretask

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.config.model.PhaseCompactionDirective
import skillbill.config.model.PhaseModelDirective
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

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

internal data class PhaseAttemptLoopCarryForward(
  var priorCorrection: PriorAttemptCorrection? = null,
  var priorUnaccountedFindings: Set<String>? = null,
  var priorUnresolvedFindings: Set<String> = emptySet(),
  var itemCoverageSegmentCount: Int = 0,
)

internal class PhaseAttemptLoopState(
  var iteration: Int,
  var malformedAttemptCount: Int,
  var outputGateFailures: Int,
  var semanticIteration: Int,
  var continuationSegmentCount: Int,
  var carryForward: PhaseAttemptLoopCarryForward = PhaseAttemptLoopCarryForward(),
) {
  var priorCorrection: PriorAttemptCorrection?
    get() = carryForward.priorCorrection
    set(value) {
      carryForward.priorCorrection = value
    }
  var priorUnaccountedFindings: Set<String>?
    get() = carryForward.priorUnaccountedFindings
    set(value) {
      carryForward.priorUnaccountedFindings = value
    }
  var priorUnresolvedFindings: Set<String>
    get() = carryForward.priorUnresolvedFindings
    set(value) {
      carryForward.priorUnresolvedFindings = value
    }
  var itemCoverageSegmentCount: Int
    get() = carryForward.itemCoverageSegmentCount
    set(value) {
      carryForward.itemCoverageSegmentCount = value
    }
}

internal data class CapturedPhaseOutput(
  val text: String,
  val bytes: ByteArray,
  val truncated: Boolean,
  val byteSize: Long,
  val sha256: String,
) {
  companion object {
    fun fromBytes(bytes: ByteArray, text: String = bytes.decodeToString()): CapturedPhaseOutput {
      val byteSize = bytes.size.toLong()
      return CapturedPhaseOutput(
        text = text,
        bytes = bytes,
        truncated = false,
        byteSize = byteSize,
        sha256 = RejectedOutputDiagnosticService.sha256(bytes),
      )
    }

    fun fromLaunchCaptured(captured: LaunchResult.Captured): CapturedPhaseOutput = CapturedPhaseOutput(
      text = captured.stdout,
      bytes = captured.stdoutBytes,
      truncated = captured.stdoutTruncated,
      byteSize = captured.stdoutByteSize,
      sha256 = captured.stdoutSha256,
    )
  }
}

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
  val captured: CapturedPhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
) {
  val outputText: String get() = captured.text
  val outputBytes: ByteArray get() = captured.bytes
  val outputTruncated: Boolean get() = captured.truncated
  val outputByteSize: Long get() = captured.byteSize
  val outputSha256: String get() = captured.sha256
}

internal data class RejectedOutputTargeting(
  val phaseId: String,
  val agentId: String,
  val model: String,
  val path: String,
  val repairTurn: Int,
)

internal data class RecordRejectedOutputArgs(
  val run: PhaseRun,
  val iteration: Int,
  val rule: String,
  val reason: String,
  val captured: CapturedPhaseOutput,
  val targeting: RejectedOutputTargeting,
)

internal data class CorrectiveRepairRejectionDetail(
  val rule: String,
  val path: String,
  val payloadFreeConstraint: String,
  val acceptedAfterStructuralRepair: Boolean = false,
  val structuralRepairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
)

internal data class CorrectiveRepairRejectionArgs(
  val run: PhaseRun,
  val iteration: Int,
  val captured: CapturedPhaseOutput,
  val diagnosticWrite: FeatureTaskRuntimeRejectedOutputWrite,
  val rejection: CorrectiveRepairRejectionDetail,
)

internal data class GateOutputArgs(
  val run: PhaseRun,
  val iteration: Int,
  val captured: CapturedPhaseOutput,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
)

internal data class SettledOutputContext(
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  val captured: CapturedPhaseOutput,
)

internal data class SettleValidatedOutputArgs(
  val run: PhaseRun,
  val iteration: Int,
  val output: SettledOutputContext,
)

internal data class PhaseStateWriteArgs(
  val run: PhaseRun,
  val iteration: Int,
  val status: String,
  val finished: Boolean,
  val outputArtifact: String?,
)

internal data class PhaseStateRequestExtras(
  val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val repositoryFingerprint: String? = null,
  val launched: LaunchedModelDirective? = null,
  val reviewRunId: String? = null,
)

internal data class PhaseStateRequestArgs(
  val write: PhaseStateWriteArgs,
  val extras: PhaseStateRequestExtras = PhaseStateRequestExtras(),
)

internal data class PersistPhaseArgs(
  val write: PhaseStateWriteArgs,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  val launched: LaunchedModelDirective? = null,
  val reviewRunId: String? = null,
)

internal data class PhaseReviewPersistenceArgs(
  val run: PhaseRun,
  val iteration: Int,
  val observability: FeatureTaskRuntimeRunObservability,
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
