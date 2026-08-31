package skillbill.cli.core

import com.github.ajalt.clikt.core.UsageError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InvokingAgentContextResolver
import skillbill.install.model.InvokingAgentContextSignal

const val SKILL_BILL_AGENT_ENV: String = "SKILL_BILL_AGENT"

fun detectInvokingAgentId(explicitAgent: String?, environment: Map<String, String>): String? =
  explicitAgent?.takeIf(String::isNotBlank)
    ?: environment[SKILL_BILL_AGENT_ENV]?.takeIf(String::isNotBlank)
    ?: InvokingAgentContextResolver.detect(environment)?.id

fun requireInvokingAgentId(explicitAgent: String?, environment: Map<String, String>, agentOption: String): String {
  val resolved = detectInvokingAgentId(explicitAgent, environment)
    ?: throw UsageError(undetectedInvokingAgentMessage(agentOption))
  return requireSupportedAgentId(resolved, invokingAgentSource(explicitAgent, agentOption))
}

/**
 * Refuse an agent id the runtime cannot launch, at the CLI boundary. Detection via execution-context
 * markers yields an [InstallAgent] id by construction, so only an operator-supplied value can name an
 * unknown agent — and without this gate that value travels as an opaque string until the runtime
 * rejects it, by which point a goal record and its planning attempt already exist.
 */
fun requireSupportedAgentId(agentId: String, source: String): String {
  val normalized = agentId.trim().lowercase()
  if (normalized !in InstallAgent.supportedIds) {
    throw UsageError(unsupportedAgentMessage(agentId, source))
  }
  return normalized
}

/**
 * Blank stays the business of the request-level identity validation that already reports it; this
 * only answers whether a supplied agent id names a supported agent.
 */
fun requireSupportedOptionalAgentId(agentId: String?, agentOption: String): String? = when {
  agentId == null || agentId.isBlank() -> agentId
  else -> requireSupportedAgentId(agentId, agentOption)
}

fun invokingAgentResolutionHelp(agentOption: String): String =
  "Agent invoking this run (${InstallAgent.supportedIds.joinToString("|")}). Resolution order: $agentOption, " +
    "then $SKILL_BILL_AGENT_ENV, then the detected invoking-agent execution context. Resolution fails when " +
    "none of the three names an agent."

private fun invokingAgentSource(explicitAgent: String?, agentOption: String): String =
  if (explicitAgent?.isNotBlank() == true) agentOption else SKILL_BILL_AGENT_ENV

private fun unsupportedAgentMessage(agentId: String, source: String): String =
  "Unknown agent '$agentId' from $source. Supported agents: ${InstallAgent.supportedIds.joinToString(", ")}."

private fun undetectedInvokingAgentMessage(agentOption: String): String =
  "Cannot determine the invoking agent, and there is no default: $agentOption was not passed, " +
    "$SKILL_BILL_AGENT_ENV is unset, and no agent execution-context marker " +
    "($invokingAgentContextMarkers) is present in this environment. " +
    "Re-run with $agentOption <agent-id> or export $SKILL_BILL_AGENT_ENV."

private val invokingAgentContextMarkers: String
  get() = InvokingAgentContextResolver.INVOKING_AGENT_CONTEXT_SIGNALS
    .flatMap(InvokingAgentContextSignal::markerKeys)
    .joinToString(", ")
