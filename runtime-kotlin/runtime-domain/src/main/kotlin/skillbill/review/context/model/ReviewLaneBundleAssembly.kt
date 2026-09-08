package skillbill.review.context.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * One assigned hunk body with the commit identity the parent already decided. Ordering authority is
 * commit order index, then path, then newStart — never worker-side rediscovery.
 */
data class ReviewLaneAssembledEntry(
  val commitSha: String,
  val parentSha: String,
  val subject: String,
  val orderIndex: Int,
  val hunk: ReviewChangedHunk,
) {
  init {
    require(commitSha.isNotBlank()) { "Assembled bundle entry commit identity must not be blank." }
    require(parentSha.isNotBlank()) { "Assembled bundle entry parent identity must not be blank." }
    require(orderIndex >= REVIEW_MIN_ORDER_INDEX) { "Assembled bundle entry order index cannot be negative." }
  }

  val hunkId: String get() = hunk.hunkId

  val canonical: String get() = canonicalFields(
    commitSha,
    parentSha,
    subject.replace("\r\n", "\n"),
    orderIndex,
    hunk.packetCanonical(),
  )
}

/**
 * Launch-assembled view of a lane's sparse assignment: ordered hunk bodies with commit metadata.
 * Distinct from [ReviewLaneBundle], which stores assignment-level hunk-id grouping without bodies.
 */
data class ReviewLaneAssembledBundle(val entries: List<ReviewLaneAssembledEntry>) {
  init {
    val ids = entries.map { it.hunkId }
    require(ids.distinct().size == ids.size) { "Assembled bundle repeats a hunk id." }
    val ordered = entries.sortedWith(ENTRY_ORDER)
    require(entries == ordered) {
      "Assembled bundle entries must be ordered by commit order index, then path, then newStart."
    }
  }

  val hunkIds: List<String> get() = entries.map { it.hunkId }

  val canonical: String get() = canonicalFieldList(entries.map { it.canonical })

  /** Stable identity of the bundle's composition for launch, result, and resume records. */
  val compositionDigest: String get() = sha256Hex(canonical)

  companion object {
    // Declared before EMPTY: the constructor's ordering check reads ENTRY_ORDER, so initializing
    // EMPTY first would run that check against a still-null comparator and fail class init.
    val ENTRY_ORDER: Comparator<ReviewLaneAssembledEntry> = compareBy(
      ReviewLaneAssembledEntry::orderIndex,
      { it.hunk.path },
      { it.hunk.newStart },
      { it.hunk.oldStart },
    )

    val EMPTY: ReviewLaneAssembledBundle = ReviewLaneAssembledBundle(emptyList())

    /**
     * Materializes the assignment's commit-grouped hunk ids into ordered body entries using the
     * packet's commit units. Does not re-decide relevance or re-derive the commit sequence.
     */
    fun assemble(assignment: ReviewAssignment, packet: ReviewContextPacket): ReviewLaneAssembledBundle {
      val hunksById = packet.changedHunks.associateBy { it.hunkId }
      val unitsBySha = packet.commitUnits.associateBy { it.commitSha }
      val entries = assignment.assignedBundle.entries.flatMap { group ->
        val unit = unitsBySha.getValue(group.commitSha)
        require(group.orderIndex == unit.orderIndex) {
          "Assignment bundle order for '${group.commitSha}' diverges from the packet."
        }
        group.hunkIds.map { hunkId ->
          val hunk = hunksById.getValue(hunkId)
          ReviewLaneAssembledEntry(
            commitSha = unit.commitSha,
            parentSha = unit.parentSha,
            subject = unit.subject,
            orderIndex = unit.orderIndex,
            hunk = hunk,
          )
        }
      }.sortedWith(ENTRY_ORDER)
      require(entries.map { it.hunkId }.toSet() == assignment.assignedHunks.toSet()) {
        "Assembled bundle must cover exactly the assigned hunks."
      }
      return ReviewLaneAssembledBundle(entries)
    }
  }
}

/** One size-driven slice of an assembled bundle; may start or end mid-commit. */
data class ReviewLaneBundleSegment(
  val segmentId: String,
  val entries: List<ReviewLaneAssembledEntry>,
  val measuredBytes: Long,
) {
  init {
    require(segmentId.isNotBlank()) { "Bundle segment id must not be blank." }
    require(entries.isNotEmpty()) { "Bundle segment '$segmentId' must carry at least one entry." }
    require(measuredBytes >= REVIEW_MIN_SEGMENT_MEASURED_BYTES) {
      "Bundle segment '$segmentId' must report a positive byte count."
    }
    val ordered = entries.sortedWith(ReviewLaneAssembledBundle.ENTRY_ORDER)
    require(entries == ordered) { "Bundle segment entries must preserve assembled-bundle order." }
  }

  val compositionDigest: String get() = sha256Hex(canonicalFieldList(entries.map { it.canonical }))
}

