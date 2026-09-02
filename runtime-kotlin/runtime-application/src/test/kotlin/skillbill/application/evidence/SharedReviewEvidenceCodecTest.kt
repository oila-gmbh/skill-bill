package skillbill.application.evidence

import skillbill.application.reviewevidence.RawCommitDiff
import skillbill.application.reviewevidence.SharedReviewEvidenceCodec
import skillbill.application.reviewevidence.SharedReviewEvidenceCommits
import skillbill.application.reviewevidence.SharedReviewEvidenceRecord
import skillbill.review.context.model.ReviewCommitSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AC-004, AC-007: the payload must round-trip every raw fact identity is derived from, verbatim. */
class SharedReviewEvidenceCodecTest {
  private fun commitRecord() = SharedReviewEvidenceRecord(
    aggregateDiff = "diff --git a/src/A.kt b/src/A.kt\n@@ -1,1 +1,2 @@\n+alpha\n",
    sequence = SharedReviewEvidenceCommits(
      baseRevision = "base",
      headRevision = "head",
      commits = listOf(
        RawCommitDiff("c1", "base", "subject with\nan embedded newline", "diff --git a/A b/A\n@@ -1 +1 @@\n+a\n"),
        // A body whose own lines look like length prefixes must not confuse the framing.
        RawCommitDiff("head", "c1", "12\nnot a length", "7\nseven\n0\n\n"),
      ),
      syntheticSource = null,
      syntheticReason = null,
    ),
  )

  @Test fun `a commit record round-trips byte-for-byte`() {
    val record = commitRecord()

    assertEquals(record, SharedReviewEvidenceCodec.decode(SharedReviewEvidenceCodec.encode(record)))
  }

  @Test fun `every synthetic source round-trips with its degraded reason verbatim`() {
    listOf(
      ReviewCommitSource.SYNTHETIC_WORKING_TREE,
      ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF,
      ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
    ).forEach { source ->
      val record = SharedReviewEvidenceRecord(
        aggregateDiff = "diff --git a/A b/A\n@@ -1 +1 @@\n+a\n",
        sequence = SharedReviewEvidenceCommits(
          baseRevision = "base",
          headRevision = "head",
          commits = emptyList(),
          syntheticSource = source,
          syntheticReason = "git enumerated no commits for base..head in the local object store",
        ),
      )

      val decoded = SharedReviewEvidenceCodec.decode(SharedReviewEvidenceCodec.encode(record))

      assertEquals(record, decoded, source.name)
      assertEquals(record.sequence.syntheticReason, decoded?.sequence?.syntheticReason)
    }
  }

  @Test fun `a truncated or foreign payload decodes to null rather than a partial record`() {
    val encoded = SharedReviewEvidenceCodec.encode(commitRecord())

    assertNull(SharedReviewEvidenceCodec.decode(encoded.dropLast(20)))
    assertNull(SharedReviewEvidenceCodec.decode(""))
    assertNull(SharedReviewEvidenceCodec.decode("not a shared evidence payload"))
    assertNull(SharedReviewEvidenceCodec.decode(encoded + "trailing"))
  }
}
