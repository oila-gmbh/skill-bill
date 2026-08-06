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

/**
 * Resolves the ordered commit sequence a review packet is built from. All Git access stays here in
 * the parent, behind the existing [DiffResolverPort] seam; workers gain no Git capability.
 */
internal class ReviewCommitSequenceResolver(private val diffResolver: DiffResolverPort) {
  fun resolve(
    scope: ParallelReviewScope,
    repoRoot: Path,
    baseRevision: String,
    headRevision: String,
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
      return synthetic(syntheticSource, aggregate, baseRevision, headRevision, "non-commit review scope")
    }
    val shas = revList(repoRoot, baseRevision, headRevision)
    if (shas.isEmpty()) {
      // A PR whose commits are not present locally is a declared synthetic source, never a fabricated chain.
      return synthetic(
        ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
        aggregate,
        baseRevision,
        headRevision,
        "git enumerated no commits for $baseRevision..$headRevision in the local object store",
      )
    }
    val units = ReviewDiffEvidence.parseCommitUnits(shas.map { readCommit(repoRoot, it, baseRevision) })
    verifyCoverage(units, aggregate, baseRevision, headRevision)
    return ResolvedCommitSequence(
      units,
      ReviewCommitCoverageFact(
        baseRevision,
        headRevision,
        units.size,
        chainVerified = true,
        pathCoverageVerified = true,
      ),
    )
  }

  private fun synthetic(
    source: ReviewCommitSource,
    aggregate: ReviewDiffEvidence,
    baseRevision: String,
    headRevision: String,
    reason: String,
  ) = ResolvedCommitSequence(
    listOf(ReviewDiffEvidence.syntheticUnit(source, aggregate.hunks)),
    ReviewCommitCoverageFact(
      baseRevision = baseRevision,
      headRevision = headRevision,
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
  private fun revList(repoRoot: Path, baseRevision: String, headRevision: String): List<String> {
    val output = diffResolver
      .runProcess(listOf("git", "rev-list", "--first-parent", "--reverse", "$baseRevision..$headRevision"), repoRoot)
      ?: throw DiffResolutionException(
        "Could not enumerate the commit sequence for $baseRevision..$headRevision.",
      )
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
  private fun verifyCoverage(
    units: List<ReviewCommitUnit>,
    aggregate: ReviewDiffEvidence,
    baseRevision: String,
    headRevision: String,
  ) {
    if (units.first().parentSha != baseRevision || units.last().commitSha != headRevision) {
      throw DiffResolutionException(
        "Resolved commit sequence does not span $baseRevision..$headRevision; the review delta would be incomplete.",
      )
    }
    val shas = units.map { it.commitSha }
    if (shas.distinct().size != shas.size) {
      throw DiffResolutionException("Resolved commit sequence lists the same commit more than once.")
    }
    units.zipWithNext().forEach { (previous, next) ->
      if (next.parentSha != previous.commitSha) {
        throw DiffResolutionException(
          "Resolved commit sequence is broken between '${previous.commitSha}' and '${next.commitSha}'.",
        )
      }
    }
    val hunkIds = units.flatMap { it.hunkIds }
    if (hunkIds.distinct().size != hunkIds.size) {
      throw DiffResolutionException("Resolved commit sequence attributes the same hunk to more than one commit.")
    }
    val uncovered = aggregate.hunks.map { it.path }.toSet() - units.flatMap { unit -> unit.hunks.map { it.path } }
      .toSet()
    if (uncovered.isNotEmpty()) {
      throw DiffResolutionException(
        "Resolved commit sequence omits paths the base-to-head delta changes: ${uncovered.sorted()}.",
      )
    }
  }
}
