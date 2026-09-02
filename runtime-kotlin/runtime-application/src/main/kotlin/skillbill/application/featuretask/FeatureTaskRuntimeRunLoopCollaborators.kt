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

  val attemptSettlementContinued1: FeatureTaskRuntimeRunLoopAttemptSettlementContinued1
    get() = coreContinuation.settlement.attemptSettlementContinued1
  val attemptSettlementContinued2: FeatureTaskRuntimeRunLoopAttemptSettlementContinued2
    get() = coreContinuation.settlement.attemptSettlementContinued2
  val attemptSettlementContinued3: FeatureTaskRuntimeRunLoopAttemptSettlementContinued3
    get() = coreContinuation.settlement.attemptSettlementContinued3

  val checkpointContinued1: FeatureTaskRuntimeRunLoopCheckpointContinued1
    get() = coreContinuation.checkpoint.checkpointContinued1
  val checkpointContinued2: FeatureTaskRuntimeRunLoopCheckpointContinued2
    get() = coreContinuation.checkpoint.checkpointContinued2
  val checkpointContinued3: FeatureTaskRuntimeRunLoopCheckpointContinued3
    get() = coreContinuation.checkpoint.checkpointContinued3
  val checkpointContinued4: FeatureTaskRuntimeRunLoopCheckpointContinued4
    get() = coreContinuation.checkpoint.checkpointContinued4
  val checkpointContinued5: FeatureTaskRuntimeRunLoopCheckpointContinued5
    get() = coreContinuation.checkpoint.checkpointContinued5
  val checkpointContinued6: FeatureTaskRuntimeRunLoopCheckpointContinued6
    get() = coreContinuation.checkpoint.checkpointContinued6

  val driveContinued1: FeatureTaskRuntimeRunLoopDriveContinued1
    get() = coreContinuation.drive.driveContinued1
  val driveContinued2: FeatureTaskRuntimeRunLoopDriveContinued2
    get() = coreContinuation.drive.driveContinued2
  val driveContinued3: FeatureTaskRuntimeRunLoopDriveContinued3
    get() = coreContinuation.drive.driveContinued3
  val driveContinued4: FeatureTaskRuntimeRunLoopDriveContinued4
    get() = coreContinuation.drive.driveContinued4

  val launchContinued1: FeatureTaskRuntimeRunLoopLaunchContinued1
    get() = coreContinuation.launch.launchContinued1
  val launchContinued2: FeatureTaskRuntimeRunLoopLaunchContinued2
    get() = coreContinuation.launch.launchContinued2
  val launchContinued3: FeatureTaskRuntimeRunLoopLaunchContinued3
    get() = coreContinuation.launch.launchContinued3

  val outputVerificationContinued1: FeatureTaskRuntimeRunLoopOutputVerificationContinued1
    get() = gateContinuation.outputVerification.outputVerificationContinued1
  val outputVerificationContinued2: FeatureTaskRuntimeRunLoopOutputVerificationContinued2
    get() = gateContinuation.outputVerification.outputVerificationContinued2
  val outputVerificationContinued3: FeatureTaskRuntimeRunLoopOutputVerificationContinued3
    get() = gateContinuation.outputVerification.outputVerificationContinued3
  val outputVerificationContinued4: FeatureTaskRuntimeRunLoopOutputVerificationContinued4
    get() = gateContinuation.outputVerification.outputVerificationContinued4
  val outputVerificationContinued5: FeatureTaskRuntimeRunLoopOutputVerificationContinued5
    get() = gateContinuation.outputVerification.outputVerificationContinued5

  val phaseAttemptsContinued1: FeatureTaskRuntimeRunLoopPhaseAttemptsContinued1
    get() = gateContinuation.phase.phaseAttemptsContinued1
  val phaseAttemptsContinued2: FeatureTaskRuntimeRunLoopPhaseAttemptsContinued2
    get() = gateContinuation.phase.phaseAttemptsContinued2
  val phaseAttemptsContinued3: FeatureTaskRuntimeRunLoopPhaseAttemptsContinued3
    get() = gateContinuation.phase.phaseAttemptsContinued3
  val phaseRunnerContinued1: FeatureTaskRuntimeRunLoopPhaseRunnerContinued1
    get() = gateContinuation.phase.phaseRunnerContinued1
  val phaseRunnerContinued2: FeatureTaskRuntimeRunLoopPhaseRunnerContinued2
    get() = gateContinuation.phase.phaseRunnerContinued2
  val phaseRunnerContinued3: FeatureTaskRuntimeRunLoopPhaseRunnerContinued3
    get() = gateContinuation.phase.phaseRunnerContinued3

  val validationGateContinued1: FeatureTaskRuntimeRunLoopValidationGateContinued1
    get() = gateContinuation.validationGate.validationGateContinued1
  val validationGateContinued2: FeatureTaskRuntimeRunLoopValidationGateContinued2
    get() = gateContinuation.validationGate.validationGateContinued2
  val validationGateContinued3: FeatureTaskRuntimeRunLoopValidationGateContinued3
    get() = gateContinuation.validationGate.validationGateContinued3
  val validationGateContinued4: FeatureTaskRuntimeRunLoopValidationGateContinued4
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
