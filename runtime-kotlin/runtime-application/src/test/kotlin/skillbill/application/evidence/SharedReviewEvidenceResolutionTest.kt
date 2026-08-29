package skillbill.application.evidence

import skillbill.application.review.ReviewCommitRange
import skillbill.application.review.ReviewDiffEvidence
import skillbill.application.review.model.ParallelReviewScope
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.review.context.model.REVIEW_SYNTHETIC_COMMIT_PREFIX
import skillbill.review.context.model.ReviewCommitSource
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Hermetic fixtures over a fake process seam and an in-memory store honouring the resolver port's
 * declared outcomes; no live Git invocation and no filesystem.
 */
class SharedReviewEvidenceResolutionTest {
  private val repoRoot: Path = Path.of(".")
  private val range = ReviewCommitRange("base", "head")

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

  /** Fingerprint-keyed, in-memory, and faithful to the port: a hit never invokes the deriver. */
  private class InMemoryStore(
    private val corruptPayload: Boolean = false,
  ) : FeatureTaskRuntimeSharedEvidenceResolverPort {
    private val stored = mutableMapOf<String, FeatureTaskRuntimeSharedEvidenceResolution>()
    var derivations: Int = 0
      private set

    override fun resolve(
      request: FeatureTaskRuntimeSharedEvidenceRequest,
      deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
    ): FeatureTaskRuntimeSharedEvidenceResolution {
      val fingerprint = request.checkpoint.fingerprint
      stored[fingerprint]?.let { return it }
      derivations++
      val derivation = deriver.derive(request.checkpoint)
      val payload = if (corruptPayload) "corrupted cache entry" else derivation.diffPayload
      val resolution = FeatureTaskRuntimeSharedEvidenceResolution(
        artifact = FeatureTaskRuntimeSharedEvidenceArtifact(
          fingerprint = fingerprint,
          baseRef = derivation.baseRef,
          headRef = derivation.headRef,
          files = derivation.files,
          hunks = derivation.hunks,
          diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef("diff.patch", payload.length.toLong()),
        ),
        diffPayload = payload,
        storePath = ".skill-bill/run-evidence/${request.workflowId}/$fingerprint",
      )
      stored[fingerprint] = resolution
      return resolution
    }
  }

