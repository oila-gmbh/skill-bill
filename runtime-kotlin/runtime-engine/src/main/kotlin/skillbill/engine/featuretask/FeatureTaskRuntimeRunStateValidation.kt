package skillbill.engine.featuretask

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.ValidationEvidencePayloadKeys
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal data class ValidationSettlementState(
  val completed: MutableSet<String>,
  val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  val gateInvalidatedPhases: MutableSet<String>,
)

internal data class ValidationSettlementValidation(
  val validatedRecordToOutput: (FeatureTaskRuntimePhaseRecord) -> FeatureTaskRuntimePhaseOutput?,
  val validationEvidenceCommandResolver: (FeatureTaskRuntimeValidationEvidence?) -> String?,
  val durableVerdictFor: (String) -> FeatureTaskRuntimeVerdict,
)

internal fun invalidateIncompleteValidationSettlement(
  state: ValidationSettlementState,
  validation: ValidationSettlementValidation,
) {
  if (FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE !in state.completed) return
  val record = state.initialRecords[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE] ?: return
  val valid = runCatching {
    val output = validation.validatedRecordToOutput(record) ?: return@runCatching false
    val produced = JsonCodec.anyToStringAnyMap(
      output.normalizedOutput?.envelope?.get(SharedPayloadKeys.PRODUCED_OUTPUTS),
    )
    val result = JsonCodec.anyToStringAnyMap(
      produced?.get(ValidationEvidencePayloadKeys.VALIDATION_RESULT),
    )
    val evidence = JsonCodec.anyToStringAnyMap(
      result?.get(ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE),
    )?.let { raw ->
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(
        raw,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      )
    }
    val requiredCommand = validation.validationEvidenceCommandResolver(evidence)
    if (requiredCommand != null) {
      evidence?.requireSuccessfulCommand(
        requiredCommand,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      ) ?: return@runCatching false
    }
    true
  }.getOrDefault(false)
  if (!valid) {
    state.completed.remove(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    state.gateInvalidatedPhases += FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
    FeatureTaskRuntimeRunStateReconstruction.invalidateUnsatisfiedGateSuccessors(
      state.transitions,
      state.completed,
      state.gateInvalidatedPhases,
      validation.durableVerdictFor,
    )
  }
}
