package skillbill.review.context.model

data class ReviewAccountingCounters(
  val launchBytes: Long = 0,
  val evidenceBytes: Long = 0,
  val resultBytes: Long = 0,
  val expansions: Int = 0,
  val toolCalls: Int = 0,
  val modelTurns: Int = 0,
) {
  init {
    require(listOf(launchBytes, evidenceBytes, resultBytes).all { it >= 0 })
    require(listOf(expansions, toolCalls, modelTurns).all { it >= 0 })
  }

  operator fun plus(other: ReviewAccountingCounters): ReviewAccountingCounters = ReviewAccountingCounters(
    launchBytes + other.launchBytes,
    evidenceBytes + other.evidenceBytes,
    resultBytes + other.resultBytes,
    expansions + other.expansions,
    toolCalls + other.toolCalls,
    modelTurns + other.modelTurns,
  )
}

data class ReviewAccountingInput(
  val lane: String,
  val assignmentDigest: String,
  val counters: ReviewAccountingCounters = ReviewAccountingCounters(),
  val usage: ProviderTokenUsage = ProviderTokenUsage(),
  val terminalOutcome: String = "completed",
  val children: List<ReviewAccountingInput> = emptyList(),
) {
  init {
    require(lane.isNotBlank() && assignmentDigest.isNotBlank())
  }
}

data class ReviewAccountingNode(
  val lane: String,
  val assignmentDigest: String,
  /** Counters owned by this session alone. */
  val counters: ReviewAccountingCounters,
  /** This session's counters plus every descendant's, each counted once. */
  val inclusiveCounters: ReviewAccountingCounters,
  /** The provider report exactly as observed, including its ownership declaration. */
  val providerUsage: ProviderTokenUsage,
  val directUsage: ProviderTokenUsage,
  val inclusiveUsage: ProviderTokenUsage,
  val terminalOutcome: String,
  val children: List<ReviewAccountingNode>,
)

data class ReviewAccountingSummary(
  val reviewId: String,
  val packetDigest: String,
  val parent: ReviewAccountingNode,
  val lanes: List<ReviewAccountingNode>,
  val aggregateCounters: ReviewAccountingCounters,
  val aggregateDirectUsage: ProviderTokenUsage,
  val aggregateInclusiveUsage: ProviderTokenUsage,
  val budgetRegression: Boolean,
)
