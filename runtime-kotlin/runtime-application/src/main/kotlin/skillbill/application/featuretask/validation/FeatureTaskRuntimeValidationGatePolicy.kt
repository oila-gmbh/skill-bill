package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateCyclePhase
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.model.ValidationDepth

internal fun validationGateArgv(
  declaration: ValidationGateDeclaration,
  validationDepth: ValidationDepth,
  cyclePhase: ValidationGateCyclePhase,
): List<String> = when (cyclePhase) {
  ValidationGateCyclePhase.INITIAL_DISCOVERY ->
    if (validationDepth == ValidationDepth.BUILD_ONLY) {
      declaration.buildOnlyCommand
    } else {
      declaration.collectAllFullGateCommand
    }
  ValidationGateCyclePhase.POST_REPAIR_VERIFY ->
    if (validationDepth == ValidationDepth.BUILD_ONLY) {
      declaration.cacheBypassingFullGateCommand
    } else {
      declaration.cacheBypassingCollectAllFullGateCommand
    }
}
