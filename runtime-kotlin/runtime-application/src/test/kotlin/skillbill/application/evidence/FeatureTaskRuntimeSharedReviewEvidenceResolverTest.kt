package skillbill.application.evidence

import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Application-layer resolve seam for the shared review evidence projection. Reuse and re-derivation
 * are the port's fingerprint-keyed outcomes; these tests pin that audit_gap / review_fix re-entry
 * at an unchanged fingerprint reuses, and a moved tree (new fingerprint) re-derives, with no new
 * invalidation concept.
 */
class FeatureTaskRuntimeSharedReviewEvidenceResolverTest {
  private val repoRoot: Path = Path.of(".")

  private fun diffFor(path: String, line: String, hunks: Int = 1) = buildString {
    appendLine("diff --git a/$path b/$path")
    appendLine("--- a/$path")
    appendLine("+++ b/$path")
    repeat(hunks) { index ->
      appendLine("@@ -$index,1 +$index,1 @@")
      appendLine("+$line-$index")
    }
  }

  private class FakeGit(private val responses: Map<String, String?>) : DiffResolverPort {
    val invoked: MutableList<String> = mutableListOf()
    override fun runProcess(args: List<String>, workDir: Path): String? {
      val key = args.joinToString(" ")
      invoked += key
      return responses[key]
    }
  }

