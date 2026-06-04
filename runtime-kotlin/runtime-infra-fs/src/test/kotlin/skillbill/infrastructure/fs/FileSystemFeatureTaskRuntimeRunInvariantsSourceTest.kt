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

  private fun writeSpec(text: String) =
    Files.createTempDirectory("feature-task-runtime-invariants").resolve("spec.md").also { path ->
      Files.writeString(path, text)
    }
}
