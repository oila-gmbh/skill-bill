package skillbill.engine.featuretask.validation

import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.engine.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.validation.model.ValidationGateCyclePhase
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.ValidationGateCacheMode

internal fun validationGateArgv(
  declaration: ValidationGateDeclaration,
  cyclePhase: ValidationGateCyclePhase,
): List<String> = when (cyclePhase) {
  ValidationGateCyclePhase.INITIAL_DISCOVERY -> declaration.collectAllFullGateCommand
  ValidationGateCyclePhase.POST_REPAIR_VERIFY -> declaration.cacheBypassingCollectAllFullGateCommand
}

internal fun validationGateCommand(
  declaration: ValidationGateDeclaration,
  cyclePhase: ValidationGateCyclePhase,
  gradleWrapper: String?,
): String = applyValidationGateGradleWrapper(
  validationGateArgv(declaration, cyclePhase),
  gradleWrapper,
).joinToString(" ")

internal fun requiredValidationGateCyclePhase(
  progress: FeatureTaskRuntimeValidationGateProgress?,
): ValidationGateCyclePhase = if (
  progress?.gateRuns?.lastOrNull()?.cacheMode == ValidationGateCacheMode.FORCED_FULL
) {
  ValidationGateCyclePhase.POST_REPAIR_VERIFY
} else {
  ValidationGateCyclePhase.INITIAL_DISCOVERY
}

internal fun requiredValidationGateCommand(
  declaration: ValidationGateDeclaration,
  gradleWrapper: String?,
  progress: FeatureTaskRuntimeValidationGateProgress?,
): String = validationGateCommand(
  declaration,
  requiredValidationGateCyclePhase(progress),
  gradleWrapper,
)

internal fun durableValidationChangedPaths(
  recorder: FeatureTaskRuntimePhaseRecorder,
  workflowId: String,
): List<String>? {
  val checkpointPaths = recorder.loadPhaseBriefings(workflowId)
    ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    ?.handoffEnvelope
    ?.repositoryCheckpoint
    ?.workingTreeOwnedPaths
  if (checkpointPaths != null) {
    return checkpointPaths.filter(String::isNotBlank).distinct().sorted()
  }
  return recorder.loadResolvedBranch(workflowId)
    ?.workflowOwnedPaths
    ?.filter(String::isNotBlank)
    ?.distinct()
    ?.sorted()
}

internal fun resolveRequiredValidationCommand(
  resolver: ValidationGateResolver,
  requiredCommandForDeclaration: (ValidationGateDeclaration) -> String,
  changedPaths: List<String>?,
  evidence: FeatureTaskRuntimeValidationEvidence?,
  sourceLabel: String,
): String? {
  val resolution = resolver.resolve(changedPaths.orEmpty())
  if (changedPaths == null && resolution is ValidationGateResolution.Declared) {
    throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
      sourceLabel,
      "validation changed-path inventory is missing for a declared validation gate.",
    )
  }
  return when (resolution) {
    is ValidationGateResolution.Declared -> requiredCommandForDeclaration(resolution.declaration)
    is ValidationGateResolution.Absent -> evidence?.results?.lastOrNull()?.command
    is ValidationGateResolution.Incompatible ->
      throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(sourceLabel, resolution.reason)
  }
}
