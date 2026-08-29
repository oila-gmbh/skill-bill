package skillbill.application.review

import skillbill.application.evidence.SharedReviewEvidenceAssembler
import skillbill.application.evidence.SharedReviewEvidenceProjection
import skillbill.application.review.model.ParallelReviewScope
import skillbill.ports.diff.DiffResolverPort
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitUnit
import java.nio.file.Path
import skillbill.application.evidence.parseCommitUnits as sharedParseCommitUnits

internal data class ResolvedCommitSequence(
  val units: List<ReviewCommitUnit>,
  val coverageFact: ReviewCommitCoverageFact,
)

/** Base and head always travel together; naming the pair keeps a range from being split or swapped. */
internal data class ReviewCommitRange(val baseRevision: String, val headRevision: String) {
  val span: String get() = "$baseRevision..$headRevision"
}

/** Alias keeping the review-side call name while the assembly itself lives in the phase-neutral package. */
internal fun parseCommitUnits(commits: List<RawCommitDiff>): List<ReviewCommitUnit> = sharedParseCommitUnits(commits)

/**
 * Thin review-side caller over the phase-neutral assembly. The Git traversal and the unit
 * construction both live in [SharedReviewEvidenceAssembler] and [SharedReviewEvidenceProjection] so
 * the shared deriver can produce the same evidence for any phase; review resolves it here only when
 * it is not reading an already-derived checkpoint artifact.
 */
internal class ReviewCommitSequenceResolver(private val diffResolver: DiffResolverPort) {
  fun resolve(
    scope: ParallelReviewScope,
    repoRoot: Path,
    range: ReviewCommitRange,
    aggregate: ReviewDiffEvidence,
    suppliedDiff: Boolean,
  ): ResolvedCommitSequence = SharedReviewEvidenceProjection.project(
    SharedReviewEvidenceAssembler(diffResolver).assemble(scope, repoRoot, range, suppliedDiff),
    aggregate,
  )
}
