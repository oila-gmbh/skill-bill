package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject

@Inject
class FeatureTaskRuntimeRunLoopCollaborators(
  private val primary: FeatureTaskRuntimeRunLoopPrimaryCollaborators,
  private val control: FeatureTaskRuntimeRunLoopControlCollaborators,
  private val support: FeatureTaskRuntimeRunLoopSupportCollaborators,
  private val coreContinuation: FeatureTaskRuntimeRunLoopCoreContinuationCollaborators,
  private val gateContinuation: FeatureTaskRuntimeRunLoopGateContinuationCollaborators,
) {
  val drive: FeatureTaskRuntimeRunLoopDrive get() = primary.drive
  val phaseRunner: FeatureTaskRuntimeRunLoopPhaseRunner get() = primary.phaseRunner
  val phaseAttempts: FeatureTaskRuntimeRunLoopPhaseAttempts get() = primary.phaseAttempts
  val launch: FeatureTaskRuntimeRunLoopLaunch get() = primary.launch
  val outputVerification: FeatureTaskRuntimeRunLoopOutputVerification get() = primary.outputVerification
  val outputPersistence: FeatureTaskRuntimeRunLoopOutputPersistence get() = primary.outputPersistence

  val validationGate: FeatureTaskRuntimeRunLoopValidationGate get() = control.validationGate
  val review: FeatureTaskRuntimeRunLoopReview get() = control.review
  val checkpoint: FeatureTaskRuntimeRunLoopCheckpoint get() = control.checkpoint
  val planningBranch: FeatureTaskRuntimeRunLoopPlanningBranch get() = control.planningBranch
  val backwardEdge: FeatureTaskRuntimeRunLoopBackwardEdge get() = control.backwardEdge
  val attemptSettlement: FeatureTaskRuntimeRunLoopAttemptSettlement get() = control.attemptSettlement

  val recordRejection: FeatureTaskRuntimeRunLoopRecordRejection get() = support.recordRejection
  val repairReceipt: FeatureTaskRuntimeRunLoopRepairReceipt get() = support.repairReceipt
  val subtaskCommit: FeatureTaskRuntimeRunLoopSubtaskCommit get() = support.subtaskCommit
  val transitions: FeatureTaskRuntimeRunLoopTransitions get() = support.transitions

  val attemptSettlementContinued1: FeatureTaskRuntimeRunLoopAttemptSettlementPhaseOutcome
    get() = coreContinuation.settlement.attemptSettlementContinued1
  val attemptSettlementContinued2: FeatureTaskRuntimeRunLoopAttemptSettlementRepairDispatch
    get() = coreContinuation.settlement.attemptSettlementContinued2
  val attemptSettlementContinued3: FeatureTaskRuntimeRunLoopAttemptSettlementReceiptFinalize
    get() = coreContinuation.settlement.attemptSettlementContinued3

  val checkpointContinued1: FeatureTaskRuntimeRunLoopCheckpointOwnedPathRemediationEstablish
    get() = coreContinuation.checkpoint.checkpointContinued1
  val checkpointContinued2: FeatureTaskRuntimeRunLoopCheckpointRemediationRollback
    get() = coreContinuation.checkpoint.checkpointContinued2
  val checkpointContinued3: FeatureTaskRuntimeRunLoopCheckpointRemediationStage
    get() = coreContinuation.checkpoint.checkpointContinued3
  val checkpointContinued4: FeatureTaskRuntimeRunLoopCheckpointRollbackIdentity
    get() = coreContinuation.checkpoint.checkpointContinued4
  val checkpointContinued5: FeatureTaskRuntimeRunLoopCheckpointSubtaskCommitLedger
    get() = coreContinuation.checkpoint.checkpointContinued5
  val checkpointContinued6: FeatureTaskRuntimeRunLoopCheckpointBlocking
    get() = coreContinuation.checkpoint.checkpointContinued6

  val driveContinued1: FeatureTaskRuntimeRunLoopDrivePhaseSelection
    get() = coreContinuation.drive.driveContinued1
  val driveContinued2: FeatureTaskRuntimeRunLoopDriveAttemptLaunch
    get() = coreContinuation.drive.driveContinued2
  val driveContinued3: FeatureTaskRuntimeRunLoopDriveSettlementGate
    get() = coreContinuation.drive.driveContinued3
  val driveContinued4: FeatureTaskRuntimeRunLoopDriveTerminalOutcome
    get() = coreContinuation.drive.driveContinued4

  val launchContinued1: FeatureTaskRuntimeRunLoopLaunchContentIdentityParse
    get() = coreContinuation.launch.launchContinued1
  val launchContinued2: FeatureTaskRuntimeRunLoopLaunchAgentSession
    get() = coreContinuation.launch.launchContinued2
  val launchContinued3: FeatureTaskRuntimeRunLoopLaunchProcessWait
    get() = coreContinuation.launch.launchContinued3

  val outputVerificationContinued1: FeatureTaskRuntimeRunLoopOutputVerificationSchemaGate
    get() = gateContinuation.outputVerification.outputVerificationContinued1
  val outputVerificationContinued2: FeatureTaskRuntimeRunLoopOutputVerificationEnvelopeWalk
    get() = gateContinuation.outputVerification.outputVerificationContinued2
  val outputVerificationContinued3: FeatureTaskRuntimeRunLoopOutputVerificationStructuralRepair
    get() = gateContinuation.outputVerification.outputVerificationContinued3
  val outputVerificationContinued4: FeatureTaskRuntimeRunLoopOutputVerificationDuplicateKeyMerge
    get() = gateContinuation.outputVerification.outputVerificationContinued4
  val outputVerificationContinued5: FeatureTaskRuntimeRunLoopOutputVerificationReceiptAssembly
    get() = gateContinuation.outputVerification.outputVerificationContinued5

  val phaseAttemptsContinued1: FeatureTaskRuntimeRunLoopPhaseAttemptsAttemptBudget
    get() = gateContinuation.phase.phaseAttemptsContinued1
  val phaseAttemptsContinued2: FeatureTaskRuntimeRunLoopPhaseAttemptsRetrySchedule
    get() = gateContinuation.phase.phaseAttemptsContinued2
  val phaseAttemptsContinued3: FeatureTaskRuntimeRunLoopPhaseAttemptsBackoffGate
    get() = gateContinuation.phase.phaseAttemptsContinued3
  val phaseRunnerContinued1: FeatureTaskRuntimeRunLoopPhaseRunnerPhaseDispatch
    get() = gateContinuation.phase.phaseRunnerContinued1
  val phaseRunnerContinued2: FeatureTaskRuntimeRunLoopPhaseRunnerMutatingPhase
    get() = gateContinuation.phase.phaseRunnerContinued2
  val phaseRunnerContinued3: FeatureTaskRuntimeRunLoopPhaseRunnerVerifyingPhase
    get() = gateContinuation.phase.phaseRunnerContinued3

  val validationGateContinued1: FeatureTaskRuntimeRunLoopValidationGateCollectCommand
    get() = gateContinuation.validationGate.validationGateContinued1
  val validationGateContinued2: FeatureTaskRuntimeRunLoopValidationGateBuildCommand
    get() = gateContinuation.validationGate.validationGateContinued2
  val validationGateContinued3: FeatureTaskRuntimeRunLoopValidationGateSkillBillValidate
    get() = gateContinuation.validationGate.validationGateContinued3
  val validationGateContinued4: FeatureTaskRuntimeRunLoopValidationGateAgnixValidate
    get() = gateContinuation.validationGate.validationGateContinued4
}

fun featureTaskRuntimeRunLoopCollaborators(
  primary: FeatureTaskRuntimeRunLoopPrimaryCollaborators,
  control: FeatureTaskRuntimeRunLoopControlCollaborators,
  support: FeatureTaskRuntimeRunLoopSupportCollaborators,
  coreContinuation: FeatureTaskRuntimeRunLoopCoreContinuationCollaborators,
  gateContinuation: FeatureTaskRuntimeRunLoopGateContinuationCollaborators,
): FeatureTaskRuntimeRunLoopCollaborators = FeatureTaskRuntimeRunLoopCollaborators(
  primary = primary,
  control = control,
  support = support,
  coreContinuation = coreContinuation,
  gateContinuation = gateContinuation,
)
