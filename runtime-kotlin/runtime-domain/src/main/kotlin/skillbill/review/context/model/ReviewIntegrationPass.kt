package skillbill.review.context.model

/**
 * What one finished specialist lane reports upward to the integration pass. It is a summary by
 * construction: identity, coverage disposition, and bounded prose. No hunk bodies, no rubric, and
 * no transcript, so a lane's raw evidence cannot reach the integration worker through a sibling.
 */
data class ReviewSpecialistSummary(
  val lane: String,
  val assignmentDigest: String,
  val disposition: ReviewLaneReviewDisposition,
  val assignedPaths: List<String>,
  val commitShas: List<String>,
  val findingCount: Int,
  val unreviewedSegmentIds: List<String> = emptyList(),
  val unreviewedUnits: List<String> = emptyList(),
  val summary: String = "",
) {
  init {
    require(lane.isNotBlank()) { "Specialist summary lane must not be blank." }
    require(assignmentDigest.isNotBlank()) { "Specialist summary must name its assignment digest." }
    require(findingCount >= 0) { "Specialist summary finding count cannot be negative." }
    require(summary.length <= MAX_SUMMARY_LENGTH) {
      "Specialist summary for lane '$lane' exceeds the $MAX_SUMMARY_LENGTH-character bound."
    }
    if (disposition == ReviewLaneReviewDisposition.INCOMPLETE) {
      require(unreviewedSegmentIds.isNotEmpty() && unreviewedUnits.isNotEmpty()) {
        "An incomplete lane summary must name what it left unreviewed."
      }
    }
  }

  val isCleanCoverage: Boolean get() = disposition == ReviewLaneReviewDisposition.COMPLETE

  companion object {
    const val MAX_SUMMARY_LENGTH: Int = 2000

    fun of(
      lane: String,
      assignmentDigest: String,
      completion: ReviewLaneCompletionState,
      coverage: ReviewSpecialistSummaryCoverage,
    ): ReviewSpecialistSummary = ReviewSpecialistSummary(
      lane = lane,
      assignmentDigest = assignmentDigest,
      disposition = completion.disposition,
      assignedPaths = coverage.assignedPaths.distinct().sorted(),
      commitShas = coverage.commitShas.distinct(),
      findingCount = coverage.findingCount,
      unreviewedSegmentIds = completion.unreviewedSegmentIds,
      unreviewedUnits = completion.unreviewedUnits,
      summary = coverage.summary.replace("\r\n", "\n").take(MAX_SUMMARY_LENGTH),
    )
  }
}

/** Terminal state of the single integration pass, distinct from any lane's terminal state. */
enum class ReviewIntegrationTerminalOutcome {
  COMPLETED,
  SKIPPED_NOT_APPLICABLE,
  REVIEW_CONTEXT_BUDGET_EXCEEDED,
  FAILED,
  TIMEOUT,
  INTERRUPTED,
  SPAWN_FAILURE,
  PROCESS_FAILURE,
  UNSUPPORTED_PROVIDER,
  NO_OP_RESUME,
  ;

  val wireValue: String get() = name.lowercase()

  /** Only a completed pass is a durable boundary; anything else must be re-run on resume. */
  val isDurablyComplete: Boolean
    get() = this == COMPLETED || this == SKIPPED_NOT_APPLICABLE || this == NO_OP_RESUME
}

/**
 * The one bounded pass that runs after every selected lane reaches a terminal state. It carries
 * final-state evidence targets, commit identity metadata, and per-lane summaries — never a lane
 * bundle, a sibling's hunk bodies, an aggregate diff, or a parent transcript.
 *
 * Its cost is fixed at one pass: it does not scale with commit count and never re-launches a
 * specialist rubric.
 */
data class GovernedReviewIntegrationLaunch(
  val packet: ReviewContextPacket,
  val specialistSummaries: List<ReviewSpecialistSummary>,
  val integrationContract: String,
  val brokerId: String,
  val budget: ReviewContextBudgetPolicy,
  val isolation: ReviewConversationIsolation = ReviewConversationIsolation.FRESH,
) {
  init {
    require(integrationContract.isNotBlank()) { "Integration launch requires a non-blank contract." }
    require(brokerId.isNotBlank()) { "Integration launch requires a broker id." }
    require(specialistSummaries.map { it.lane }.distinct().size == specialistSummaries.size) {
      "Integration launch carries more than one summary for the same lane."
    }
    val foreign = specialistSummaries.map { it.lane }.filterNot { it in packet.selectedLanes }
    require(foreign.isEmpty()) {
      "Integration launch carries summaries for lanes outside the packet selection: ${foreign.sorted()}."
    }
    val unknownCommits = specialistSummaries.flatMap { it.commitShas }.filterNot { it in packet.ownedCommitIds }
    require(unknownCommits.isEmpty()) {
      "Integration launch names commits the packet does not own: ${unknownCommits.distinct().sorted()}."
    }
  }

  val commitSequenceDigest: String get() = packet.commitSequenceDigest

  /**
   * Final-state evidence: the head-revision view of every path any summarized lane touched. Cross-
   * commit interactions live in the composed end state, not in any single lane's incremental hunks.
   */
  val finalStateEvidenceTargets: List<ReviewEvidenceTarget>
    get() {
      val summarizedPaths = specialistSummaries.flatMap { it.assignedPaths }.toSet()
      return packet.evidenceTargets.filter { it.path in summarizedPaths }.sortedBy { it.targetId }
    }

  /** Lanes whose coverage was not clean; the integration pass never closes these gaps. */
  val incompleteLanes: List<String>
    get() = specialistSummaries.filterNot { it.isCleanCoverage }.map { it.lane }.sorted()
}
