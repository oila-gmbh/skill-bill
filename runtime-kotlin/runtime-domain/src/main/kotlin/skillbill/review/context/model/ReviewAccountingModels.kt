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
  val terminalOutcome: String = "completed",
  val bundleCompositionDigest: String? = null,
  val segmentAccounting: List<ReviewLaneSegmentAccounting> = emptyList(),
  val unreviewedSegmentIds: List<String> = emptyList(),
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
  val terminalOutcome: String,
  /** Bundle composition this lane actually reviewed, so result records preserve it. */
  val bundleCompositionDigest: String?,
  val segmentAccounting: List<ReviewLaneSegmentAccounting>,
  val unreviewedSegmentIds: List<String>,
  val children: List<ReviewAccountingNode>,
)

/**
 * Commit-sequence identity plus the routing shape the parent decided before any worker launched.
 * Counting focused/skipped at both the commit and the commit-lane-pair level keeps two different
 * questions answerable: how much of the sequence was reviewed at all, and how sparse the fan-out was.
 */
data class ReviewCommitRoutingAccounting(
  val commitSequenceDigest: String,
  val routingDigest: String,
  val commitCount: Int,
  val laneCount: Int,
  val focusedCommitCount: Int,
  val skippedCommitCount: Int,
  val focusedPairCount: Int,
  val skippedPairCount: Int,
  val incompleteLanes: List<String> = emptyList(),
) {
  init {
    require(commitSequenceDigest.isNotBlank() && routingDigest.isNotBlank())
    require(commitCount >= 1) { "A routed review covers at least one commit." }
    require(
      listOf(laneCount, focusedCommitCount, skippedCommitCount, focusedPairCount, skippedPairCount)
        .all { it >= 0 },
    )
    require(focusedCommitCount + skippedCommitCount == commitCount) {
      "Every commit is either focused by some lane or skipped by all of them."
    }
  }
}

/** What the parent's own relevance analysis consumed against its configured ceilings. */
data class ReviewParentAnalysisConsumption(
  val analyzedPairs: Int,
  val analyzedBytes: Long,
  val maxAnalysisPairs: Int,
  val maxAnalysisBytes: Long,
) {
  init {
    require(analyzedPairs >= 0 && analyzedBytes >= 0)
    require(maxAnalysisPairs >= 1 && maxAnalysisBytes >= 1)
  }
}

/** Terminal state of the single integration pass, attributed to the sequence it covered. */
data class ReviewIntegrationAccounting(
  val commitSequenceDigest: String,
  val terminalOutcome: String,
  val summarizedLaneCount: Int,
  val findingCount: Int,
  val counters: ReviewAccountingCounters = ReviewAccountingCounters(),
  val skipReason: String? = null,
) {
  init {
    require(commitSequenceDigest.isNotBlank() && terminalOutcome.isNotBlank())
    require(summarizedLaneCount >= 0 && findingCount >= 0)
    if (terminalOutcome == SKIPPED_NOT_APPLICABLE) {
      require(!skipReason.isNullOrBlank()) { "A skipped integration pass must record why." }
    }
  }

  companion object {
    const val SKIPPED_NOT_APPLICABLE: String = "skipped_not_applicable"
  }
}

data class ReviewAccountingSummary(
  val reviewId: String,
  val packetDigest: String,
  val parent: ReviewAccountingNode,
  val lanes: List<ReviewAccountingNode>,
  val aggregateCounters: ReviewAccountingCounters,
  val commitRouting: ReviewCommitRoutingAccounting? = null,
  val parentAnalysis: ReviewParentAnalysisConsumption? = null,
  /** Null only when no integration state was settled at all, never as a stand-in for "clean". */
  val integration: ReviewIntegrationAccounting? = null,
)
