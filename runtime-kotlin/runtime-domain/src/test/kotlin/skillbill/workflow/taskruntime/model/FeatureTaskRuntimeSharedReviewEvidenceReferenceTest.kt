package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeSharedReviewEvidenceReferenceTest {
  @Test
  fun `declared field names are exactly the five reference fields`() {
    assertEquals(
      listOf("store_path", "checkpoint_fingerprint", "base_ref", "head_ref", "file_hunk_index"),
      FeatureTaskRuntimeSharedReviewEvidenceReference.DECLARED_FIELD_NAMES,
    )
  }

  @Test
  fun `every rendered field is inside the allowlist and none can carry diff text`() {
    val fields = reference().toProjectionFields()
    val allowlist = FeatureTaskRuntimeSharedReviewEvidenceReference.DECLARED_FIELD_NAMES
    assertEquals(allowlist, fields.map { it.name })
    fields.forEach { field ->
      assertTrue(field.name in allowlist, "${field.name} is outside the declared allowlist")
      assertTrue(field.name !in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES)
      assertTrue(
        field.value is FeatureTaskRuntimeHandoffProjectionValue.CompactReference ||
          field.value is FeatureTaskRuntimeHandoffProjectionValue.Text ||
          field.value is FeatureTaskRuntimeHandoffProjectionValue.TextList,
      )
    }
    val storePath = fields.single { it.name == "store_path" }.value
    assertEquals(
      FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
      (storePath as FeatureTaskRuntimeHandoffProjectionValue.CompactReference).kind,
    )
    val fingerprint = fields.single { it.name == "checkpoint_fingerprint" }.value
    assertEquals(
      FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
      (fingerprint as FeatureTaskRuntimeHandoffProjectionValue.CompactReference).kind,
    )
  }

  @Test
  fun `an unnamed artifact or checkpoint is rejected rather than delivered blank`() {
    assertFailsWith<IllegalArgumentException> { reference(storePath = " ") }
    assertFailsWith<IllegalArgumentException> { reference(fingerprint = " ") }
  }

  @Test
  fun `the index records hunk counts per file so its size tracks file count not diff size`() {
    val small = FeatureTaskRuntimeSharedReviewEvidenceReference.of("store", artifact(hunksPerFile = 1))
    val large = FeatureTaskRuntimeSharedReviewEvidenceReference.of("store", artifact(hunksPerFile = 400))
    assertEquals(small.fileHunkIndex.size, large.fileHunkIndex.size)
    assertEquals(listOf("modified a.kt hunks=1"), small.fileHunkIndex)
    assertEquals(listOf("modified a.kt hunks=400"), large.fileHunkIndex)
  }

  @Test
  fun `an index past the file cap declares the omission instead of silently truncating`() {
    val files = (1..FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT + 10).map {
      FeatureTaskRuntimeSharedEvidenceFileEntry("f$it.kt", "modified")
    }
    val reference = FeatureTaskRuntimeSharedReviewEvidenceReference.of(
      "store",
      artifact().copy(files = files, hunks = emptyList()),
    )
    assertEquals(FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT, reference.fileHunkIndex.size)
    assertEquals("omitted 11 further changed files", reference.fileHunkIndex.last())
  }

  private fun artifact(hunksPerFile: Int = 1) = FeatureTaskRuntimeSharedEvidenceArtifact(
    fingerprint = "fp",
    baseRef = "base",
    headRef = "head",
    files = listOf(FeatureTaskRuntimeSharedEvidenceFileEntry("a.kt", "modified")),
    hunks = (1..hunksPerFile).map { FeatureTaskRuntimeSharedEvidenceHunkEntry("a.kt", "@@ -$it +$it @@") },
    diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef("diff.patch", 1),
  )

  private fun reference(storePath: String = "store", fingerprint: String = "fp") =
    FeatureTaskRuntimeSharedReviewEvidenceReference(
      storePath = storePath,
      checkpointFingerprint = fingerprint,
      baseRef = "base",
      headRef = "head",
      fileHunkIndex = listOf("modified a.kt hunks=1"),
    )
}
