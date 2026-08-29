package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

internal data class PhaseAttemptContext(
  val run: PhaseRun,
  val state: FeatureTaskRuntimeRunState,
  val iteration: Int,
  val observability: FeatureTaskRuntimeRunObservability,
)

internal data class PhaseAttemptAccumulatorContext(
  val attempt: PhaseAttemptContext,
  val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
)

internal data class SchemaInvalidArgs(
  val operatorReason: String,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  val rejectedOutput: String? = null,
  val malformedOutput: Boolean = false,
  val retryReason: String? = null,
  val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
)

internal data class TerminalOutputAttemptArgs(
  val run: PhaseRun,
  val iteration: Int,
  val reason: String,
  val outputMap: Map<String, Any?>,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
)

internal data class UnattributableRecordRejectionArgs(
  val context: PhaseAttemptContext,
  val rejection: RecordRejection,
  val producer: String?,
)

internal data class RecordRejectionAttemptArgs(
  val context: PhaseAttemptContext,
  val priorCorrection: PriorAttemptCorrection?,
  val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
)

internal data class ProducerEvidenceRecordRejectionArgs(
  val context: PhaseAttemptContext,
  val producer: String,
  val consumer: String,
)

internal data class QuarantineRecordRejectionArgs(
  val context: PhaseAttemptContext,
  val rejection: RecordRejection,
  val regeneration: RecordRejectionRegenerationEdge,
  val producerEvidence: ProducerOutputEvidence,
)

internal data class ValidationGateCycleRequestArgs(
  val context: PhaseAttemptAccumulatorContext,
  val checkpoint: String,
)

internal data class SettleValidationGateCycleArgs(
  val context: PhaseAttemptAccumulatorContext,
  val cycle: ValidationGateCycleResult,
)

internal data class FixLoopOutcomeArgs(
  val context: PhaseAttemptAccumulatorContext,
  val loop: PhaseAttemptLoopState,
  val agentId: String,
)

internal data class LaunchPreparationRejectedArgs(
  val run: PhaseRun,
  val state: FeatureTaskRuntimeRunState,
  val classification: FeatureTaskRuntimeProjectionFailureClassification,
  val sourceLabel: String,
  val measurement: LaunchRejectionMeasurementContext,
  val message: String,
)

internal data class LaunchSeamRejectionArgs(
  val run: PhaseRun,
  val state: FeatureTaskRuntimeRunState,
  val classification: FeatureTaskRuntimeProjectionFailureClassification,
  val sourceLabel: String,
  val fallbackProducerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
)

internal data class CompletionProjectionRejectionArgs(
  val run: PhaseRun,
  val iteration: Int,
  val outputMap: Map<String, Any?>,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val repositoryFingerprint: String?,
)

internal data class PersistAcceptedOutputArgs(
  val run: PhaseRun,
  val iteration: Int,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  val repositoryFingerprint: String?,
)

internal data class PersistStandardAcceptedOutputArgs(
  val accepted: PersistAcceptedOutputArgs,
  val outputText: String,
)

internal data class BlockAndPersistArgs(
  val run: PhaseRun,
  val attemptCount: Int,
  val reason: String,
  val observability: FeatureTaskRuntimeRunObservability,
  val loopId: String?,
  val edgeIteration: Int?,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition,
  val payload: BlockAndPersistPayload,
)

internal data class BlockAndPersistPayload(
  val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  val outputArtifact: String? = null,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val rejectedOutput: String? = null,
  val childNeverLaunched: Boolean = false,
)

internal data class BlockAndPersistInPhaseArgs(
  val run: PhaseRun,
  val attemptCount: Int,
  val reason: String,
  val observability: FeatureTaskRuntimeRunObservability,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition,
  val payload: BlockAndPersistPayload,
)

internal data class RunPhaseArgs(
  val phaseId: String,
  val request: FeatureTaskRuntimeRunRequest,
  val state: FeatureTaskRuntimeRunState,
  val observability: FeatureTaskRuntimeRunObservability,
  val specSource: SpecSource,
  val reentry: PendingReentry?,
  val phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
)

internal data class ImplementFixRepairReceiptArgs(
  val run: PhaseRun,
  val outputMap: Map<String, Any?>,
  val reject: (String, String) -> AttemptResult,
  val iteration: Int,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
)

internal data class CompletedImplementationOutputArgs(
  val run: PhaseRun,
  val outputMap: Map<String, Any?>,
  val reject: (String, String) -> AttemptResult,
  val iteration: Int,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
)

internal data class CommitCheckpointArgs(
  val precedingPhaseId: String,
  val branch: String,
  val loopId: String?,
  val intent: String,
  val ownedPaths: List<String>,
  val blockedReason: (String, String) -> String,
)

internal data class RecordCheckpointIdentityArgs(
  val precedingPhaseId: String,
  val branch: String,
  val loopId: String?,
  val ownedPaths: List<String>,
  val parentSha: String?,
  val commitSha: String,
  val blockedReason: (String, String) -> String,
)