/**
 * Result of packing an assembled bundle into the fewest segments that fit [maxLaneLaunchBytes].
 * Unreviewable entries are those that cannot fit alone; they feed an incomplete lane disposition.
 */
data class ReviewLaneBundleSegmentation(
  val segments: List<ReviewLaneBundleSegment>,
  val unreviewableEntries: List<ReviewLaneAssembledEntry> = emptyList(),
  val budgetLimitBytes: Long,
) {
  init {
    require(budgetLimitBytes > 0) { "Segmentation budget must be positive." }
    require(segments.map { it.segmentId }.distinct().size == segments.size) {
      "Bundle segment ids must be unique."
    }
    val seen = mutableSetOf<String>()
    (segments.flatMap { it.entries } + unreviewableEntries).forEach { entry ->
      require(seen.add(entry.hunkId)) { "Segmentation claims hunk '${entry.hunkId}' more than once." }
    }
  }

  val unreviewedSegmentIds: List<String>
    get() = if (unreviewableEntries.isEmpty()) {
      emptyList()
    } else {
      listOf(UNREVIEWABLE_SEGMENT_ID)
    }

  val incomplete: Boolean get() = unreviewableEntries.isNotEmpty()

  companion object {
    const val UNREVIEWABLE_SEGMENT_ID: String = "unreviewable"
  }
}

/** Whether a lane finished its single pass over every assigned segment. */
enum class ReviewLaneReviewDisposition {
  COMPLETE,
  INCOMPLETE,
  ;

  val wireValue: String get() = name.lowercase()
}

data class ReviewLaneSegmentAccounting(
  val segmentId: String,
  val measuredBytes: Long,
  val entryCount: Int,
  val compositionDigest: String,
) {
  init {
    require(segmentId.isNotBlank())
    require(measuredBytes >= REVIEW_MIN_ORDER_INDEX && entryCount >= REVIEW_MIN_ORDER_INDEX)
    require(compositionDigest.matches(SHA256_HEX))
  }
}

data class ReviewLaneCompletionState(
  val disposition: ReviewLaneReviewDisposition,
  val bundleCompositionDigest: String,
  val segments: List<ReviewLaneSegmentAccounting>,
  val unreviewedSegmentIds: List<String> = emptyList(),
  val budgetDimension: String? = null,
  /**
   * The concrete review units the lane left unreviewed, as `commit@path` labels. Reporting has to
   * name what was not covered; a segment id alone tells a reader nothing about which code went
   * unreviewed.
   */
  val unreviewedUnits: List<String> = emptyList(),
) {
  init {
    require(bundleCompositionDigest.matches(SHA256_HEX)) {
      "Lane completion state requires a SHA-256 bundle composition digest."
    }
    require(unreviewedSegmentIds.distinct().size == unreviewedSegmentIds.size)
    when (disposition) {
      ReviewLaneReviewDisposition.COMPLETE -> {
        require(unreviewedSegmentIds.isEmpty()) { "A complete lane cannot name unreviewed segments." }
        require(budgetDimension == null) { "A complete lane cannot name a stopping budget dimension." }
        require(unreviewedUnits.isEmpty()) { "A complete lane cannot name unreviewed units." }
      }
      ReviewLaneReviewDisposition.INCOMPLETE -> {
        require(unreviewedSegmentIds.isNotEmpty()) {
          "An incomplete lane must name every unreviewed segment id."
        }
        require(!budgetDimension.isNullOrBlank()) {
          "An incomplete lane must name the budget dimension that stopped it."
        }
        require(unreviewedUnits.isNotEmpty()) {
          "An incomplete lane must name the units it left unreviewed."
        }
      }
    }
  }

  val isCleanCoverage: Boolean
    get() = disposition == ReviewLaneReviewDisposition.COMPLETE
}

/**
 * Packs [bundle] entries in order into the fewest segments whose measured launch payload fits
 * [maxLaneLaunchBytes]. Split points are size-driven only — never commit boundaries.
 */
fun segmentAssembledBundle(
  bundle: ReviewLaneAssembledBundle,
  maxLaneLaunchBytes: Long,
  measure: (List<ReviewLaneAssembledEntry>) -> Long,
): ReviewLaneBundleSegmentation {
  require(maxLaneLaunchBytes > 0)
  if (bundle.entries.isEmpty()) {
    return ReviewLaneBundleSegmentation(emptyList(), emptyList(), maxLaneLaunchBytes)
  }
  val segments = mutableListOf<ReviewLaneBundleSegment>()
  val unreviewable = mutableListOf<ReviewLaneAssembledEntry>()
  var current = mutableListOf<ReviewLaneAssembledEntry>()
  var segmentIndex = 0

  fun flushCurrent() {
    if (current.isEmpty()) return
    val measured = measure(current)
    require(measured <= maxLaneLaunchBytes) {
      "Flushed segment exceeds the configured lane launch budget."
    }
    segments += ReviewLaneBundleSegment(
      segmentId = "seg-${segmentIndex.toString().padStart(REVIEW_BUNDLE_SEGMENT_ID_PAD_WIDTH, '0')}",
      entries = current.toList(),
      measuredBytes = measured,
    )
    segmentIndex += 1
    current = mutableListOf()
  }

  bundle.entries.forEach { entry ->
    val trial = current + entry
    val trialBytes = measure(trial)
    if (trialBytes <= maxLaneLaunchBytes) {
      current = trial.toMutableList()
      return@forEach
    }
    if (current.isEmpty()) {
      unreviewable += entry
      return@forEach
    }
    flushCurrent()
    val alone = measure(listOf(entry))
    if (alone <= maxLaneLaunchBytes) {
      current = mutableListOf(entry)
    } else {
      unreviewable += entry
    }
  }
  flushCurrent()
  return ReviewLaneBundleSegmentation(segments, unreviewable, maxLaneLaunchBytes)
}

