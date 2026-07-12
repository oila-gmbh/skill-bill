package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeModelAssignment
import skillbill.config.model.PhaseModelDirective

object FeatureTaskRuntimeModelResolver {
  fun resolve(
    phaseId: String,
    resolvedAgentId: String,
    assignment: FeatureTaskRuntimeModelAssignment,
  ): PhaseModelDirective? = assignment.perPhaseDirectives[phaseId]
    ?: assignment.matrix?.directiveFor(resolvedAgentId, phaseId)
}
