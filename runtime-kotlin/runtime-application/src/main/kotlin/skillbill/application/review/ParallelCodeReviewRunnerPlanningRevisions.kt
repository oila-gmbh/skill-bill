package skillbill.application.review

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.reviewevidence.model.DiffResolutionException
import skillbill.application.reviewevidence.model.ParallelReviewScope
import java.nio.file.Path

internal fun ParallelCodeReviewRunnerPlanning.resolveReviewRevisions(
  request: ParallelCodeReviewRequest,
): Pair<String, String> {
  val (base, head) = if (spansCommitRange(request)) canonicalRange(request) else declaredRange(request)
  if (base.isBlank() || head.isBlank()) {
    throw DiffResolutionException("Review base and head revisions must resolve to non-blank immutable identities.")
  }
  return base to head
}

internal fun ParallelCodeReviewRunnerPlanning.spansCommitRange(request: ParallelCodeReviewRequest): Boolean =
  !hasSuppliedDiff(request) &&
    (request.scope == ParallelReviewScope.BRANCH || request.scope == ParallelReviewScope.PR)

internal fun ParallelCodeReviewRunnerPlanning.hasSuppliedDiff(request: ParallelCodeReviewRequest): Boolean =
  request.suppliedDiff != null || request.suppliedDiffPath != null

internal fun ParallelCodeReviewRunnerPlanning.canonicalRange(
  request: ParallelCodeReviewRequest,
): Pair<String, String> {
  val head = canonicalRevision(request.headRevision ?: PARALLEL_REVIEW_HEAD_REVISION, request.repoRoot)
  val base = request.baseRevision?.let { canonicalRevision(it, request.repoRoot) } ?: when (request.scope) {
    ParallelReviewScope.PR -> detectPrBase(request.repoRoot)
    ParallelReviewScope.STAGED,
    ParallelReviewScope.UNSTAGED,
    ParallelReviewScope.BRANCH,
    ParallelReviewScope.WORKTREE_FROM_BASE,
    -> detectBranchBase(request.repoRoot)
  }
  return base to head
}

internal fun ParallelCodeReviewRunnerPlanning.declaredRange(request: ParallelCodeReviewRequest): Pair<String, String> {
  val head = request.headRevision
    ?: if (hasSuppliedDiff(request)) {
      PARALLEL_REVIEW_HEAD_REVISION
    } else {
      canonicalRevision(PARALLEL_REVIEW_HEAD_REVISION, request.repoRoot)
    }
  return (request.baseRevision ?: head) to head
}

internal fun ParallelCodeReviewRunnerPlanning.canonicalRevision(revision: String, repoRoot: Path): String =
  diffResolver.runProcess(listOf("git", "rev-parse", "--verify", "$revision^{commit}"), repoRoot)
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: throw DiffResolutionException("Review revision '$revision' does not resolve to a commit here.")

internal fun ParallelCodeReviewRunnerPlanning.detectPrBase(repoRoot: Path): String {
  val baseRefOid = diffResolver
    .runProcess(listOf("gh", "pr", "view", "--json", "baseRefOid", "--jq", ".baseRefOid"), repoRoot)
    ?.trim()
    ?.takeIf { it.isNotBlank() }
  val merged = baseRefOid?.let {
    diffResolver.runProcess(listOf("git", "merge-base", "HEAD", it), repoRoot)?.trim()
  }
  return merged?.takeIf { it.isNotBlank() } ?: detectBranchBase(repoRoot)
}

internal fun ParallelCodeReviewRunnerPlanning.detectBranchBase(repoRoot: Path): String {
  val candidates = listOf("main", "master", "origin/main", "origin/master")
  for (candidate in candidates) {
    val result = diffResolver.runProcess(listOf("git", "merge-base", "HEAD", candidate), repoRoot)
    if (result != null) return result.trim()
  }
  throw DiffResolutionException(
    "Could not detect branch base. Tried: ${candidates.joinToString()}.",
  )
}
