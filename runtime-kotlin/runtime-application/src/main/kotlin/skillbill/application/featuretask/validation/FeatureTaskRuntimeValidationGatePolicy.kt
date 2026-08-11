package skillbill.application.featuretask.validation

import skillbill.workflow.model.ValidationDepth

/** Repair-cycle cap distinct from [skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy]. */
const val MAX_VALIDATE_GATE_REPAIR_ITERATIONS: Int = 3

internal fun validationGateArgv(
  declaration: skillbill.scaffold.model.ValidationGateDeclaration,
  validationDepth: ValidationDepth,
  cacheMode: skillbill.ports.validation.model.ValidationGateCacheMode,
): List<String> {
  // BUILD_ONLY must keep compile/build argv, but the terminal FORCED_FULL verifier still
  // needs the pack's cache-bypass extras; otherwise an immediately-repeated build-only
  // command reports 0 executed work and is rejected as a cache-served no-op.
  if (validationDepth == ValidationDepth.BUILD_ONLY) {
    return if (cacheMode == skillbill.ports.validation.model.ValidationGateCacheMode.FORCED_FULL) {
      declaration.buildOnlyCommand + validationGateCacheBypassExtraArgs(declaration)
    } else {
      declaration.buildOnlyCommand
    }
  }
  return when (cacheMode) {
    skillbill.ports.validation.model.ValidationGateCacheMode.FORCED_FULL ->
      declaration.cacheBypassingFullGateCommand
    skillbill.ports.validation.model.ValidationGateCacheMode.CACHE_ELIGIBLE ->
      declaration.fullGateCommand
  }
}

/**
 * Extra argv tokens the pack adds for a cache-bypassing full gate relative to
 * [skillbill.scaffold.model.ValidationGateDeclaration.fullGateCommand].
 * Used to force attestation on BUILD_ONLY terminal runs without switching to the full gate.
 */
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
