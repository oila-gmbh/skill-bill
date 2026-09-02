package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseGateBranchPort
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseGateValidationPort

@Inject
class FeatureTaskRuntimePhaseGates(
  branchPort: FeatureTaskRuntimePhaseGateBranchPort,
  validationPort: FeatureTaskRuntimePhaseGateValidationPort,
) {
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner = branchPort.branchSetupRunner
  val planningStopper: FeatureTaskRuntimePlanningStopper = branchPort.planningStopper
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry = branchPort.lifecycleTelemetry
  val gitOperations = branchPort.gitOperations
  val specGate: FeatureTaskRuntimeSpecGate = branchPort.specGate
  val planningProjectionValidator = validationPort.planningProjectionValidator
  val buildReceiptValidator = validationPort.buildReceiptValidator
  val validationGateResolver = validationPort.validationGateResolver
  val validationGateRunner = validationPort.validationGateRunner
  val validationGateCoordinator = validationPort.validationGateCoordinator
  val buildGateCoordinator = validationPort.buildGateCoordinator
  val sharedEvidenceResolver = validationPort.sharedEvidenceResolver
  val diffResolver = validationPort.diffResolver
  val reviewDriver = validationPort.reviewDriver
  val specIntentProjectionResolver = validationPort.specIntentProjectionResolver
  val findingVerificationBoundaryMemory = validationPort.findingVerificationBoundaryMemory
}
