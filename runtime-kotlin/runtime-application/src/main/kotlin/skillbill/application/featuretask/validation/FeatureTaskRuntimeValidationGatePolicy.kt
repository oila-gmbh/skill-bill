package skillbill.application.featuretask.validation

import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.model.ValidationDepth

internal fun validationGateArgv(
  declaration: ValidationGateDeclaration,
  validationDepth: ValidationDepth,
): List<String> = if (validationDepth == ValidationDepth.BUILD_ONLY) {
  declaration.buildOnlyCommand
} else {
  declaration.collectAllFullGateCommand
}
