package skillbill.application.featuretask.model

import skillbill.agent.model.AgentId

import skillbill.application.featuretask.FeatureTaskRuntimeAgentResolver

/**
 * Static per-phase agent assignment: a design-time phase-id-to-agent map plus an optional run-wide
 * [override] that wins at the launch seam. A running agent cannot choose its own assignment; the
 * map is a property of the run request. Resolution order lives in
 * [skillbill.application.FeatureTaskRuntimeAgentResolver].
 */
data class FeatureTaskRuntimeAgentAssignment(
  val perPhaseAgentIds: Map<String, AgentId> = emptyMap(),
  val override: String? = null,
) {
  init {
    perPhaseAgentIds.forEach { (phaseId, agentId) ->
      require(phaseId.isNotBlank()) {
        "FeatureTaskRuntimeAgentAssignment.perPhaseAgentIds must not contain a blank phase id."
      }
      require(agentId.value.isNotBlank()) {
        "FeatureTaskRuntimeAgentAssignment.perPhaseAgentIds['$phaseId'] must not map to a blank agent id."
      }
    }
    override?.let { value ->
      require(value.isNotBlank()) {
        "FeatureTaskRuntimeAgentAssignment.override must not be blank when provided."
      }
    }
  }
}

/** The resolved effective agent ids for one phase. */
data class FeatureTaskRuntimeResolvedPhaseAgent(
  val phaseId: String,
  val invokedAgentId: AgentId,
  val configuredAgentOverrideId: String?,
) {
  /** The agent that actually executes the phase: the override when present, else the invoked. */
  val resolvedAgentId: AgentId = configuredAgentOverrideId?.let(::AgentId) ?: invokedAgentId

  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeResolvedPhaseAgent.phaseId must be non-blank." }
    require(invokedAgentId.value.isNotBlank()) { "FeatureTaskRuntimeResolvedPhaseAgent.invokedAgentId must be non-blank." }
    configuredAgentOverrideId?.let { value ->
      require(value.isNotBlank()) {
        "FeatureTaskRuntimeResolvedPhaseAgent.configuredAgentOverrideId must not be blank when provided."
      }
    }
  }
}
