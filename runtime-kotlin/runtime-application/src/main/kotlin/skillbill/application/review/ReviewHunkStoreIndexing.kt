package skillbill.application.review

import skillbill.application.evidence.SharedReviewEvidenceCodec
import skillbill.application.evidence.SharedReviewEvidenceCommits
import skillbill.application.evidence.SharedReviewEvidenceRecord
import skillbill.error.ReviewHunkEvidenceIntegrityError
import skillbill.error.ReviewHunkEvidenceLocatorUnreadableError
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest
import skillbill.review.context.model.ReviewChangedHunk
import skillbill.review.context.model.ReviewCommitSource
import skillbill.review.context.model.ReviewCommitUnit
import skillbill.review.context.model.ReviewHunkEvidenceLocator
import java.nio.file.Path

internal data class IndexedReviewHunks(
  val hunks: List<ReviewChangedHunk>,
  val commitUnits: List<ReviewCommitUnit>,
)

internal object ReviewHunkStoreIndexing {
  fun index(
    hunks: List<ReviewChangedHunk>,
    commitUnits: List<ReviewCommitUnit>,
    storePath: String?,
    repoRoot: Path?,
    locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort,
  ): IndexedReviewHunks {
    if (storePath.isNullOrBlank()) {
      return IndexedReviewHunks(
        hunks = hunks.map { it.asIndex(it.evidenceLocator, it.content) },
        commitUnits = commitUnits.map { unit ->
          unit.copy(hunks = unit.hunks.map { it.asIndex(it.evidenceLocator, it.content) })
        },
      )
    }
    val root = repoRoot ?: throw ReviewHunkEvidenceLocatorUnreadableError(
      storePath,
      "compose-time locator dereference requires a repository root",
    )
    val payload = locatorReader.readDiffPayload(
      FeatureTaskRuntimeSharedEvidenceLocatorReadRequest(root, storePath),
    )
    val record = SharedReviewEvidenceCodec.decode(payload) ?: rawRecord(payload, storePath)
    val indexed = hunks.map { hunk -> indexHunk(hunk, record, storePath) }
    val byKey = indexed.associateBy { hunkKey(it) }
    return IndexedReviewHunks(
      hunks = indexed,
      commitUnits = commitUnits.map { unit ->
        unit.copy(
          hunks = unit.hunks.map { incoming ->
            byKey[hunkKey(incoming)] ?: indexHunk(incoming, record, storePath)
          },
        )
      },
    )
  }

  private fun rawRecord(payload: String, storePath: String): SharedReviewEvidenceRecord {
    runCatching { ReviewDiffEvidence.parse(payload) }.getOrNull()
      ?: throw ReviewHunkEvidenceLocatorUnreadableError(
        storePath,
        "payload is not a shared-evidence or git-diff body",
      )
    return SharedReviewEvidenceRecord(
      aggregateDiff = payload,
      sequence = SharedReviewEvidenceCommits(
        baseRevision = "unknown",
        headRevision = "unknown",
        commits = emptyList(),
        syntheticSource = ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF,
        syntheticReason = "compose-time locator payload was a raw diff",
      ),
    )
  }

  private fun indexHunk(
    hunk: ReviewChangedHunk,
    record: SharedReviewEvidenceRecord,
    storePath: String,
  ): ReviewChangedHunk {
    val storedBody = storedBody(hunk, record, storePath)
    val storedDigest = ReviewChangedHunk.digestOfBody(storedBody)
    if (hunk.content.isNotEmpty()) {
      val expected = ReviewChangedHunk.digestOfBody(hunk.content)
      if (expected != storedDigest) {
        throw ReviewHunkEvidenceIntegrityError(storePath, expected, storedDigest)
      }
    }
    val locator = ReviewHunkEvidenceLocator.atStore(
      storePath,
      hunk.oldStart,
      hunk.oldCount,
      hunk.newStart,
      hunk.newCount,
    )
    return hunk.asIndex(locator, storedBody)
  }

  private fun storedBody(
    hunk: ReviewChangedHunk,
    record: SharedReviewEvidenceRecord,
    storePath: String,
  ): String {
    val scopedDiff = hunk.commitScope?.let { scope ->
      val sha = scope.substringBefore('@')
      record.sequence.commits.find { it.commitSha == sha }?.diff
    }
    val match = listOfNotNull(scopedDiff, record.aggregateDiff).firstNotNullOfOrNull { diff ->
      runCatching { ReviewDiffEvidence.parse(diff) }.getOrNull()
        ?.hunks
        ?.find { sameSpan(it, hunk) }
    } ?: throw ReviewHunkEvidenceLocatorUnreadableError(
      storePath,
      "stored payload has no hunk at ${hunk.path} " +
        ReviewHunkEvidenceLocator.header(hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount),
    )
    return match.content
  }

  private fun sameSpan(left: ReviewChangedHunk, right: ReviewChangedHunk): Boolean =
    left.path == right.path &&
      left.oldStart == right.oldStart &&
      left.oldCount == right.oldCount &&
      left.newStart == right.newStart &&
      left.newCount == right.newCount

  private fun hunkKey(hunk: ReviewChangedHunk): String =
    listOf(hunk.path, hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount, hunk.commitScope.orEmpty())
      .joinToString("\u0000")
}
