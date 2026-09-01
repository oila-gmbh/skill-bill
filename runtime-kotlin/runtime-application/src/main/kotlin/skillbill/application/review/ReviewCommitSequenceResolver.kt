package skillbill.application.review

import skillbill.application.reviewevidence.RawCommitDiff
import skillbill.application.reviewevidence.ResolvedCommitSequence
import skillbill.application.reviewevidence.ReviewCommitRange
import skillbill.application.reviewevidence.ReviewDiffEvidence
import skillbill.application.reviewevidence.SharedReviewEvidenceAssembler
import skillbill.application.reviewevidence.SharedReviewEvidenceProjection
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.ports.diff.DiffResolverPort
import skillbill.review.context.model.ReviewCommitUnit
import java.nio.file.Path
import skillbill.application.reviewevidence.parseCommitUnits as sharedParseCommitUnits

internal fun parseCommitUnits(commits: List<RawCommitDiff>): List<ReviewCommitUnit> = sharedParseCommitUnits(commits)

/**
 * Thin review-side caller over the phase-neutral assembly. The Git traversal and the unit
 * construction both live in [SharedReviewEvidenceAssembler] and [SharedReviewEvidenceProjection] so
 * the shared deriver can produce the same evidence for any phase; review resolves it here only when
 * it is not reading an already-derived checkpoint artifact.
 */
class ReviewCommitSequenceResolver(private val diffResolver: DiffResolverPort) {
  internal fun resolve(
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
