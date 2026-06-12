package skillbill.infrastructure.fs

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemFeatureTaskRuntimeRunInvariantsSourceTest {
  @Test
  fun `reads explicit governed feature size from spec text`() {
    val spec = writeSpec(
      """
      # Runtime spec

      feature_size: LARGE

      ## Acceptance Criteria
      1. Criterion one.
      """.trimIndent(),
    )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(FeatureTaskRuntimeFeatureSize.LARGE, invariants.featureSize)
  }

  @Test
  fun `defaults omitted feature size to medium`() {
    val spec = writeSpec(
      """
      # Runtime spec

      ## Acceptance Criteria
      1. Criterion one.
      """.trimIndent(),
    )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(FeatureTaskRuntimeFeatureSize.MEDIUM, invariants.featureSize)
  }

  @Test
  fun `rejects malformed explicit feature size instead of defaulting`() {
    val spec = writeSpec(
      """
      # Runtime spec

      feature_size: HUGE

      ## Acceptance Criteria
      1. Criterion one.
      """.trimIndent(),
    )

    assertFailsWith<IllegalArgumentException> {
      FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)
    }
  }

  @Test
  fun `reads explicit governed feature size with an inline comment`() {
    val spec = writeSpec(
      """
      # Runtime spec

      Feature size: SMALL # intentionally scoped

      ## Acceptance Criteria
      1. Criterion one.
      """.trimIndent(),
    )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(FeatureTaskRuntimeFeatureSize.SMALL, invariants.featureSize)
  }

  @Test
  fun `reads numbered acceptance criteria under the canonical heading`() {
    val spec = writeSpec(
      """
      # Runtime spec

      ## Acceptance Criteria
      1. First criterion.
      2. Second criterion.
      """.trimIndent(),
    )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(listOf("First criterion.", "Second criterion."), invariants.acceptanceCriteria)
  }

  @Test
  fun `accepts a heading suffix after acceptance criteria`() {
    val spec = writeSpec(
      """
      # Runtime spec

      ## Acceptance criteria (this subtask)
      1. First criterion.
      """.trimIndent(),
    )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(listOf("First criterion."), invariants.acceptanceCriteria)
  }

  @Test
  fun `accepts bullet acceptance criteria`() {
    val spec = writeSpec(
      """
      # Runtime spec

      ## Acceptance criteria (this subtask)
      - AC1: bullet criterion one.
      - AC2: bullet criterion two.
      """.trimIndent(),
    )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(listOf("AC1: bullet criterion one.", "AC2: bullet criterion two."), invariants.acceptanceCriteria)
  }

  @Test
  fun `accepts checkbox acceptance criteria and strips the marker`() {
    val spec = writeSpec(
      """
      # Runtime spec

      ## Acceptance Criteria
      - [ ] unchecked criterion.
      - [x] checked criterion.
      """.trimIndent(),
    )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(listOf("unchecked criterion.", "checked criterion."), invariants.acceptanceCriteria)
  }

  @Test
  fun `does not harvest criteria from a non-acceptance heading`() {
    val spec = writeSpec(
      """
      # Runtime spec

      ## Scope
      1. Not an acceptance criterion.
      """.trimIndent(),
    )

    assertFailsWith<IllegalArgumentException> {
      FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)
    }
  }

  private fun writeSpec(text: String) =
    Files.createTempDirectory("feature-task-runtime-invariants").resolve("spec.md").also { path ->
      Files.writeString(path, text)
    }
}
