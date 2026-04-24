package skillbill.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.ReviewService

@Inject
class ReviewTopLevelCommands(
  val importReviewCommand: ImportReviewCommand,
  val recordFeedbackCommand: RecordFeedbackCommand,
  val triageCommand: TriageCommand,
  val statsCommand: ReviewStatsCommand,
  val featureImplementStatsCommand: FeatureImplementStatsCommand,
  val featureVerifyStatsCommand: FeatureVerifyStatsCommand,
)

@Inject
class ImportReviewCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("import-review", "Import a review output file or stdin into the local SQLite store.") {
  private val input by argument(help = "Path to review text, or '-' for stdin.").default("-")
  private val format by formatOption()

  override fun run() {
    state.complete(service.importReview(input, state.dbOverride), format)
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
    state.complete(service.recordFeedback(runId, event, findings, note, state.dbOverride), format)
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
    when {
      format == CliFormat.JSON -> state.complete(result.payload, format)
      result.findings.isNotEmpty() -> state.completeText(
        CliOutput.numberedFindings(runId, result.findings),
        result.payload,
      )
      else -> state.completeText(CliOutput.triageResult(runId, result.recorded), result.payload)
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
    state.complete(service.reviewStats(runId, state.dbOverride), format)
  }
}

@Inject
class FeatureImplementStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("implement-stats", "Show aggregate bill-feature-implement metrics.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.featureImplementStats(state.dbOverride), format)
  }
}

@Inject
class FeatureVerifyStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("verify-stats", "Show aggregate bill-feature-verify metrics.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.featureVerifyStats(state.dbOverride), format)
  }
}
