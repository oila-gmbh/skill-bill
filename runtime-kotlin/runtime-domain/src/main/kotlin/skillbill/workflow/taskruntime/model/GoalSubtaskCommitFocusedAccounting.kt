package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap

/**
 * What one delegated commit-focused review pass actually did, as durable lifecycle state: the
 * commit sequence it covered, how sparsely it routed, which lanes ended incomplete from budget
 * exhaustion, what the parent's relevance analysis consumed, and how the single integration pass
 * ended.
 *
 * Identities, counts, and lane names only. A commit subject, a path, or diff text here would put
 * code content into durable lifecycle state, which this record exists to stay clear of.
 */
data class GoalSubtaskCommitFocusedAccounting(
  val commitSequenceDigest: String,
  val commitCount: Int,
  val laneCount: Int,
  val focusedCommitCount: Int,
  val skippedCommitCount: Int,
  val integrationTerminalOutcome: String,
  val routingDigest: String? = null,
  val focusedPairCount: Int? = null,
  val skippedPairCount: Int? = null,
  val laneBundleSizes: Map<String, Long> = emptyMap(),
  val laneSegmentCounts: Map<String, Int> = emptyMap(),
  /** Non-clean coverage. The integration pass never compensates for a lane named here. */
  val incompleteLanes: List<String> = emptyList(),
  val parentAnalysisPairs: Int? = null,
  val parentAnalysisBytes: Long? = null,
  val integrationSkipReason: String? = null,
  val integrationFindingCount: Int? = null,
) {
  init {
    require(commitSequenceDigest.matches(SHA256_HEX)) {
      "Commit-focused accounting requires a SHA-256 commit sequence identity."
    }
    require(integrationTerminalOutcome in INTEGRATION_TERMINAL_OUTCOMES) {
      "Unknown integration terminal outcome '$integrationTerminalOutcome'."
    }
    require(listOf(commitCount, laneCount, focusedCommitCount, skippedCommitCount).all { it >= 0 })
    require(focusedCommitCount + skippedCommitCount == commitCount) {
      "Every commit is either focused by some lane or skipped by all of them."
    }
    require(incompleteLanes.distinct().size == incompleteLanes.size)
    if (integrationTerminalOutcome == SKIPPED_NOT_APPLICABLE) {
      require(!integrationSkipReason.isNullOrBlank()) {
        "A skipped integration pass must record why it was not applicable."
      }
    }
  }

  val isCleanCoverage: Boolean get() = incompleteLanes.isEmpty()

  @OpenBoundaryMap("Commit-focused review accounting at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "commit_sequence_digest" to commitSequenceDigest,
    "commit_count" to commitCount,
    "lane_count" to laneCount,
    "focused_commit_count" to focusedCommitCount,
    "skipped_commit_count" to skippedCommitCount,
    "integration_terminal_outcome" to integrationTerminalOutcome,
  ).apply {
    routingDigest?.let { put("routing_digest", it) }
    focusedPairCount?.let { put("focused_pair_count", it) }
    skippedPairCount?.let { put("skipped_pair_count", it) }
    laneBundleSizes.takeIf { it.isNotEmpty() }?.let { put("lane_bundle_sizes", it.toSortedMap()) }
    laneSegmentCounts.takeIf { it.isNotEmpty() }?.let { put("lane_segment_counts", it.toSortedMap()) }
    incompleteLanes.takeIf { it.isNotEmpty() }?.let { put("incomplete_lanes", it.sorted()) }
    parentAnalysisPairs?.let { put("parent_analysis_pairs", it) }
    parentAnalysisBytes?.let { put("parent_analysis_bytes", it) }
    integrationSkipReason?.let { put("integration_skip_reason", it) }
    integrationFindingCount?.let { put("integration_finding_count", it) }
  }

  companion object {
    const val SKIPPED_NOT_APPLICABLE: String = "skipped_not_applicable"

    val INTEGRATION_TERMINAL_OUTCOMES: Set<String> = setOf(
      "completed",
      SKIPPED_NOT_APPLICABLE,
      "review_context_budget_exceeded",
      "failed",
      "timeout",
      "interrupted",
      "spawn_failure",
      "process_failure",
      "unsupported_provider",
      "no_op_resume",
    )

    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    @OpenBoundaryMap("Commit-focused review accounting decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): GoalSubtaskCommitFocusedAccounting {
      raw.requireOnlyReviewStateKeys(ARTIFACT_KEYS, path)
      return GoalSubtaskCommitFocusedAccounting(
        commitSequenceDigest = raw.requireReviewStateString("commit_sequence_digest", path),
        commitCount = raw.requireReviewStateInt("commit_count", path),
        laneCount = raw.requireReviewStateInt("lane_count", path),
        focusedCommitCount = raw.requireReviewStateInt("focused_commit_count", path),
        skippedCommitCount = raw.requireReviewStateInt("skipped_commit_count", path),
        integrationTerminalOutcome = raw.requireReviewStateString("integration_terminal_outcome", path),
        routingDigest = raw.optionalReviewStateString("routing_digest", path),
        focusedPairCount = raw.optionalReviewStateInt("focused_pair_count", path),
        skippedPairCount = raw.optionalReviewStateInt("skipped_pair_count", path),
        laneBundleSizes = raw.longCountMap("lane_bundle_sizes", path),
        laneSegmentCounts = raw.longCountMap("lane_segment_counts", path)
          .mapValues { (_, value) -> value.toInt() },
        incompleteLanes = raw.optionalReviewStateList("incomplete_lanes", path)
          .orEmpty()
          .map { it.toString() },
        parentAnalysisPairs = raw.optionalReviewStateInt("parent_analysis_pairs", path),
        parentAnalysisBytes = raw.optionalReviewStateInt("parent_analysis_bytes", path)?.toLong(),
        integrationSkipReason = raw.optionalReviewStateString("integration_skip_reason", path),
        integrationFindingCount = raw.optionalReviewStateInt("integration_finding_count", path),
      )
    }

    private val ARTIFACT_KEYS = setOf(
      "commit_sequence_digest",
      "commit_count",
      "lane_count",
      "focused_commit_count",
      "skipped_commit_count",
      "integration_terminal_outcome",
      "routing_digest",
      "focused_pair_count",
      "skipped_pair_count",
      "lane_bundle_sizes",
      "lane_segment_counts",
      "incomplete_lanes",
      "parent_analysis_pairs",
      "parent_analysis_bytes",
      "integration_skip_reason",
      "integration_finding_count",
    )

    private fun Map<String, Any?>.longCountMap(key: String, path: String): Map<String, Long> {
      val raw = this[key] ?: return emptyMap()
      return raw.asReviewStateMap("$path.$key").mapValues { (_, value) ->
        (value as? Number)?.toLong() ?: 0L
      }
    }
  }
}