  /** Fingerprint-keyed store faithful to the port: a hit never invokes the deriver. */
  private class InMemoryStore : FeatureTaskRuntimeSharedEvidenceResolverPort {
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
      val resolution = FeatureTaskRuntimeSharedEvidenceResolution(
        artifact = FeatureTaskRuntimeSharedEvidenceArtifact(
          fingerprint = fingerprint,
          baseRef = derivation.baseRef,
          headRef = derivation.headRef,
          files = derivation.files,
          hunks = derivation.hunks,
          diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(
            "diff.patch",
            derivation.diffPayload.length.toLong(),
          ),
        ),
        diffPayload = derivation.diffPayload,
        storePath = ".skill-bill/run-evidence/${request.workflowId}/$fingerprint",
      )
      stored[fingerprint] = resolution
      return resolution
    }
  }

  private fun checkpoint(fingerprint: String, base: String = "base", head: String = "head") =
    FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = fingerprint,
      baseRef = base,
      headRef = head,
    )

  private fun gitFor(base: String, head: String, path: String, line: String, hunks: Int = 1) =
    FakeGit(mapOf("git diff $base $head" to diffFor(path, line, hunks)))

  @Test
  fun `an absent artifact re-derives and the launch still receives a reference`() {
    val store = InMemoryStore()
    val git = gitFor("base", "head", "a.kt", "added")
    val resolver = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, git)

    val reference = resolver.resolve(repoRoot, "wf-1", checkpoint("fp-absent"))

    assertNotNull(reference)
    assertEquals(1, store.derivations)
    assertEquals(".skill-bill/run-evidence/wf-1/fp-absent", reference.storePath)
    assertEquals("fp-absent", reference.checkpointFingerprint)
    assertTrue(reference.fileHunkIndex.any { "a.kt" in it })
  }

  @Test
  fun `a resolvable artifact at a matching fingerprint is reused with no repository traversal`() {
    val store = InMemoryStore()
    val firstGit = gitFor("base", "head", "a.kt", "one")
    val resolver = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, firstGit)
    val first = resolver.resolve(repoRoot, "wf-1", checkpoint("fp-hit"))
    assertNotNull(first)
    assertEquals(1, store.derivations)
    assertEquals(1, firstGit.invoked.size)

    val secondGit = FakeGit(emptyMap())
    val reused = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, secondGit)
      .resolve(repoRoot, "wf-1", checkpoint("fp-hit"))

    assertNotNull(reused)
    assertEquals(first.storePath, reused.storePath)
    assertEquals(first.checkpointFingerprint, reused.checkpointFingerprint)
    assertEquals(1, store.derivations, "a fingerprint hit must not re-derive")
    assertTrue(secondGit.invoked.isEmpty(), "a fingerprint hit must not touch the repository")
  }

  @Test
  fun `audit_gap re-entry at an unchanged checkpoint reuses and a moved tree re-derives`() {
    val store = InMemoryStore()
    val firstGit = gitFor("base", "head", "a.kt", "before")
    val resolver = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, firstGit)
    val before = resolver.resolve(repoRoot, "wf-audit-gap", checkpoint("fp-unchanged"))
    assertNotNull(before)

    // Unchanged checkpoint fingerprint: audit_gap re-entry reuses the stored artifact.
    val reuseGit = FakeGit(emptyMap())
    val reused = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, reuseGit)
      .resolve(repoRoot, "wf-audit-gap", checkpoint("fp-unchanged"))
    assertNotNull(reused)
    assertEquals(before.checkpointFingerprint, reused.checkpointFingerprint)
    assertEquals(1, store.derivations)
    assertTrue(reuseGit.invoked.isEmpty())

    // Remediation moved the tree: a new fingerprint forces re-derivation.
    val movedGit = gitFor("base", "head", "b.kt", "after")
    val moved = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, movedGit)
      .resolve(repoRoot, "wf-audit-gap", checkpoint("fp-moved"))
    assertNotNull(moved)
    assertNotEquals(before.checkpointFingerprint, moved.checkpointFingerprint)
    assertEquals(2, store.derivations)
    assertEquals(1, movedGit.invoked.size)
  }

  @Test
  fun `review_fix re-entry reuses or re-derives by fingerprint with no added invalidation branch`() {
    // MUST_MATCH on the review_fix edge substitutes a freshly resolved checkpoint rather than
    // rejecting movement; reuse is still fingerprint equality alone — no new invalidation concept.
    val store = InMemoryStore()
    val first = FeatureTaskRuntimeSharedReviewEvidenceResolver(
      store,
      gitFor("base", "head", "fix.kt", "round-1"),
    ).resolve(repoRoot, "wf-review-fix", checkpoint("fp-review-1"))
    assertNotNull(first)

    val same = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, FakeGit(emptyMap()))
      .resolve(repoRoot, "wf-review-fix", checkpoint("fp-review-1"))
    assertNotNull(same)
    assertEquals(first.checkpointFingerprint, same.checkpointFingerprint)
    assertEquals(1, store.derivations)

    val next = FeatureTaskRuntimeSharedReviewEvidenceResolver(
      store,
      gitFor("base", "head", "fix.kt", "round-2"),
    ).resolve(repoRoot, "wf-review-fix", checkpoint("fp-review-2"))
    assertNotNull(next)
    assertNotEquals(first.checkpointFingerprint, next.checkpointFingerprint)
    assertEquals(2, store.derivations)
  }

  @Test
  fun `a resolution that cannot produce a store path yields null so the launch still succeeds`() {
    val blankStore = FeatureTaskRuntimeSharedEvidenceResolverPort { request, deriver ->
      val derivation = deriver.derive(request.checkpoint)
      FeatureTaskRuntimeSharedEvidenceResolution(
        artifact = FeatureTaskRuntimeSharedEvidenceArtifact(
          fingerprint = request.checkpoint.fingerprint,
          baseRef = derivation.baseRef,
          headRef = derivation.headRef,
          files = derivation.files,
          hunks = derivation.hunks,
          diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef("diff.patch", 0),
        ),
        diffPayload = derivation.diffPayload,
        storePath = null,
      )
    }
    val reference = FeatureTaskRuntimeSharedReviewEvidenceResolver(
      blankStore,
      gitFor("base", "head", "a.kt", "x"),
    ).resolve(repoRoot, "wf-1", checkpoint("fp"))

    assertNull(reference)
  }

  @Test
  fun `a fingerprint contradiction from the port loud-fails instead of becoming a silent null omit`() {
    val contradicted = FeatureTaskRuntimeSharedEvidenceResolverPort { _, _ ->
      throw FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError(
        addressedFingerprint = "fp-addressed",
        recordedFingerprint = "fp-recorded",
        sourceLabel = "envelope.json",
      )
    }
    val error = assertFailsWith<FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError> {
      FeatureTaskRuntimeSharedReviewEvidenceResolver(
        contradicted,
        FakeGit(emptyMap()),
      ).resolve(repoRoot, "wf-1", checkpoint("fp-addressed"))
    }
    assertEquals("fp-addressed", error.addressedFingerprint)
    assertEquals("fp-recorded", error.recordedFingerprint)
  }

  @Test
  fun `serialized reference size tracks file count not diff size`() {
    val store = InMemoryStore()
    fun resolve(hunks: Int) = FeatureTaskRuntimeSharedReviewEvidenceResolver(
      store,
      gitFor("base", "head", "a.kt", "line", hunks),
    ).resolve(repoRoot, "wf-size", checkpoint("fp-$hunks"))!!

    val small = resolve(1)
    val large = resolve(200)
    assertEquals(small.fileHunkIndex.size, large.fileHunkIndex.size)
    assertTrue(small.fileHunkIndex.single().startsWith("modified a.kt hunks="))
    assertTrue(large.fileHunkIndex.single().startsWith("modified a.kt hunks="))
  }
}
