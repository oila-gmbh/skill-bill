package skillbill.ports.review.model

import skillbill.review.context.model.ReviewIntegrationTerminalOutcome

/** Durable integration-pass boundary: which sequence it covered and how it ended. */
data class ReviewIntegrationPassRecord(
  val commitSequenceDigest: String,
  val terminalOutcome: ReviewIntegrationTerminalOutcome,
) {
  constructor(commitSequenceDigest: String, terminalOutcome: String) : this(
    commitSequenceDigest = commitSequenceDigest,
    terminalOutcome = requireNotNull(ReviewIntegrationTerminalOutcome.entries.firstOrNull { it.wireValue == terminalOutcome }) {
      "Unknown review integration terminal outcome '$terminalOutcome'."
    },
  )

  init {
    require(commitSequenceDigest.isNotBlank()) { "Integration pass record must name its commit sequence." }
  }
}
