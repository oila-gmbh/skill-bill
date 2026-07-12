package skillbill.application.model

import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.PhaseModelDirective

data class FeatureTaskRuntimeModelAssignment(
  val perPhaseDirectives: Map<String, PhaseModelDirective> = emptyMap(),
  val matrix: ExecutionMatrix? = null,
) {
  init {
    perPhaseDirectives.forEach { (phaseId, directive) ->
      require(phaseId.isNotBlank()) {
        "FeatureTaskRuntimeModelAssignment.perPhaseDirectives must not contain a blank phase id."
      }
      require(directive.model.isNotBlank()) {
        "FeatureTaskRuntimeModelAssignment.perPhaseDirectives['$phaseId'] must not contain a blank model."
      }
    }
  }
}
