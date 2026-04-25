package skillbill.cli

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject

@Inject
class SkillBillCommand(
  private val state: CliRunState,
  commands: TopLevelCliCommands,
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
      commands.review.importReviewCommand,
      commands.review.recordFeedbackCommand,
      commands.review.triageCommand,
      commands.review.statsCommand,
      commands.review.featureImplementStatsCommand,
      commands.review.featureVerifyStatsCommand,
      commands.learnings,
      commands.telemetry,
      commands.workflows.workflowCommand,
      commands.workflows.verifyWorkflowCommand,
      commands.version,
      commands.doctor,
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

@Inject
class TopLevelCliCommands(
  reviewCommands: ReviewTopLevelCommands,
  learningsCommand: LearningsCommand,
  telemetryCommand: TelemetryCommand,
  workflowCommands: WorkflowTopLevelCommands,
  versionCommand: VersionCommand,
  doctorCommand: DoctorCliCommand,
) {
  val review = reviewCommands
  val learnings = learningsCommand
  val telemetry = telemetryCommand
  val workflows = workflowCommands
  val version = versionCommand
  val doctor = doctorCommand
}
