package skillbill.cli.models

internal data class ImportReviewArgs(
  val input: String,
  val format: CliFormat,
)

internal data class RecordFeedbackArgs(
  val runId: String,
  val event: String,
  val findings: List<String>,
  val note: String,
  val format: CliFormat,
)

internal data class TriageRequest(
  val runId: String,
  val decisions: List<String>,
  val listOnly: Boolean,
  val format: CliFormat,
)

internal data class ReviewStatsArgs(
  val runId: String?,
  val format: CliFormat,
)
