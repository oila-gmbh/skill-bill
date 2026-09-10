package skillbill.application.review

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.reviewevidence.model.DiffResolutionException
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.model.ReviewEvidenceCoordinates

internal fun ParallelCodeReviewRunnerPlanning.evidenceCoordinates(
  request: ParallelCodeReviewRequest,
  head: String,
): ReviewEvidenceCoordinates {
  if (hasSuppliedDiff(request) || request.scope in setOf(ParallelReviewScope.BRANCH, ParallelReviewScope.PR)) {
    return ReviewEvidenceCoordinates.Committed(head)
  }
  val index = diffResolver.runProcess(listOf("git", "ls-files", "--stage", "-z"), request.repoRoot)
    ?: throw DiffResolutionException("Cannot capture the reviewed index.")
  val entries = index.split('\u0000').filter(String::isNotEmpty).associate { row ->
    val metadata = row.substringBefore('\t').split(' ')
    if (metadata.size != INDEX_ENTRY_METADATA_FIELDS || metadata[2] != "0" || '\t' !in row) {
      throw DiffResolutionException("Review checkpoint contains unresolved index entries.")
    }
    row.substringAfter('\t') to ReviewCheckpointFileIdentity.Regular(metadata[1])
  }
  if (request.scope == ParallelReviewScope.STAGED) {
    return ReviewEvidenceCoordinates.Checkpoint(ReviewEvidenceCoordinates.Checkpoint.Kind.INDEX, entries)
  }
  val files = diffResolver.reviewWorktreeFileIdentities(request.repoRoot, entries.keys.toList())
  return ReviewEvidenceCoordinates.Checkpoint(ReviewEvidenceCoordinates.Checkpoint.Kind.WORKTREE, files)
}

private const val INDEX_ENTRY_METADATA_FIELDS = 3