  private fun branchGit(shas: List<String>, parents: Map<String, String>, diffs: Map<String, String>): FakeGit {
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

  private fun twoCommitGit(): Pair<FakeGit, String> {
    val diffs = mapOf("c1" to diffFor("src/c1.kt", "one"), "head" to diffFor("src/head.kt", "two"))
    val git = branchGit(listOf("c1", "head"), mapOf("c1" to "base", "head" to "c1"), diffs)
    return git to (diffs.getValue("c1") + "\n" + diffs.getValue("head"))
  }

  private fun queryOf(
    scope: ParallelReviewScope = ParallelReviewScope.BRANCH,
    supplied: Boolean = false,
    workflowId: String = "wf-1",
  ) = SharedReviewEvidenceQuery(repoRoot, workflowId, scope, range, supplied)

  private fun resolve(
    store: FeatureTaskRuntimeSharedEvidenceResolverPort,
    git: DiffResolverPort,
    aggregate: String,
    query: SharedReviewEvidenceQuery = queryOf(),
    aggregateReads: MutableList<String>? = null,
  ) = SharedReviewEvidenceResolution(store, git).resolve(query) {
    aggregateReads?.add("aggregate")
    aggregate
  }

  // AC-002, AC-003: N consumers over one checkpoint derive exactly once and traverse nothing after.
  @Test fun `a fingerprint hit serves the stored evidence with zero repository traversal`() {
    val store = InMemoryStore()
    val (first, aggregate) = twoCommitGit()
    val reads = mutableListOf<String>()

    val derived = resolve(store, first, aggregate, aggregateReads = reads)
    val lanes = (1..4).map {
      val lane = twoCommitGit().first
      resolve(store, lane, aggregate, aggregateReads = reads) to lane
    }

    assertEquals(1, store.derivations)
    assertEquals(1, reads.size)
    assertTrue(lanes.all { it.second.invoked.isEmpty() }, "a lane reading the stored artifact must not shell out")
    assertTrue(lanes.all { it.first == derived })
  }

  // AC-004: identities rebuilt from the store are byte-identical to in-line derivation.
  @Test fun `identities projected from stored evidence match in-line derivation byte for byte`() {
    val store = InMemoryStore()
    val (git, aggregate) = twoCommitGit()
    val parsed = ReviewDiffEvidence.parse(aggregate)

    val inLine = SharedReviewEvidenceProjection.project(
      resolve(FeatureTaskRuntimeSharedEvidenceResolverPort.NONE, git, aggregate).sequence,
      parsed,
    )
    resolve(store, twoCommitGit().first, aggregate)
    val fromStore = SharedReviewEvidenceProjection.project(
      resolve(store, twoCommitGit().first, aggregate).sequence,
      parsed,
    )

    assertEquals(inLine.units.map { it.commitUnitId }, fromStore.units.map { it.commitUnitId })
    assertEquals(inLine.units.flatMap { it.hunkIds }, fromStore.units.flatMap { it.hunkIds })
    assertEquals(inLine.coverageFact, fromStore.coverageFact)
    assertEquals(2, fromStore.units.size)
  }

  // AC-007: synthetic identities and the sole-unit ordering invariant survive the store round trip.
  @Test fun `every synthetic source keeps its placeholder identity and sole-unit ordering`() {
    val aggregate = diffFor("src/A.kt", "alpha")
    val parsed = ReviewDiffEvidence.parse(aggregate)
    val cases = listOf(
      Triple(ParallelReviewScope.STAGED, false, ReviewCommitSource.SYNTHETIC_WORKING_TREE),
      Triple(ParallelReviewScope.BRANCH, true, ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF),
      Triple(ParallelReviewScope.PR, false, ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF),
    )

    cases.forEach { (scope, supplied, expected) ->
      val store = InMemoryStore()
      val git = FakeGit(mapOf("git rev-list --first-parent --reverse base..head" to ""))
      val query = queryOf(scope = scope, supplied = supplied, workflowId = scope.name)
      val first = resolve(store, git, aggregate, query)
      val reloaded = resolve(store, git, aggregate, query)

      val projected = SharedReviewEvidenceProjection.project(reloaded.sequence, parsed)
      val unit = projected.units.single()
      assertEquals(expected, unit.source, scope.name)
      assertTrue(unit.commitSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX), unit.commitSha)
      assertTrue(unit.parentSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX), unit.parentSha)
      assertEquals(0, unit.orderIndex)
      assertEquals(
        SharedReviewEvidenceProjection.project(first.sequence, parsed).coverageFact.degradedReason,
        projected.coverageFact.degradedReason,
      )
    }
  }

  // AC-002: a corrupt payload re-derives in line and still yields a complete sequence.
  @Test fun `a corrupt stored payload degrades to in-line derivation rather than failing the review`() {
    val store = InMemoryStore(corruptPayload = true)
    val (seed, aggregate) = twoCommitGit()
    resolve(store, seed, aggregate)
    val fallback = twoCommitGit().first

    val record = resolve(store, fallback, aggregate)

    val projected = SharedReviewEvidenceProjection.project(record.sequence, ReviewDiffEvidence.parse(aggregate))
    assertTrue(fallback.invoked.isNotEmpty(), "a corrupt cache entry must re-derive")
    assertEquals(2, projected.units.size)
  }

  // AC-005, AC-006: a commit range is checkpoint-keyed; a working-tree scope never reuses a range key.
  @Test fun `only an immutable commit range is checkpoint-keyed`() {
    val store = InMemoryStore()
    val aggregate = diffFor("src/A.kt", "alpha")
    val noGit = FakeGit(mapOf("git rev-list --first-parent --reverse base..head" to ""))

    resolve(store, noGit, aggregate, queryOf(scope = ParallelReviewScope.BRANCH))
    resolve(store, noGit, aggregate, queryOf(scope = ParallelReviewScope.PR))
    val branchAndPr = store.derivations
    resolve(store, noGit, aggregate, queryOf(scope = ParallelReviewScope.STAGED))
    resolve(store, noGit, aggregate, queryOf(scope = ParallelReviewScope.UNSTAGED))

    assertEquals(2, branchAndPr)
    assertTrue(store.derivations > branchAndPr)
  }

  // AC-005: a supplied diff is not identified by its declared range, so it persists under a payload fingerprint.
  @Test fun `a supplied diff review derives in line rather than reusing a range-keyed artifact`() {
    val store = InMemoryStore()
    val noGit = FakeGit(mapOf("git rev-list --first-parent --reverse base..head" to ""))
    val first = diffFor("src/A.kt", "alpha")
    val second = diffFor("src/B.kt", "beta")

    val firstRecord = resolve(store, noGit, first, queryOf(supplied = true))
    val secondRecord = resolve(store, noGit, second, queryOf(supplied = true))

    assertEquals(2, store.derivations)
    assertEquals(first, firstRecord.aggregateDiff)
    assertEquals(second, secondRecord.aggregateDiff)
    assertTrue(firstRecord.storePath!!.startsWith(".skill-bill/run-evidence/wf-1/"))
    assertTrue(secondRecord.storePath!!.startsWith(".skill-bill/run-evidence/wf-1/"))
    assertNotEquals(firstRecord.storePath, secondRecord.storePath)
  }

  // AC-004: a different range is a different checkpoint, never a stale hit.
  @Test fun `a different range resolves a different checkpoint`() {
    val store = InMemoryStore()
    val (git, aggregate) = twoCommitGit()
    resolve(store, git, aggregate)

    val otherGit = FakeGit(mapOf("git rev-list --first-parent --reverse base..other-head" to ""))
    val otherRange = SharedReviewEvidenceResolution(store, otherGit).resolve(
      SharedReviewEvidenceQuery(
        repoRoot = repoRoot,
        workflowId = "wf-1",
        scope = ParallelReviewScope.BRANCH,
        range = ReviewCommitRange("base", "other-head"),
        suppliedDiff = false,
      ),
    ) { aggregate }

    assertEquals(2, store.derivations)
    assertNotEquals("head", otherRange.sequence.headRevision)
  }

  @Test fun `standalone and feature-task locators share the run-evidence workflow layout`() {
    val store = InMemoryStore()
    val (git, aggregate) = twoCommitGit()
    val standalone = resolve(store, git, aggregate, queryOf(workflowId = "code-review"))
    val featureTask = resolve(
      InMemoryStore(),
      twoCommitGit().first,
      aggregate,
      queryOf(workflowId = "wftr-1"),
    )
    val standalonePath = checkNotNull(standalone.storePath)
    val featureTaskPath = checkNotNull(featureTask.storePath)
    assertTrue(standalonePath.startsWith(".skill-bill/run-evidence/code-review/"))
    assertTrue(featureTaskPath.startsWith(".skill-bill/run-evidence/wftr-1/"))
    assertTrue(standalonePath.endsWith(standalonePath.substringAfterLast('/')))
    assertEquals(
      standalonePath.count { it == '/' },
      featureTaskPath.count { it == '/' },
    )
  }
}
