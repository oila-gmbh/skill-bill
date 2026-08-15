package skillbill.application.featuretask.validation

import skillbill.workflow.model.ValidationDepth

internal fun validationGateArgv(
  declaration: skillbill.scaffold.model.ValidationGateDeclaration,
  validationDepth: ValidationDepth,
  cacheMode: skillbill.ports.validation.model.ValidationGateCacheMode,
): List<String> {
  if (validationDepth == ValidationDepth.BUILD_ONLY) {
    return if (cacheMode == skillbill.ports.validation.model.ValidationGateCacheMode.FORCED_FULL) {
      declaration.buildOnlyCommand + validationGateCacheBypassExtraArgs(declaration)
    } else {
      declaration.buildOnlyCommand
    }
  }
  return validationGateCollectAllArgv(declaration, cacheMode)
}

internal fun validationGateCollectAllArgv(
  declaration: skillbill.scaffold.model.ValidationGateDeclaration,
  cacheMode: skillbill.ports.validation.model.ValidationGateCacheMode,
): List<String> =
  when (cacheMode) {
    skillbill.ports.validation.model.ValidationGateCacheMode.FORCED_FULL ->
      declaration.cacheBypassingCollectAllFullGateCommand
    skillbill.ports.validation.model.ValidationGateCacheMode.CACHE_ELIGIBLE ->
      declaration.collectAllFullGateCommand
  }

internal fun validationGateCacheBypassExtraArgs(
  declaration: skillbill.scaffold.model.ValidationGateDeclaration,
): List<String> {
  val full = declaration.fullGateCommand
  val bypass = declaration.cacheBypassingFullGateCommand
  if (bypass.size > full.size && bypass.take(full.size) == full) {
    return bypass.drop(full.size)
  }
  return bypass.filter { token -> token.startsWith("-") && token !in full }
}
