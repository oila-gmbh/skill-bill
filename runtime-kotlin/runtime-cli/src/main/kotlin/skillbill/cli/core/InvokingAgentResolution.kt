package skillbill.cli.core

import com.github.ajalt.clikt.core.UsageError
import skillbill.install.model.InvokingAgentContextResolver
import skillbill.install.model.InvokingAgentContextSignal

const val SKILL_BILL_AGENT_ENV: String = "SKILL_BILL_AGENT"

fun detectInvokingAgentId(explicitAgent: String?, environment: Map<String, String>): String? =
  explicitAgent?.takeIf(String::isNotBlank)
    ?: environment[SKILL_BILL_AGENT_ENV]?.takeIf(String::isNotBlank)
    ?: InvokingAgentContextResolver.detect(environment)?.id

fun requireInvokingAgentId(explicitAgent: String?, environment: Map<String, String>, agentOption: String): String =
  detectInvokingAgentId(explicitAgent, environment)
    ?: throw UsageError(undetectedInvokingAgentMessage(agentOption))

fun invokingAgentResolutionHelp(agentOption: String): String =
  "Agent invoking this run. Resolution order: $agentOption, then $SKILL_BILL_AGENT_ENV, then the detected " +
    "invoking-agent execution context. Resolution fails when none of the three names an agent."

private fun undetectedInvokingAgentMessage(agentOption: String): String =
  "Cannot determine the invoking agent, and there is no default: $agentOption was not passed, " +
    "$SKILL_BILL_AGENT_ENV is unset, and no agent execution-context marker " +
    "($invokingAgentContextMarkers) is present in this environment. " +
    "Re-run with $agentOption <agent-id> or export $SKILL_BILL_AGENT_ENV."

private val invokingAgentContextMarkers: String
  get() = InvokingAgentContextResolver.INVOKING_AGENT_CONTEXT_SIGNALS
    .flatMap(InvokingAgentContextSignal::markerKeys)
    .joinToString(", ")
