package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeSharedEvidenceModelsTest {
  @Test
  fun `carries fingerprint refs indexes and payload reference`() {
    val artifact = FeatureTaskRuntimeSharedEvidenceArtifact(
      fingerprint = "fp-1",
      baseRef = "main",
      headRef = "feature",
      files = listOf(FeatureTaskRuntimeSharedEvidenceFileEntry("src/A.kt", "modified")),
      hunks = listOf(FeatureTaskRuntimeSharedEvidenceHunkEntry("src/A.kt", "@@ -1,2 +1,3 @@")),
      diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef("diff.patch", 42),
    )

    assertEquals("fp-1", artifact.fingerprint)
    assertEquals("main", artifact.baseRef)
    assertEquals("feature", artifact.headRef)
    assertEquals("src/A.kt", artifact.files.single().path)
    assertEquals("modified", artifact.files.single().changeKind)
    assertEquals("@@ -1,2 +1,3 @@", artifact.hunks.single().header)
    assertEquals("diff.patch", artifact.diffPayload.relativePath)
    assertEquals(42, artifact.diffPayload.sizeBytes)
  }

  @Test
  fun `rejects a blank fingerprint`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeSharedEvidenceArtifact(
        fingerprint = "  ",
        baseRef = null,
        headRef = null,
        files = emptyList(),
        hunks = emptyList(),
        diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef("diff.patch", 0),
      )
    }
  }

  @Test
  fun `rejects blank index entries and payload reference`() {
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeSharedEvidenceFileEntry(" ", "modified") }
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeSharedEvidenceFileEntry("src/A.kt", " ") }
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeSharedEvidenceHunkEntry(" ", "@@") }
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeSharedEvidenceHunkEntry("src/A.kt", " ") }
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(" ", 1) }
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeSharedEvidenceDiffPayloadRef("diff.patch", -1) }
  }
}
