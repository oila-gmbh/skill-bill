package skillbill.application.review

import skillbill.application.model.DiffResolutionException
import skillbill.application.model.ParallelReviewScope
import skillbill.ports.diff.DiffResolverPort
import skillbill.review.context.model.ReviewCommitSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Hermetic fixtures over a fake process seam; no live Git invocation. */
class ReviewCommitSequenceResolverTest {
  private val repoRoot: Path = Path.of(".")

  private fun diffFor(path: String, line: String) = """
    diff --git a/$path b/$path
    --- a/$path
    +++ b/$path
    @@ -1,1 +1,2 @@
    +$line
  """.trimIndent()

  private class FakeGit(private val responses: Map<String, String?>) : DiffResolverPort {
    val invoked: MutableList<String> = mutableListOf()
    override fun runProcess(args: List<String>, workDir: Path): String? {
      val key = args.joinToString(" ")
      invoked += key
      return responses[key]
    }
  }

  private fun branchRepo(
    shas: List<String>,
    diffs: Map<String, String>,
    parents: Map<String, String>,
  ): FakeGit {
    val responses = mutableMapOf<String, String?>(
      "git rev-list --first-parent --reverse base..head" to shas.joinToString("\n"),
    )
    shas.forEach { sha ->
      val parent = parents.getValue(sha)
      responses["git show -s --format=%P%n%s $sha"] = "$parent\nsubject $sha"
      responses["git diff $parent $sha"] = diffs.getValue(sha)
    }
    return FakeGit(responses)
  }

  private fun sixCommitRepo(): FakeGit {
    val shas = (1..5).map { "c$it" } + "head"
    val parents = shas.mapIndexed { index, sha -> sha to if (index == 0) "base" else shas[index - 1] }.toMap()
    val diffs = shas.associateWith { diffFor("src/${it}.kt", "line-$it") }
    return branchRepo(shas, diffs, parents)
  }

  private fun resolve(
    git: DiffResolverPort,
    scope: ParallelReviewScope,
    aggregateDiff: String,
    supplied: Boolean = false,
  ) =
    ReviewCommitSequenceResolver(git).resolve(
      scope,
      repoRoot,
      "base",
      "head",
      ReviewDiffEvidence.parse(aggregateDiff),
      supplied,
    )

