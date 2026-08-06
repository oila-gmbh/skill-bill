@file:Suppress("MagicNumber")

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
    require(orderIndex >= 0) { "Assembled bundle entry order index cannot be negative." }
  }

  val hunkId: String get() = hunk.hunkId

  val canonical: String get() = canonicalFields(
    commitSha,
    parentSha,
    subject.replace("\r\n", "\n"),
    orderIndex,
    hunk.canonicalValue(),
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

  val canonical: String get() = canonicalFields(*entries.map { it.canonical }.toTypedArray())

  /** Stable identity of the bundle's composition for launch, result, and resume records. */
  val compositionDigest: String get() = sha256Hex(canonical)

  companion object {
    val EMPTY: ReviewLaneAssembledBundle = ReviewLaneAssembledBundle(emptyList())

    val ENTRY_ORDER: Comparator<ReviewLaneAssembledEntry> = compareBy(
      ReviewLaneAssembledEntry::orderIndex,
      { it.hunk.path },
      { it.hunk.newStart },
      { it.hunk.oldStart },
    )

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
    require(measuredBytes > 0) { "Bundle segment '$segmentId' must report a positive byte count." }
    val ordered = entries.sortedWith(ReviewLaneAssembledBundle.ENTRY_ORDER)
    require(entries == ordered) { "Bundle segment entries must preserve assembled-bundle order." }
  }

  val compositionDigest: String get() =
    sha256Hex(canonicalFields(*entries.map { it.canonical }.toTypedArray()))
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
    require(measuredBytes >= 0 && entryCount >= 0)
    require(compositionDigest.matches(SHA256_HEX_PATTERN))
  }
}

data class ReviewLaneCompletionState(
  val disposition: ReviewLaneReviewDisposition,
  val bundleCompositionDigest: String,
  val segments: List<ReviewLaneSegmentAccounting>,
  val unreviewedSegmentIds: List<String> = emptyList(),
  val budgetDimension: String? = null,
) {
  init {
    require(bundleCompositionDigest.matches(SHA256_HEX_PATTERN)) {
      "Lane completion state requires a SHA-256 bundle composition digest."
    }
    require(unreviewedSegmentIds.distinct().size == unreviewedSegmentIds.size)
    when (disposition) {
      ReviewLaneReviewDisposition.COMPLETE -> {
        require(unreviewedSegmentIds.isEmpty()) { "A complete lane cannot name unreviewed segments." }
        require(budgetDimension == null) { "A complete lane cannot name a stopping budget dimension." }
      }
      ReviewLaneReviewDisposition.INCOMPLETE -> {
        require(unreviewedSegmentIds.isNotEmpty()) {
          "An incomplete lane must name every unreviewed segment id."
        }
        require(!budgetDimension.isNullOrBlank()) {
          "An incomplete lane must name the budget dimension that stopped it."
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
      segmentId = "seg-${segmentIndex.toString().padStart(3, '0')}",
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

fun ReviewLaneBundleSegmentation.toCompletionState(
  bundleCompositionDigest: String,
): ReviewLaneCompletionState = if (incomplete) {
  ReviewLaneCompletionState(
    disposition = ReviewLaneReviewDisposition.INCOMPLETE,
    bundleCompositionDigest = bundleCompositionDigest,
    segments = segments.map { it.toAccounting() } +
      ReviewLaneSegmentAccounting(
        segmentId = ReviewLaneBundleSegmentation.UNREVIEWABLE_SEGMENT_ID,
        measuredBytes = 0,
        entryCount = unreviewableEntries.size,
        compositionDigest = sha256Hex(
          canonicalFields(*unreviewableEntries.map { it.canonical }.toTypedArray()),
        ),
      ),
    unreviewedSegmentIds = listOf(ReviewLaneBundleSegmentation.UNREVIEWABLE_SEGMENT_ID),
    budgetDimension = "lane_launch_bytes",
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

private val SHA256_HEX_PATTERN = Regex("[a-f0-9]{64}")

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(StandardCharsets.UTF_8))
  .joinToString("") { byte -> "%02x".format(byte) }
