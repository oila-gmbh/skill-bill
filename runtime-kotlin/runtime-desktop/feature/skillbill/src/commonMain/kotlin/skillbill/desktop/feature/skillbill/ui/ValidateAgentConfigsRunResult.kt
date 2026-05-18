package skillbill.desktop.feature.skillbill.ui

/**
 * SKILL-46 AC8: result of invoking `scripts/validate_agent_configs` after a successful deletion.
 */
data class ValidateAgentConfigsRunResult(
  val outputLines: List<String>,
  val exitCode: Int,
)
