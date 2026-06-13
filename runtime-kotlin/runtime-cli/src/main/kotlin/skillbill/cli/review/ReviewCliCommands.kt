package skillbill.cli.review

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.review.ReviewService
import skillbill.cli.core.CliOutput
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.formatOption
import skillbill.cli.core.toCliNumberedFindingsPresentation
import skillbill.cli.core.toCliTriagePresentation
import skillbill.cli.model.CliFormat

@Inject
class FeatureStatsCommands(
  val featureImplementStatsCommand: FeatureImplementStatsCommand,
  val featureVerifyStatsCommand: FeatureVerifyStatsCommand,
  val featureTaskStatsCommand: FeatureTaskStatsCommand,
  val featureTaskRuntimeStatsCommand: FeatureTaskRuntimeStatsCommand,
  val goalStatsCommand: GoalStatsCommand,
) {
  val commands =
    listOf(
      featureImplementStatsCommand,
      featureVerifyStatsCommand,
      featureTaskStatsCommand,
      featureTaskRuntimeStatsCommand,
      goalStatsCommand,
    )
}

@Inject
class ReviewTopLevelCommands(
  val importReviewCommand: ImportReviewCommand,
  val recordFeedbackCommand: RecordFeedbackCommand,
  val triageCommand: TriageCommand,
  val statsCommand: ReviewStatsCommand,
  val featureStatsCommands: FeatureStatsCommands,
) {
  val commands =
    listOf(
      importReviewCommand,
      recordFeedbackCommand,
      triageCommand,
      statsCommand,
    ) + featureStatsCommands.commands
}

@Inject
class ImportReviewCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("import-review", "Import a review output file or stdin into the local SQLite store.") {
  private val input by argument(help = "Path to review text, or '-' for stdin.").default("-")
  private val format by formatOption()

  override fun run() {
    state.complete(service.importReview(input, state.dbOverride).toCliMap(), format)
  }
}

@Inject
class RecordFeedbackCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("record-feedback", "Record explicit feedback events for findings in an imported review run.") {
  private val runId by option("--run-id", help = "Imported review run id.").required()
  private val event by option("--event", help = "Canonical finding outcome to record.").required()
  private val findings by option("--finding", help = "Finding id to update. Repeat for multiple findings.").multiple(
    required = true,
  )
  private val note by option("--note", help = "Optional note for the recorded feedback event.").default("")
  private val format by formatOption()

  override fun run() {
    state.complete(service.recordFeedback(runId, event, findings, note, state.dbOverride).toCliMap(), format)
  }
}

@Inject
class TriageCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "triage",
  "Show numbered findings for a review run and record triage decisions by number.",
) {
  private val runId by option("--run-id", help = "Imported review run id.").required()
  private val decisions by option(
    "--decision",
    help = "Triage entry like '1 fix', '2 skip - intentional', or '3 accept - good catch'.",
  ).multiple()
  private val listOnly by option("--list", help = "Show numbered findings without recording decisions.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    val result = service.triage(runId, decisions, listOnly, state.dbOverride)
    val payload = result.toCliMap()
    when {
      format == CliFormat.JSON -> state.complete(payload, format)
      result.findings.isNotEmpty() -> state.completeText(
        CliOutput.numberedFindings(result.toCliNumberedFindingsPresentation(runId)),
        payload,
      )
      else -> state.completeText(CliOutput.triageResult(result.toCliTriagePresentation(runId)), payload)
    }
  }
}

@Inject
class ReviewStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("stats", "Show aggregate or per-run review acceptance metrics.") {
  private val runId by option("--run-id", help = "Optional review run id to scope stats to one review.")
  private val format by formatOption()

  override fun run() {
    state.complete(service.reviewStats(runId, state.dbOverride).toCliMap(), format)
  }
}

@Inject
class FeatureImplementStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("implement-stats", "Show aggregate bill-feature-task metrics.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.featureImplementStats(state.dbOverride).toCliMap(), format)
  }
}

@Inject
class FeatureVerifyStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("verify-stats", "Show aggregate bill-feature-verify metrics.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.featureVerifyStats(state.dbOverride).toCliMap(), format)
  }
}

@Inject
class FeatureTaskStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("feature-task-stats", "Show aggregate feature-task metrics.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.featureTaskRuntimeStats(state.dbOverride).toCliMap(), format)
  }
}

@Inject
class FeatureTaskRuntimeStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand(
  "runtime-stats",
  "Deprecated alias for feature-task-stats. Use feature-task-stats; behavior is unchanged.",
) {
  override val hiddenFromHelp: Boolean = true

  private val format by formatOption()

  override fun run() {
    state.liveStderr(
      "runtime-stats is a deprecated alias for feature-task-stats. " +
        "Use feature-task-stats; behavior is unchanged.\n",
    )
    state.complete(service.featureTaskRuntimeStats(state.dbOverride).toCliMap(), format)
  }
}

@Inject
class GoalStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("goal-stats", "Show aggregate decomposed-goal run metrics.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.goalStats(state.dbOverride).toCliMap(), format)
  }
}
