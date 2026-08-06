package skillbill.ports.persistence.model

/** Durable integration-pass boundary: which sequence it covered and how it ended. */
data class ReviewIntegrationPassRecord(
  val commitSequenceDigest: String,
  val terminalOutcome: String,
) {
  init {
    require(commitSequenceDigest.isNotBlank()) { "Integration pass record must name its commit sequence." }
    require(terminalOutcome.isNotBlank()) { "Integration pass record must name its terminal outcome." }
  }
}
