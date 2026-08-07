package skillbill.application.evidence

import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelReviewScope
import skillbill.application.review.RawCommitDiff
import skillbill.application.review.ResolvedCommitSequence
import skillbill.application.review.ReviewCommitRange
import skillbill.application.review.ReviewDiffEvidence
import skillbill.ports.diff.DiffResolverPort
import skillbill.review.context.model.ReviewCommitCoverageFact
import skillbill.review.context.model.ReviewCommitSource
import skillbill.review.context.model.ReviewCommitUnit
import java.nio.file.Path

/**
 * The raw repository facts one checkpoint's commit sequence is assembled from: each commit's own
 * incremental diff, or the declared synthetic source when the checkpoint has no attributable commit
 * chain.
 *
 * Derived identities are deliberately absent. The record carries only what Git reported, so
 * rebuilding evidence from a stored record runs the same construction on the same inputs and can
 * never drift from a fresh derivation.
 */
internal data class SharedReviewEvidenceCommits(
  val baseRevision: String,
  val headRevision: String,
  val commits: List<RawCommitDiff>,
  val syntheticSource: ReviewCommitSource?,
  val syntheticReason: String?,
) {
  init {
    require((syntheticSource == null) == (syntheticReason == null)) {
      "A synthetic shared review evidence record must name both its source and its reason."
    }
    require(syntheticSource != null || commits.isNotEmpty()) {
      "A non-synthetic shared review evidence record must carry at least one commit."
    }
  }
}

/** One checkpoint's assembled evidence: the authoritative delta plus the commit sequence over it. */
internal data class SharedReviewEvidenceRecord(
  val aggregateDiff: String,
  val sequence: SharedReviewEvidenceCommits,
)

/**
 * Phase-neutral assembly of one checkpoint's review evidence. All Git access stays here behind the
 * existing [DiffResolverPort] seam; the review phase is one consumer of the result rather than its
 * owner, so the shared deriver can invoke it for any phase.
 */
internal class SharedReviewEvidenceAssembler(private val diffResolver: DiffResolverPort) {
  fun assemble(
    scope: ParallelReviewScope,
    repoRoot: Path,
    range: ReviewCommitRange,
    suppliedDiff: Boolean,
  ): SharedReviewEvidenceCommits {
    val declaredSynthetic = when {
      suppliedDiff -> ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF
      scope == ParallelReviewScope.STAGED || scope == ParallelReviewScope.UNSTAGED ->
        ReviewCommitSource.SYNTHETIC_WORKING_TREE
      else -> null
    }
    if (declaredSynthetic != null) {
      return synthetic(range, declaredSynthetic, "non-commit review scope")
    }
    val shas = revList(repoRoot, range)
    if (shas.isEmpty()) {
      // A PR whose commits are not present locally is a declared synthetic source, never a fabricated chain.
      return synthetic(
        range,
        ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
        "git enumerated no commits for ${range.span} in the local object store",
      )
    }
    val commits = shas.map { readCommit(repoRoot, it, range.baseRevision) }
    if (commits.first().parentSha != range.baseRevision) {
      // Once main has been merged into the branch, the first-parent walk starts at the original
      // branch point rather than the merge base, so the sequence cannot own the whole delta. That
      // is ordinary topology, not corruption: declare the degradation instead of aborting the review.
      return synthetic(
        range,
        ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
        "the first-parent sequence for ${range.span} starts at '${commits.first().parentSha}', " +
          "not the review base; commit attribution would omit merged-in history",
      )
    }
    return SharedReviewEvidenceCommits(
      baseRevision = range.baseRevision,
      headRevision = range.headRevision,
      commits = commits,
      syntheticSource = null,
      syntheticReason = null,
    )
  }

  private fun synthetic(
    range: ReviewCommitRange,
    source: ReviewCommitSource,
    reason: String,
  ) = SharedReviewEvidenceCommits(
    baseRevision = range.baseRevision,
    headRevision = range.headRevision,
    commits = emptyList(),
    syntheticSource = source,
    syntheticReason = reason,
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
}

/**
 * Turns an assembled record into the ordered unit sequence a review packet is built from. Pure over
 * the record, so a record recovered from the shared store projects to byte-identical identities.
 */
internal object SharedReviewEvidenceProjection {
  fun project(record: SharedReviewEvidenceCommits, aggregate: ReviewDiffEvidence): ResolvedCommitSequence {
    val range = ReviewCommitRange(record.baseRevision, record.headRevision)
    record.syntheticSource?.let { source ->
      return ResolvedCommitSequence(
        listOf(ReviewCommitUnit.synthetic(source, aggregate.hunks)),
        ReviewCommitCoverageFact(
          baseRevision = range.baseRevision,
          headRevision = range.headRevision,
          commitCount = 1,
          chainVerified = false,
          pathCoverageVerified = true,
          degradedReason = "single synthetic unit from ${source.name.lowercase()}: ${record.syntheticReason}",
        ),
      )
    }
    val units = parseCommitUnits(record.commits)
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

  /**
   * A checked chain-and-ownership property, never textual concatenation equality: every path the
   * authoritative base-to-head delta touches is attributable to some commit in the sequence.
   */
  private fun verifyCoverage(
    units: List<ReviewCommitUnit>,
    aggregate: ReviewDiffEvidence,
    range: ReviewCommitRange,
  ) {
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
