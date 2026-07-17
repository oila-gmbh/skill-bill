package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseSafetyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimePhaseSafetyPolicyTest {
  @Test
  fun `porcelain paths are normalized and rename uses destination`() {
    val paths = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(
      """
       M src/Main.kt
      ?? .feature-specs/SKILL-124-new/spec.md
      R  old.txt -> new.txt
      """.trimIndent(),
    )

    assertEquals(
      listOf(".feature-specs/SKILL-124-new/spec.md", "new.txt", "src/Main.kt"),
      paths,
    )
  }
}
