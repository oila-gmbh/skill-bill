package skillbill.application.review

import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelReviewScope
import skillbill.ports.diff.DiffResolverPort
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitSource
import skillbill.review.context.model.ReviewCommitUnit
import java.nio.file.Path

internal data class ResolvedCommitSequence(
  val units: List<ReviewCommitUnit>,
  val coverageFact: ReviewCommitCoverageFact,
)

/** Base and head always travel together; naming the pair keeps a range from being split or swapped. */
internal data class ReviewCommitRange(val baseRevision: String, val headRevision: String) {
  val span: String get() = "$baseRevision..$headRevision"
}

/**
 * Ordered per-commit units built from each commit's incremental diff. Reuses the single record
 * parser, so malformed records fail loudly here exactly as they do for an aggregate diff. An
 * empty commit yields a zero-hunk unit rather than vanishing from the sequence.
 */
internal fun parseCommitUnits(commits: List<RawCommitDiff>): List<ReviewCommitUnit> =
  commits.mapIndexed { index, commit ->
    ReviewCommitUnit.ofCommit(
      commitSha = commit.commitSha,
      parentSha = commit.parentSha,
      subject = commit.subject,
      orderIndex = index,
      hunks = if (commit.diff.isBlank()) emptyList() else ReviewDiffEvidence.parse(commit.diff).hunks,
    )
  }

/**
 * Resolves the ordered commit sequence a review packet is built from. All Git access stays here in
 * the parent, behind the existing [DiffResolverPort] seam; workers gain no Git capability.
 */
internal class ReviewCommitSequenceResolver(private val diffResolver: DiffResolverPort) {
  fun resolve(
    scope: ParallelReviewScope,
    repoRoot: Path,
    range: ReviewCommitRange,
    aggregate: ReviewDiffEvidence,
    suppliedDiff: Boolean,
  ): ResolvedCommitSequence {
    val syntheticSource = when {
      suppliedDiff -> ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF
      scope == ParallelReviewScope.STAGED || scope == ParallelReviewScope.UNSTAGED ->
        ReviewCommitSource.SYNTHETIC_WORKING_TREE
      else -> null
    }
    if (syntheticSource != null) {
      return synthetic(syntheticSource, aggregate, range, "non-commit review scope")
    }
    val shas = revList(repoRoot, range)
    if (shas.isEmpty()) {
      // A PR whose commits are not present locally is a declared synthetic source, never a fabricated chain.
      return synthetic(
        ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
        aggregate,
        range,
        "git enumerated no commits for ${range.span} in the local object store",
      )
    }
    val units = parseCommitUnits(shas.map { readCommit(repoRoot, it, range.baseRevision) })
    if (units.first().parentSha != range.baseRevision) {
      // Once main has been merged into the branch, the first-parent walk starts at the original
      // branch point rather than the merge base, so the sequence cannot own the whole delta. That
      // is ordinary topology, not corruption: declare the degradation instead of aborting the review.
      return synthetic(
        ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
        aggregate,
        range,
        "the first-parent sequence for ${range.span} starts at '${units.first().parentSha}', " +
          "not the review base; commit attribution would omit merged-in history",
      )
    }
    verifyCoverage(units, aggregate, range)
    return ResolvedCommitSequence(
      units,
      ReviewCommitCoverageFact(
        range.baseRevision,
        range.headRevision,
        units.size,
        chainVerified = true,
        pathCoverageVerified = true,
      ),
    )
  }

  private fun synthetic(
    source: ReviewCommitSource,
    aggregate: ReviewDiffEvidence,
    range: ReviewCommitRange,
    reason: String,
  ) = ResolvedCommitSequence(
    listOf(ReviewCommitUnit.synthetic(source, aggregate.hunks)),
    ReviewCommitCoverageFact(
      baseRevision = range.baseRevision,
      headRevision = range.headRevision,
      commitCount = 1,
      chainVerified = false,
      pathCoverageVerified = true,
      degradedReason = "single synthetic unit from ${source.name.lowercase()}: $reason",
    ),
  )

  /**
   * A null result is a failed git invocation, never an absent-commit degradation: reporting it as
   * the latter would attach a false degraded_reason to a packet whose sequence was simply unread.
   */
  private fun revList(repoRoot: Path, range: ReviewCommitRange): List<String> {
    val output = diffResolver
      .runProcess(listOf("git", "rev-list", "--first-parent", "--reverse", range.span), repoRoot)
      ?: throw DiffResolutionException("Could not enumerate the commit sequence for ${range.span}.")
    return output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
  }

  private fun readCommit(repoRoot: Path, sha: String, baseRevision: String): RawCommitDiff {
    val metadata = diffResolver.runProcess(listOf("git", "show", "-s", "--format=%P%n%s", sha), repoRoot)
      ?: throw DiffResolutionException("Could not read commit metadata for '$sha'.")
    val lines = metadata.lines()
    // A root commit reports no parent; the review base is then its only meaningful predecessor.
    val parent = lines.firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: baseRevision
    val subject = lines.drop(1).joinToString("\n").trim()
    val diff = diffResolver.runProcess(listOf("git", "diff", parent, sha), repoRoot)
      ?: throw DiffResolutionException("Could not read the incremental diff for commit '$sha'.")
    return RawCommitDiff(sha, parent, subject, diff)
  }

  /**
   * A checked chain-and-ownership property, never textual concatenation equality: every path the
   * authoritative base-to-head delta touches is attributable to some commit in the sequence.
   */
  private fun verifyCoverage(units: List<ReviewCommitUnit>, aggregate: ReviewDiffEvidence, range: ReviewCommitRange) {
    coverageViolation(units, aggregate, range)?.let { throw DiffResolutionException(it) }
  }

  private fun coverageViolation(
    units: List<ReviewCommitUnit>,
    aggregate: ReviewDiffEvidence,
    range: ReviewCommitRange,
  ): String? {
    val shas = units.map { it.commitSha }
    val brokenLink = units.zipWithNext().firstOrNull { (previous, next) -> next.parentSha != previous.commitSha }
    val hunkIds = units.flatMap { it.hunkIds }
    val uncovered = aggregate.hunks.map { it.path }.toSet() -
      units.flatMap { unit -> unit.hunks.map { it.path } }.toSet()
    return when {
      units.last().commitSha != range.headRevision ->
        "Resolved commit sequence does not span ${range.span}; the review delta would be incomplete."
      shas.distinct().size != shas.size -> "Resolved commit sequence lists the same commit more than once."
      brokenLink != null ->
        "Resolved commit sequence is broken between '${brokenLink.first.commitSha}' and " +
          "'${brokenLink.second.commitSha}'."
      hunkIds.distinct().size != hunkIds.size ->
        "Resolved commit sequence attributes the same hunk to more than one commit."
      uncovered.isNotEmpty() ->
        "Resolved commit sequence omits paths the base-to-head delta changes: ${uncovered.sorted()}."
      else -> null
    }
  }
}