  // AC-001, AC-004
  @Test fun `a six commit branch resolves an ordered first-parent sequence`() {
    val aggregate = (1..5).joinToString("\n") { diffFor("src/c$it.kt", "line-c$it") } +
      "\n" + diffFor("src/head.kt", "line-head")
    val resolved = resolve(sixCommitRepo(), ParallelReviewScope.BRANCH, aggregate)
    assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "head"), resolved.units.map { it.commitSha })
    assertEquals(listOf(0, 1, 2, 3, 4, 5), resolved.units.map { it.orderIndex })
    assertEquals("base", resolved.units.first().parentSha)
    assertTrue(resolved.coverageFact.chainVerified && resolved.coverageFact.pathCoverageVerified)
    assertEquals(6, resolved.coverageFact.commitCount)
    assertTrue(resolved.units.all { it.source == ReviewCommitSource.COMMIT_RANGE })
  }

  // AC-001, AC-007
  @Test fun `a merge commit is traversed by first parent only`() {
    val git = sixCommitRepo()
    resolve(
      git,
      ParallelReviewScope.BRANCH,
      (1..5).joinToString("\n") { diffFor("src/c$it.kt", "line-c$it") } + "\n" + diffFor("src/head.kt", "line-head"),
    )
    assertTrue(git.invoked.any { it == "git rev-list --first-parent --reverse base..head" })
  }

  // AC-007
  @Test fun `an empty commit stays in the sequence as a zero-hunk unit`() {
    val shas = listOf("c1", "head")
    val git = branchRepo(
      shas,
      mapOf("c1" to "", "head" to diffFor("src/A.kt", "alpha")),
      mapOf("c1" to "base", "head" to "c1"),
    )
    val resolved = resolve(git, ParallelReviewScope.BRANCH, diffFor("src/A.kt", "alpha"))
    assertEquals(2, resolved.units.size)
    assertEquals(emptyList(), resolved.units.first().hunks)
  }

  // AC-004
  @Test fun `a sequence that omits a changed path fails loudly`() {
    val git = branchRepo(
      listOf("head"),
      mapOf("head" to diffFor("src/A.kt", "alpha")),
      mapOf("head" to "base"),
    )
    val aggregate = diffFor("src/A.kt", "alpha") + "\n" + diffFor("src/Dropped.kt", "gone")
    val failure = assertFailsWith<DiffResolutionException> {
      resolve(git, ParallelReviewScope.BRANCH, aggregate)
    }
    assertTrue("src/Dropped.kt" in failure.message.orEmpty())
  }

  // AC-004
  @Test fun `a duplicated commit fails loudly`() {
    val diff = diffFor("src/A.kt", "alpha")
    val responses = mutableMapOf<String, String?>(
      "git rev-list --first-parent --reverse base..head" to "head\nhead",
      "git show -s --format=%P%n%s head" to "base\nsubject head",
      "git diff base head" to diff,
    )
    val failure = assertFailsWith<DiffResolutionException> {
      resolve(FakeGit(responses), ParallelReviewScope.BRANCH, diff)
    }
    assertTrue("more than once" in failure.message.orEmpty())
  }

  // AC-001: byte-identical hunks in two different commits are distinct, commit-owned evidence.
  @Test fun `identical hunks in two commits keep distinct commit-scoped identities`() {
    val duplicate = diffFor("src/A.kt", "alpha")
    val git = branchRepo(
      listOf("c1", "head"),
      mapOf("c1" to duplicate, "head" to duplicate),
      mapOf("c1" to "base", "head" to "c1"),
    )
    val resolved = resolve(git, ParallelReviewScope.BRANCH, duplicate)
    val ids = resolved.units.flatMap { it.hunkIds }
    assertEquals(2, ids.size)
    assertEquals(2, ids.distinct().size)
  }

  // AC-001: a failed git invocation is not an absent-commit degradation.
  @Test fun `a failed rev-list fails loudly instead of degrading to a synthetic unit`() {
    val failure = assertFailsWith<DiffResolutionException> {
      resolve(FakeGit(emptyMap()), ParallelReviewScope.BRANCH, diffFor("src/A.kt", "alpha"))
    }
    assertTrue("enumerate the commit sequence" in failure.message.orEmpty())
  }

  // AC-004
  @Test fun `a sequence that does not reach head fails loudly`() {
    val git = branchRepo(
      listOf("c1"),
      mapOf("c1" to diffFor("src/A.kt", "alpha")),
      mapOf("c1" to "base"),
    )
    assertFailsWith<DiffResolutionException> {
      resolve(git, ParallelReviewScope.BRANCH, diffFor("src/A.kt", "alpha"))
    }
  }

  // AC-005
  @Test fun `non-commit and locally-absent sources produce exactly one declared synthetic unit`() {
    val aggregate = diffFor("src/A.kt", "alpha")
    val noGit = FakeGit(emptyMap())
    listOf(
      ParallelReviewScope.STAGED to ReviewCommitSource.SYNTHETIC_WORKING_TREE,
      ParallelReviewScope.UNSTAGED to ReviewCommitSource.SYNTHETIC_WORKING_TREE,
    ).forEach { (scope, expected) ->
      val resolved = resolve(noGit, scope, aggregate)
      assertEquals(1, resolved.units.size)
      assertEquals(expected, resolved.units.single().source)
      assertTrue(resolved.coverageFact.degradedReason!!.isNotBlank())
    }
    assertEquals(
      ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF,
      resolve(noGit, ParallelReviewScope.BRANCH, aggregate, supplied = true).units.single().source,
    )
    // A PR whose commits are not present locally never fabricates a chain.
    assertEquals(
      ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
      resolve(
        FakeGit(mapOf("git rev-list --first-parent --reverse base..head" to "")),
        ParallelReviewScope.PR,
        aggregate,
      ).units.single().source,
    )
  }
}
