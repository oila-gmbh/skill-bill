package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimePhaseFileManifest
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

  @Test
  fun `same issue and pre-existing other issue specs are allowed`() {
    val manifest = FeatureTaskRuntimePhaseFileManifest(
      before = listOf(".feature-specs/SKILL-124-existing/spec.md"),
      after = listOf(
        ".feature-specs/SKILL-120-db-first/spec.md",
        ".feature-specs/SKILL-124-existing/spec.md",
      ),
    )

    assertEquals(
      emptyList(),
      FeatureTaskRuntimePhaseSafetyPolicy.unauthorizedIssueSpecs(manifest, setOf("SKILL-120")),
    )
  }
}
