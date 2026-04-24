package skillbill.cli

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject

@Inject
class SkillBillCommand(
  private val state: CliRunState,
  reviewCommands: ReviewTopLevelCommands,
  learningsCommand: LearningsCommand,
  telemetryCommand: TelemetryCommand,
  versionCommand: VersionCommand,
  doctorCommand: DoctorCliCommand,
) : DocumentedCliCommand(
  "skill-bill",
  "Import Skill Bill review output, triage findings, manage learnings, and inspect telemetry.",
) {
  private val dbOverride by option(
    "--db",
    help = "Optional SQLite path. Defaults to SKILL_BILL_DB or the standard local state path.",
  )

  init {
    completionOption()
    subcommands(
      reviewCommands.importReviewCommand,
      reviewCommands.recordFeedbackCommand,
      reviewCommands.triageCommand,
      reviewCommands.statsCommand,
      reviewCommands.featureImplementStatsCommand,
      reviewCommands.featureVerifyStatsCommand,
      learningsCommand,
      telemetryCommand,
      versionCommand,
      doctorCommand,
    )
  }

  override fun aliases(): Map<String, List<String>> = mapOf(
    "feature-implement-stats" to listOf("implement-stats"),
    "feature-verify-stats" to listOf("verify-stats"),
  )

  override fun run() {
    state.dbOverride = dbOverride
  }
}
