package skillbill.review.context.model

const val REVIEW_SYNTHETIC_COMMIT_PREFIX: String = "synthetic:"

enum class ReviewCommitSource {
  COMMIT_RANGE,
  SYNTHETIC_WORKING_TREE,
  SYNTHETIC_SUPPLIED_DIFF,
  SYNTHETIC_AGGREGATE_PR_DIFF,
  ;

  val isSynthetic: Boolean get() = this != COMMIT_RANGE
}

/** One ordered review unit: a real commit's incremental hunks, or a synthetic whole-delta unit. */
data class ReviewCommitUnit(
  val commitSha: String,
  val parentSha: String,
  val subject: String,
  val orderIndex: Int,
  val hunks: List<ReviewChangedHunk>,
  val source: ReviewCommitSource,
) {
  init {
    require(commitSha.isNotBlank()) { "Review commit unit identity must not be blank." }
    require(parentSha.isNotBlank()) { "Review commit unit parent identity must not be blank." }
    require(orderIndex >= REVIEW_MIN_ORDER_INDEX) { "Review commit unit order index cannot be negative." }
    require(hunks.map { it.hunkId }.distinct().size == hunks.size) {
      "Review commit unit '$commitSha' repeats a hunk id; a commit owns each hunk exactly once."
    }
    val ownScope = commitScopeKey(commitSha, orderIndex)
    require(hunks.all { it.commitScope == null || (!source.isSynthetic && it.commitScope == ownScope) }) {
      "Review commit unit '$commitSha' owns a hunk scoped to a different commit."
    }
    require(
      hunks.map { it.path to listOf(it.oldStart, it.newStart) }.distinct().size == hunks.size,
    ) { "Review commit unit '$commitSha' carries two hunks at the same position in one file." }
    if (source.isSynthetic) {
      require(
        commitSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX) && parentSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX),
      ) {
        "Synthetic review unit from source '$source' must carry a '$REVIEW_SYNTHETIC_COMMIT_PREFIX' placeholder " +
          "identity, never a fabricated commit SHA."
      }
      require(orderIndex == REVIEW_SYNTHETIC_UNIT_ORDER_INDEX) {
        "A synthetic review unit is the sole unit of its packet and must be first."
      }
    } else {
      require(
        !commitSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX) && !parentSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX),
      ) { "A COMMIT_RANGE review unit must carry real Git identities, not synthetic placeholders." }
    }
  }

  /**
   * A commit owns its hunks as a set, so every identity and projection reads this order rather than
   * the order a fact port or parser happened to enumerate them in. The unit's own uniqueness
   * invariant makes (path, newStart, oldStart) a total order over its hunks.
   */
  val canonicalHunks: List<ReviewChangedHunk>
    get() = hunks.sortedWith(
      compareBy(ReviewChangedHunk::path, ReviewChangedHunk::newStart, ReviewChangedHunk::oldStart),
    )

  val hunkIds: List<String> get() = canonicalHunks.map { it.hunkId }

  val commitUnitId: String by lazy(LazyThreadSafetyMode.PUBLICATION) { sha256(canonicalValue()) }

  internal fun canonicalValue(): String = canonicalFields(
    commitSha,
    parentSha,
    subject.replace("\r\n", "\n"),
    orderIndex,
    source.name,
    canonicalFieldList(canonicalHunks.map { it.packetCanonical() }),
  )

  companion object {
    fun commitScopeKey(commitSha: String, orderIndex: Int): String = "$commitSha@$orderIndex"

    /** Builds a COMMIT_RANGE unit, scoping every hunk to this commit so its identity is commit-owned. */
    fun ofCommit(
      commitSha: String,
      parentSha: String,
      subject: String,
      orderIndex: Int,
      hunks: List<ReviewChangedHunk>,
    ): ReviewCommitUnit {
      val scope = commitScopeKey(commitSha, orderIndex)
      return ReviewCommitUnit(
        commitSha = commitSha,
        parentSha = parentSha,
        subject = subject,
        orderIndex = orderIndex,
        hunks = hunks.map { if (it.commitScope == scope) it else it.copy(commitScope = scope) },
        source = ReviewCommitSource.COMMIT_RANGE,
      )
    }

    fun synthetic(source: ReviewCommitSource, hunks: List<ReviewChangedHunk>): ReviewCommitUnit {
      require(source.isSynthetic) { "A synthetic review unit cannot declare the COMMIT_RANGE source." }
      return ReviewCommitUnit(
        commitSha = REVIEW_SYNTHETIC_COMMIT_PREFIX + source.name.lowercase(),
        parentSha = REVIEW_SYNTHETIC_COMMIT_PREFIX + "base",
        subject = "synthetic review unit for ${source.name.lowercase()}",
        orderIndex = REVIEW_SYNTHETIC_UNIT_ORDER_INDEX,
        hunks = hunks,
        source = source,
      )
    }
  }
}

/**
 * The checked base-to-head equivalence fact: the ordered units cover the authoritative delta with
 * no silent omission or duplication. A unit sequence that cannot assert the chain must say why.
 */
data class ReviewCommitCoverageFact(
  val baseRevision: String,
  val headRevision: String,
  val commitCount: Int,
  val chainVerified: Boolean,
  val pathCoverageVerified: Boolean,
  val degradedReason: String? = null,
) {
  init {
    require(baseRevision.isNotBlank() && headRevision.isNotBlank()) {
      "Commit coverage fact must carry non-blank base and head revisions."
    }
    require(commitCount >= REVIEW_MIN_COMMIT_COUNT) { "Commit coverage fact must describe at least one review unit." }
    require(degradedReason == null || degradedReason.isNotBlank()) {
      "A commit coverage fact degraded reason must not be blank."
    }
    require((chainVerified && pathCoverageVerified) || !degradedReason.isNullOrBlank()) {
      "An unverified commit coverage fact must name the reason it could not be verified."
    }
  }

  val canonical: String get() = canonicalFields(
    baseRevision,
    headRevision,
    commitCount,
    chainVerified,
    pathCoverageVerified,
    degradedReason.orEmpty(),
  )
}