internal data class ValidationGateTriageArgs(
  val context: PhaseAttemptAccumulatorContext,
  val findings: ValidationFindingSetProjection,
)

internal data class ValidationGateRepairArgs(
  val context: PhaseAttemptAccumulatorContext,
  val findings: ValidationFindingSetProjection,
  val repairTurn: Int,
  val triagePlan: String?,
)

internal data class SettleValidatedOutputPauseArgs(
  val capture: ValidatedOutputCapture,
  val outputMap: Map<String, Any?>,
  val attested: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val repositoryFingerprint: String?,
)

internal data class LaunchCapturedArgs(
  val stdout: String,
  val stdoutBytes: ByteArray,
  val stdoutTruncated: Boolean,
  val stdoutByteSize: Long,
  val stdoutSha256: String,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
)

internal data class ReconstructFixLoopBudgetBasesArgs(
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  val edgeIterationByLoop: Map<String, Int>,
  val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val completed: Set<String>,
  val gateInvalidatedPhases: Set<String>,
  val nextIteration: (String) -> Int,
)

internal data class ProducerOutputQueryArgs(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val agentId: String,
  val dbOverride: String?,
  val generation: Int,
)

internal data class AppendCheckpointIdentityArgs(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: String,
  val branch: String,
  val phaseId: String,
  val loopId: String?,
  val generation: Int,
  val parentSha: String?,
  val ownedPaths: List<String>,
  val commitSha: String,
  val dbOverride: String?,
)

internal data class RejectedOutputTargetingArgs(
  val run: PhaseRun,
  val phaseId: String,
  val agentId: String,
  val model: String,
  val path: String,
  val repairTurn: Int,
)

internal data class SettleValidatedOutputAfterFingerprintArgs(
  val capture: ValidatedOutputCapture,
  val outputMap: Map<String, Any?>,
  val attested: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val repositoryFingerprint: String?,
  val reject: (String, String) -> AttemptResult,
)

internal data class RejectedOutputTargetingOverrides(
  val phaseId: String? = null,
  val agentId: String? = null,
  val model: String? = null,
  val path: String? = null,
  val repairTurn: Int? = null,
)

internal fun defaultRejectedOutputTargetingArgs(
  run: PhaseRun,
  overrides: RejectedOutputTargetingOverrides = RejectedOutputTargetingOverrides(),
): RejectedOutputTargetingArgs {
  val phaseId = overrides.phaseId ?: run.phaseId
  return RejectedOutputTargetingArgs(
    run = run,
    phaseId = phaseId,
    agentId = overrides.agentId ?: run.resolvedAgent.resolvedAgentId,
    model = overrides.model ?: run.modelDirective?.model ?: "unspecified",
    path = overrides.path ?: "/",
    repairTurn = overrides.repairTurn ?: if (phaseId == run.phaseId) run.validationGateRepairTurn else 0,
  )
}

internal data class PhaseBlockRequest(
  val run: PhaseRun,
  val attemptCount: Int,
  val reason: String,
  val observability: FeatureTaskRuntimeRunObservability,
  val payload: BlockAndPersistPayload = BlockAndPersistPayload(),
  val failureDisposition: FeatureTaskRuntimeFailureDisposition =
    FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
)

internal fun BlockAndPersistInPhaseArgs.withDisposition(
  failureDisposition: FeatureTaskRuntimeFailureDisposition,
): BlockAndPersistInPhaseArgs = copy(failureDisposition = failureDisposition)

internal fun phaseBlockArgs(
  run: PhaseRun,
  attemptCount: Int,
  reason: String,
  observability: FeatureTaskRuntimeRunObservability,
  payload: BlockAndPersistPayload = BlockAndPersistPayload(),
): BlockAndPersistInPhaseArgs = BlockAndPersistInPhaseArgs(
  run = run,
  attemptCount = attemptCount,
  reason = reason,
  observability = observability,
  failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  payload = payload,
)

internal fun recordRejectionAttemptArgs(
  context: PhaseAttemptContext,
  priorCorrection: PriorAttemptCorrection? = null,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
): RecordRejectionAttemptArgs = RecordRejectionAttemptArgs(
  context = context,
  priorCorrection = priorCorrection,
  phaseTokenAccumulator = phaseTokenAccumulator,
)

internal fun phaseAttemptAccumulatorContext(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  iteration: Int,
  observability: FeatureTaskRuntimeRunObservability,
  phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
): PhaseAttemptAccumulatorContext = PhaseAttemptAccumulatorContext(
  attempt = PhaseAttemptContext(run, state, iteration, observability),
  phaseTokenAccumulator = phaseTokenAccumulator,
)

internal fun FeatureTaskRuntimeRunLoop.blockInPhase(request: PhaseBlockRequest): PhaseOutcome = blockAndPersistInPhase(
  phaseBlockArgs(request.run, request.attemptCount, request.reason, request.observability, request.payload)
    .withDisposition(request.failureDisposition),
)
