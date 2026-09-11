package skillbill.engine.featuretask

import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.FeatureTaskRuntimeModelAssignment

object FeatureTaskRuntimeModelResolver {
  fun resolve(
    phaseId: String,
    resolvedAgentId: String,
    assignment: FeatureTaskRuntimeModelAssignment,
  ): PhaseModelDirective? = assignment.perPhaseDirectives[phaseId]
    ?: assignment.matrix?.directiveFor(resolvedAgentId, phaseId)
}
