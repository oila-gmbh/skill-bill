package skillbill.application.featuretask

import skillbill.agent.model.AgentId

import skillbill.application.featuretask.model.FeatureTaskRuntimeModelAssignment
import skillbill.config.model.PhaseModelDirective

object FeatureTaskRuntimeModelResolver {
  fun resolve(
    phaseId: String,
    resolvedAgentId: AgentId,
    assignment: FeatureTaskRuntimeModelAssignment,
  ): PhaseModelDirective? = assignment.perPhaseDirectives[phaseId]
    ?: assignment.matrix?.directiveFor(resolvedAgentId, phaseId)
}
