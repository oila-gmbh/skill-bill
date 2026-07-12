package skillbill.cli.core

import com.github.ajalt.clikt.core.UsageError
import skillbill.config.model.PhaseModelDirective
import skillbill.install.model.MODEL_DIRECTIVE_CAPABLE_AGENTS
import skillbill.install.model.supportsModelDirective

fun refuseUnsupportedModelDirectives(
  directivesByPhase: Map<String, PhaseModelDirective>,
  resolvedAgentIdByPhase: Map<String, String>,
) {
  directivesByPhase.entries.firstOrNull { (phaseId, _) ->
    !supportsModelDirective(resolvedAgentIdByPhase.getValue(phaseId))
  }?.let { (phaseId, directive) ->
    val agentId = resolvedAgentIdByPhase.getValue(phaseId)
    throw UsageError(
      "--phase-model/execution_matrix: phase '$phaseId' resolves to agent '$agentId', which cannot honor a " +
        "model/effort directive (${directiveText(directive)}). Capable agents: " +
        MODEL_DIRECTIVE_CAPABLE_AGENTS.joinToString(", ") { it.id } + ".",
    )
  }
}

private fun directiveText(directive: PhaseModelDirective): String = buildString {
  append("model=")
  append(directive.model)
  directive.effort?.let {
    append(", effort=")
    append(it)
  }
}
