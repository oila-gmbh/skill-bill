package skillbill.cli.core

import com.github.ajalt.clikt.core.UsageError
import skillbill.config.model.PhaseModelDirective
import skillbill.config.model.ProviderProfile
import skillbill.install.model.InstallAgent
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

/**
 * Refuses profile-bearing directives before any phase launches. Runs on the session-selected
 * matrix's directives. A directive naming an undeclared profile, referencing a profile whose
 * `auth_token_env` variable is absent from the runtime environment, or resolving to any agent other
 * than claude fails as a [UsageError]. No resolved token value ever appears in a message.
 */
fun refuseUnresolvableProfileDirectives(
  directivesByPhase: Map<String, PhaseModelDirective>,
  resolvedAgentIdByPhase: Map<String, String>,
  profiles: Map<String, ProviderProfile>,
  environment: Map<String, String>,
) {
  directivesByPhase.entries.forEach { (phaseId, directive) ->
    requireDeclaredProfile(phaseId, directive, profiles, environment)
    val resolvedAgentId = resolvedAgentIdByPhase.getValue(phaseId)
    if (resolvedAgentId != InstallAgent.CLAUDE.id) {
      throw UsageError(
        "--phase-model/execution_matrix: phase '$phaseId' resolves to agent '$resolvedAgentId', but provider " +
          "profiles apply to claude only.",
      )
    }
  }
}

private fun requireDeclaredProfile(
  phaseId: String,
  directive: PhaseModelDirective,
  profiles: Map<String, ProviderProfile>,
  environment: Map<String, String>,
) {
  val profileName = directive.profile ?: return
  val declaredProfiles = profiles.keys.sorted()
  if (profileName !in profiles) {
    throw UsageError(
      "--phase-model/execution_matrix: phase '$phaseId' references provider profile '$profileName', which is " +
        "not a declared provider_profiles entry. Declared profiles: " +
        (if (declaredProfiles.isEmpty()) "none" else declaredProfiles.joinToString(", ")) + ".",
    )
  }
  val profile = profiles.getValue(profileName)
  profile.authTokenEnv?.let { variable ->
    if (variable !in environment) {
      throw UsageError(
        "--phase-model/execution_matrix: phase '$phaseId' references provider profile '$profileName', whose " +
          "auth_token_env variable '$variable' is not present in the runtime environment.",
      )
    }
  }
}

private fun directiveText(directive: PhaseModelDirective): String = buildString {
  append("model=")
  append(directive.model)
  directive.effort?.let {
    append(", effort=")
    append(it)
  }
  directive.profile?.let {
    append(", profile=")
    append(it)
  }
}