fun ReviewLaneBundleSegmentation.toCompletionState(bundleCompositionDigest: String): ReviewLaneCompletionState =
  if (incomplete) {
    ReviewLaneCompletionState(
      disposition = ReviewLaneReviewDisposition.INCOMPLETE,
      bundleCompositionDigest = bundleCompositionDigest,
      segments = segments.map { it.toAccounting() } +
        ReviewLaneSegmentAccounting(
          segmentId = ReviewLaneBundleSegmentation.UNREVIEWABLE_SEGMENT_ID,
          measuredBytes = 0,
          entryCount = unreviewableEntries.size,
          compositionDigest = sha256Hex(canonicalFieldList(unreviewableEntries.map { it.canonical })),
        ),
      unreviewedSegmentIds = listOf(ReviewLaneBundleSegmentation.UNREVIEWABLE_SEGMENT_ID),
      budgetDimension = "lane_launch_bytes",
      unreviewedUnits = unreviewableEntries.map { "${it.commitSha}@${it.hunk.path}" }.distinct(),
    )
  } else {
    ReviewLaneCompletionState(
      disposition = ReviewLaneReviewDisposition.COMPLETE,
      bundleCompositionDigest = bundleCompositionDigest,
      segments = segments.map { it.toAccounting() },
    )
  }

fun ReviewLaneBundleSegment.toAccounting(): ReviewLaneSegmentAccounting = ReviewLaneSegmentAccounting(
  segmentId = segmentId,
  measuredBytes = measuredBytes,
  entryCount = entries.size,
  compositionDigest = compositionDigest,
)

const val LANE_EVIDENCE_BYTES_DIMENSION: String = "lane_evidence_bytes"

fun ReviewLaneCompletionState.withBrokerEvidenceRefusal(brokerDeniedUnits: List<String>): ReviewLaneCompletionState {
  val distinct = brokerDeniedUnits.distinct()
  require(distinct.isNotEmpty()) { "Broker evidence refusal must name at least one denied unit." }
  if (
    disposition == ReviewLaneReviewDisposition.INCOMPLETE &&
    budgetDimension == LANE_EVIDENCE_BYTES_DIMENSION &&
    unreviewedUnits == distinct
  ) {
    return this
  }
  return copy(
    disposition = ReviewLaneReviewDisposition.INCOMPLETE,
    unreviewedSegmentIds = segments.map { it.segmentId }.ifEmpty { listOf(BROKER_EVIDENCE_REFUSAL_SEGMENT_ID) },
    budgetDimension = LANE_EVIDENCE_BYTES_DIMENSION,
    unreviewedUnits = distinct,
  )
}

private const val BROKER_EVIDENCE_REFUSAL_SEGMENT_ID = "seg-evidence-refused"

/** The dimension named when a lane is incomplete because its parent agent run did not succeed. */
const val LANE_RUN_OUTCOME_DIMENSION: String = "lane_run_outcome"

/**
 * Downgrades a lane whose parent agent run did not succeed to incomplete coverage. Bundle
 * segmentation only describes whether the assembled bundle fit the budget, so a lane whose agent
 * crashed still looks complete by that measure; here its whole assigned bundle counts as
 * unreviewed, which is what both the coverage report and the integration pass must be told.
 */
fun ReviewLaneCompletionState.asFailedLaneRun(assignedUnits: List<String>): ReviewLaneCompletionState =
  if (disposition == ReviewLaneReviewDisposition.INCOMPLETE) {
    this
  } else {
    copy(
      disposition = ReviewLaneReviewDisposition.INCOMPLETE,
      unreviewedSegmentIds = segments.map { it.segmentId }.ifEmpty { listOf(FAILED_RUN_SEGMENT_ID) },
      budgetDimension = LANE_RUN_OUTCOME_DIMENSION,
      unreviewedUnits = assignedUnits.distinct().ifEmpty { listOf(FAILED_RUN_UNIT) },
    )
  }

private const val FAILED_RUN_SEGMENT_ID = "seg-lane-run-failed"
private const val FAILED_RUN_UNIT = "entire assigned bundle"

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(StandardCharsets.UTF_8))
  .joinToString("") { byte -> "%02x".format(byte) }
