package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateCyclePhase
import skillbill.scaffold.model.ValidationGateDeclaration

internal fun validationGateArgv(
  declaration: ValidationGateDeclaration,
  cyclePhase: ValidationGateCyclePhase,
): List<String> = when (cyclePhase) {
  ValidationGateCyclePhase.INITIAL_DISCOVERY -> declaration.collectAllFullGateCommand
  ValidationGateCyclePhase.POST_REPAIR_VERIFY -> declaration.cacheBypassingCollectAllFullGateCommand
}
