package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.model.FeatureTaskRuntimeResolvedPhaseAgent

/**
 * Pure per-phase agent resolver. The run-wide override is independent and wins at the launch
 * seam; the invoked side resolves in order:
 *
 *   per-phase map entry -> invoking agent id (always-present default)
 *
 * The invoking agent id is already resolved upstream (its own `--agent` / `SKILL_BILL_AGENT` /
 * detected-context / default order is applied at the CLI boundary) and is required non-blank
 * here, so it is the always-present default; there is no separate env layer below it.
 */
object FeatureTaskRuntimeAgentResolver {
  /**
   * Resolves the effective agent for [phaseId]. A non-blank per-phase map entry wins; otherwise
   * the [invokedAgentId] (the run's already-resolved invoking agent, required non-blank) is used.
   */
  fun resolve(
    phaseId: String,
    assignment: FeatureTaskRuntimeAgentAssignment,
    invokedAgentId: String,
  ): FeatureTaskRuntimeResolvedPhaseAgent {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeAgentResolver.resolve requires a non-blank phaseId." }
    require(invokedAgentId.isNotBlank()) {
      "FeatureTaskRuntimeAgentResolver.resolve requires a non-blank invokedAgentId; the invoking agent is the " +
        "documented default and must always be present (no hardcoded codex fallback)."
    }
    val resolvedInvoked = assignment.perPhaseAgentIds[phaseId]?.takeIf(String::isNotBlank)
      ?: invokedAgentId
    return FeatureTaskRuntimeResolvedPhaseAgent(
      phaseId = phaseId,
      invokedAgentId = resolvedInvoked,
      configuredAgentOverrideId = assignment.override?.takeIf(String::isNotBlank),
    )
  }
}
