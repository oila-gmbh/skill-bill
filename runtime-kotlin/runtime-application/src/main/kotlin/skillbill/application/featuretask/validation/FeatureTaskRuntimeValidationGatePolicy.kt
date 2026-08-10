package skillbill.application.featuretask.validation

import skillbill.workflow.model.ValidationDepth

/** Repair-cycle cap distinct from [skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy]. */
const val MAX_VALIDATE_GATE_REPAIR_ITERATIONS: Int = 3

internal fun validationGateArgv(
  declaration: skillbill.scaffold.model.ValidationGateDeclaration,
  validationDepth: ValidationDepth,
  cacheMode: skillbill.ports.validation.model.ValidationGateCacheMode,
): List<String> = when {
  validationDepth == ValidationDepth.BUILD_ONLY -> declaration.buildOnlyCommand
  cacheMode == skillbill.ports.validation.model.ValidationGateCacheMode.FORCED_FULL ->
    declaration.cacheBypassingFullGateCommand
  else -> declaration.fullGateCommand
}
